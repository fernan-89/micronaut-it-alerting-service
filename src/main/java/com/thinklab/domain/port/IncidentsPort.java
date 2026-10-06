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

    record IncidentDraft(String title, String description, Severity impact, Severity urgency, UUID requesterId, UUID assetId) {
    }
}
