package com.thinklab.infrastructure.adapter.out.integration.health;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.port.HealthChecksPort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Adapter to the health monitor (ADR-031): the tenant's checks, read through its public route as a service identity. It keeps only what
 * alerting needs (never the target, which can be an internal address). A failure says which service and which status and never repeats its answer.
 */
@Singleton
public class HealthMonitoringAdapter implements HealthChecksPort {

    private static final Logger log = LoggerFactory.getLogger(HealthMonitoringAdapter.class);
    static final String EXECUTOR = "system:alerting";

    private final HealthMonitoringApiClient client;

    public HealthMonitoringAdapter(HealthMonitoringApiClient client) {
        this.client = client;
    }

    @Override
    public Flux<ObservedCheck> list(UUID organisationId) {
        return client.list(organisationId.toString(), EXECUTOR)
                .flatMapMany(Flux::fromIterable)
                .map(check -> new ObservedCheck(check.id(), check.name(), check.assetId(), check.health(), check.status(), check.lastError()))
                .onErrorMap(error -> {
                    String what = error instanceof HttpClientResponseException http ? " (HTTP " + http.getStatus().getCode() + ")" : "";
                    log.warn("[INTEGRATION] The health monitor could not be read{}: {}", what, error.getClass().getSimpleName());
                    return new UpstreamUnavailableException("The health monitor could not be read" + what + ".");
                });
    }

    @Serdeable
    @Introspected
    public record CheckApiResponse(UUID id, String name, UUID assetId, String health, String status, String lastError) {}
}

@Client(id = "health-monitoring-service", path = "/it-health-monitoring/v1")
interface HealthMonitoringApiClient {

    @Get("/retrieve")
    Mono<List<HealthMonitoringAdapter.CheckApiResponse>> list(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);
}
