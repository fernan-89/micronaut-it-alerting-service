package com.thinklab.domain.port;

import com.thinklab.domain.model.AlertRule.Severity;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound Port to the incident service (ADR-031): open an incident and comment on it, acting as a service identity. A failure is {@link com.thinklab.domain.exception.UpstreamUnavailableException}. */
public interface IncidentsPort {

    /** Opens the incident and returns its id. */
    Mono<UUID> open(UUID organisationId, IncidentDraft draft);

    /** Adds an INTERNAL note: a person reading the incident sees it, a requester never does. */
    Mono<Void> comment(UUID organisationId, UUID incidentId, String text);

    /** The status of the incident (NEW, ACKNOWLEDGED, IN_PROGRESS, ON_HOLD, RESOLVED, CLOSED, CANCELLED...), to know whether it is still being worked. */
    Mono<String> status(UUID organisationId, UUID incidentId);

    /** {@code idempotencyKey} names the thing the incident is for (the alert id): opening twice under it gives the one incident (incident ADR-034). */
    record IncidentDraft(String title, String description, Severity impact, Severity urgency, UUID requesterId, UUID assetId, String idempotencyKey) {
    }
}
