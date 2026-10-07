package com.thinklab.domain.repository;

import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertAuditEntry;
import com.thinklab.domain.model.Alert.AlertStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Outbound Port for Alert persistence. {@link #save} is a guarded write on the status loaded (ADR-033). Every lookup is tenant-scoped except {@link #openTenants}. */
public interface AlertRepository {

    /**
     * Inserts the alert. A second OPEN alert for the same check is {@link com.thinklab.domain.exception.DuplicateAlertException}: the partial
     * unique index is what makes "one alert per outage" true with several instances.
     */
    Mono<Alert> create(Alert alert);

    Mono<Alert> findById(UUID id, UUID organisationId);

    Flux<Alert> findAll(UUID organisationId, Filter filter);

    Flux<Alert> findOpen(UUID organisationId);

    Mono<Void> save(Alert alert, AlertStatus expectedStatus, AlertAuditEntry auditEntry);

    /** The alerts of the tenant resolved at or after that moment, newest first: the candidates for a reopening and for a notice of the resolution. */
    Flux<Alert> findResolvedSince(UUID organisationId, Instant since);

    /**
     * Records that an incident was opened for the alert, only while it has none: false when another instance linked it first (the same incident,
     * as the incident service answers a repeated key with the first one), so the audit trail holds one INCIDENT_OPENED.
     */
    Mono<Boolean> saveIncidentLink(Alert alert, AlertAuditEntry auditEntry);

    /**
     * Takes the right to send a notice, atomically: true only for the one caller that finds it not yet sent, tried fewer than {@code maxAttempts}
     * times and not tried in the last {@code minGap}, and counts that attempt. With several instances only one sends.
     */
    Mono<Boolean> claimNotice(UUID alertId, UUID organisationId, String key, Instant now, Duration minGap, int maxAttempts);

    /** What came of a claimed notice: sent (the time) or failed (a fixed reason). */
    Mono<Void> recordNotice(UUID alertId, UUID organisationId, String key, Instant sentAt, String error);

    /** The tenants that have an OPEN alert: their alerts must still be resolved when the check recovers, even if every rule was paused since. */
    Flux<UUID> openTenants();

    record Filter(AlertStatus status, UUID checkId) {
    }
}
