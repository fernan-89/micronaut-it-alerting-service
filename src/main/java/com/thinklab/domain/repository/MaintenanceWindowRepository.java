package com.thinklab.domain.repository;

import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.model.MaintenanceWindow.WindowAuditEntry;
import com.thinklab.domain.model.MaintenanceWindow.WindowStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/** Outbound Port for MaintenanceWindow persistence. {@link #save} is a guarded write on the status loaded (ADR-033); every lookup is tenant-scoped. */
public interface MaintenanceWindowRepository {

    Mono<MaintenanceWindow> create(MaintenanceWindow window);

    Mono<MaintenanceWindow> findById(UUID id, UUID organisationId);

    /** Newest start first. */
    Flux<MaintenanceWindow> findAll(UUID organisationId);

    /** The ACTIVE windows that have not ended yet: all the evaluation needs to know whether a check is silenced. */
    Flux<MaintenanceWindow> findCurrent(UUID organisationId, Instant now);

    Mono<Void> save(MaintenanceWindow window, WindowStatus expectedStatus, WindowAuditEntry auditEntry);
}
