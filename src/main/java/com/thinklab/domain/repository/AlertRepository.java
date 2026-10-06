package com.thinklab.domain.repository;

import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertAuditEntry;
import com.thinklab.domain.model.Alert.AlertStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

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

    /** The tenants that have an OPEN alert: their alerts must still be resolved when the check recovers, even if every rule was paused since. */
    Flux<UUID> openTenants();

    record Filter(AlertStatus status, UUID checkId) {
    }
}
