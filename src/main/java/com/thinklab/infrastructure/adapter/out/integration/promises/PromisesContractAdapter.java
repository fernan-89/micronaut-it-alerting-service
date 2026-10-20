package com.thinklab.infrastructure.adapter.out.integration.promises;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import com.thinklab.domain.port.PromisesPort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Adapter to every registry that answers the promises contract (platform ADR-001, alerting ADR-044): the same route for all of them,
 * {@code GET <registry>/promises/retrieve?changedWithinDays=&limit=}, read as a service identity. The registry has already judged each promise, with a fixed code
 * of its own vocabulary; alerting only turns the verdict into DOWN (broken), UP (kept) or not judged, and the code into the alert's error. It knows nothing of
 * any registry's fields. A failure says which registry and which status and never repeats its answer.
 */
@Singleton
public class PromisesContractAdapter implements PromisesPort {

    private static final Logger log = LoggerFactory.getLogger(PromisesContractAdapter.class);
    static final String EXECUTOR = "system:alerting";
    static final String BROKEN = "DOWN";
    static final String KEPT = "UP";
    static final String NOT_JUDGED = "UNKNOWN";
    /** What the registry is asked for: every broken promise and what changed in this many days (what recovered, what was settled), at most this many of each. */
    static final int CHANGED_WITHIN_DAYS = 30;
    static final int LIMIT = 500;

    private final HttpClient http;
    private final Map<String, RegistryConfig> registries;

    public PromisesContractAdapter(HttpClient http, List<RegistryConfig> registries) {
        this.http = http;
        // Micronaut normalises a property key such as CLOUD_INVENTORY to cloud-inventory: the source is found by its name written either way.
        this.registries = registries.stream().collect(Collectors.toMap(registry -> source(registry.getName()), Function.identity()));
    }

    @Override
    public Flux<ObservedCheck> list(UUID organisationId, Source source) {
        RegistryConfig registry = registries.get(source(source.name()));
        if (registry == null) {
            return Flux.error(new IllegalArgumentException("The source " + source + " has no registry configured: it is not read from one."));
        }
        HttpRequest<?> request = HttpRequest.GET(registry.getUrl() + registry.getPath() + "/promises/retrieve?changedWithinDays=" + CHANGED_WITHIN_DAYS + "&limit=" + LIMIT)
                .header("X-Tenant-Id", organisationId.toString()).header("X-Executor", EXECUTOR);
        return Mono.from(http.retrieve(request, Argument.listOf(PromiseDto.class)))
                .flatMapMany(Flux::fromIterable)
                .map(promise -> toCheck(promise, source))
                .onErrorMap(error -> {
                    String what = error instanceof HttpClientResponseException status ? " (HTTP " + status.getStatus().getCode() + ")" : "";
                    log.warn("[INTEGRATION] The {} registry could not be read{}: {}", source, what, error.getClass().getSimpleName());
                    return new UpstreamUnavailableException("The " + source.noun() + " registry could not be read" + what + ".");
                });
    }

    static String source(String name) {
        return name.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    static ObservedCheck toCheck(PromiseDto promise, Source source) {
        String health = switch (String.valueOf(promise.verdict())) {
            case "BROKEN" -> BROKEN;
            case "KEPT" -> KEPT;
            default -> NOT_JUDGED;
        };
        return new ObservedCheck(promise.id(), promise.name(), promise.assetId(), health, promise.status(), "BROKEN".equals(promise.verdict()) ? promise.code() : null, source);
    }

    /** One promise as the registry answers it (platform ADR-001). */
    @Serdeable
    @Introspected
    public record PromiseDto(UUID id, String name, @Nullable UUID assetId, String verdict, @Nullable String code, String status, Instant changedAt) {}
}
