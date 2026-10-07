package com.thinklab.infrastructure.adapter.out.integration.incident;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.port.IncidentsPort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * Adapter to the incident service (ADR-031): opens an incident and adds an internal note through its public routes, as the service identity
 * {@code system:alerting}, so the incident's audit trail names it. A failure says which service and which status and never repeats its answer.
 */
@Singleton
public class IncidentServiceAdapter implements IncidentsPort {

    private static final Logger log = LoggerFactory.getLogger(IncidentServiceAdapter.class);
    static final String EXECUTOR = "system:alerting";

    private final IncidentApiClient client;

    public IncidentServiceAdapter(IncidentApiClient client) {
        this.client = client;
    }

    @Override
    public Mono<UUID> open(UUID organisationId, IncidentDraft draft) {
        var body = new OpenIncidentApiRequest(draft.title(), draft.description(), draft.impact().name(), draft.urgency().name(), draft.requesterId(),
                draft.assetId() == null ? null : Set.of(draft.assetId()), draft.idempotencyKey());
        return client.open(organisationId.toString(), EXECUTOR, body)
                .map(IncidentApiResponse::id)
                .onErrorMap(error -> unavailable("opened", error));
    }

    @Override
    public Mono<String> status(UUID organisationId, UUID incidentId) {
        return client.retrieve(incidentId, organisationId.toString(), EXECUTOR)
                .map(IncidentStatusApiResponse::status)
                .onErrorMap(error -> unavailable("read", error));
    }

    @Override
    public Mono<Void> comment(UUID organisationId, UUID incidentId, String text) {
        return client.comment(incidentId, organisationId.toString(), EXECUTOR, new CommentApiRequest(text, true))
                .onErrorMap(error -> unavailable("commented on", error));
    }

    private static UpstreamUnavailableException unavailable(String what, Throwable error) {
        String status = error instanceof HttpClientResponseException http ? " (HTTP " + http.getStatus().getCode() + ")" : "";
        log.warn("[INTEGRATION] The incident could not be {}{}: {}", what, status, error.getClass().getSimpleName());
        return new UpstreamUnavailableException("The incident service did not accept the incident to be " + what + status + ".");
    }

    @Serdeable
    @Introspected
    public record OpenIncidentApiRequest(String title, String description, String impact, String urgency, UUID requesterId, Set<UUID> affectedAssetIds, String idempotencyKey) {}

    @Serdeable
    @Introspected
    public record IncidentApiResponse(UUID id) {}

    @Serdeable
    @Introspected
    public record IncidentStatusApiResponse(String status) {}

    @Serdeable
    @Introspected
    public record CommentApiRequest(String text, boolean internal) {}
}

@Client(id = "incident-service", path = "/it-incident-management/v1")
interface IncidentApiClient {

    @Post("/initiate")
    Mono<IncidentServiceAdapter.IncidentApiResponse> open(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor,
                                                          @Body IncidentServiceAdapter.OpenIncidentApiRequest request);

    @Get("/{id}/retrieve")
    Mono<IncidentServiceAdapter.IncidentStatusApiResponse> retrieve(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);

    @Post("/{id}/comment/initiate")
    Mono<Void> comment(@PathVariable UUID id, @Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor,
                       @Body IncidentServiceAdapter.CommentApiRequest request);
}
