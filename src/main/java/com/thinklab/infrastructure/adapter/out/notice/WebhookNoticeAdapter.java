package com.thinklab.infrastructure.adapter.out.notice;

import com.thinklab.application.config.AlertingProperties;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.port.NoticePort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Posts a notice to a webhook (ADR-036). The rule names an environment variable (starting with {@code THINKLAB_ALERT_HOOK_}), and its value
 * is the address: it is read here, when used, and never stored, logged, audited or returned. The address must be https (a host listed in
 * {@code thinklab.alerting.insecure-hosts}, a test double, may be plain http). The body carries ids, the check name, the monitor's fixed-vocabulary
 * error and the severities; a Slack or Teams style {@code text} line comes with it. A failure says what happened and which status, and never
 * repeats what the webhook answered.
 */
@Singleton
public class WebhookNoticeAdapter implements NoticePort {

    private static final Logger log = LoggerFactory.getLogger(WebhookNoticeAdapter.class);
    static final String PREFIX = "THINKLAB_ALERT_HOOK_";

    private final HttpClient client;
    private final AlertingProperties properties;
    private final Function<String, String> environment;

    public WebhookNoticeAdapter(@Client("/") HttpClient client, AlertingProperties properties) {
        this(client, properties, System::getenv);
    }

    WebhookNoticeAdapter(HttpClient client, AlertingProperties properties, Function<String, String> environment) {
        this.client = client;
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public Mono<Void> send(String target, Notice notice) {
        return Mono.defer(() -> {
            String address = target != null && target.startsWith(PREFIX) ? environment.apply(target) : null;
            if (address == null || address.isBlank()) {
                return Mono.error(new UpstreamUnavailableException("The notification webhook is not configured."));
            }
            if (!allowed(address.trim())) {
                return Mono.error(new UpstreamUnavailableException("The notification webhook address is not allowed."));
            }
            var body = new NoticeApiRequest(notice.text(), notice.event(), notice.alertId(), notice.incidentId(), notice.checkName(), notice.error(),
                    notice.impact(), notice.urgency(), notice.at().toString());
            return Mono.from(client.exchange(HttpRequest.POST(address.trim(), body)))
                    .then()
                    .onErrorMap(error -> !(error instanceof UpstreamUnavailableException), error -> {
                        if (error instanceof HttpClientResponseException http) {
                            log.warn("[INTEGRATION] The notification webhook refused a {} notice: HTTP {}", notice.event(), http.getStatus().getCode());
                            return new UpstreamUnavailableException("The notification webhook refused the notice (HTTP " + http.getStatus().getCode() + ").");
                        }
                        log.warn("[INTEGRATION] The notification webhook could not be reached for a {} notice: {}", notice.event(), error.getClass().getSimpleName());
                        return new UpstreamUnavailableException("The notification webhook could not be reached.");
                    });
        });
    }

    private boolean allowed(String address) {
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || uri.getScheme() == null) {
            return false;
        }
        boolean secure = "https".equals(uri.getScheme().toLowerCase(Locale.ROOT));
        boolean plainDouble = "http".equals(uri.getScheme().toLowerCase(Locale.ROOT)) && properties.getInsecureHosts().stream().anyMatch(insecure -> insecure.equalsIgnoreCase(host));
        return secure || plainDouble;
    }

    @Serdeable
    @Introspected
    public record NoticeApiRequest(String text, String event, UUID alertId, UUID incidentId, String check, String error, String impact, String urgency, String at) {}
}
