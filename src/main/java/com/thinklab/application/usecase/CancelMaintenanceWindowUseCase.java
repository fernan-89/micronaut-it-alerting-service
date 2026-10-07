package com.thinklab.application.usecase;

import com.thinklab.domain.exception.MaintenanceWindowNotFoundException;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for ending a silence at once (BIAN Behavior Qualifier: {@code window/control/cancel}). Staff only; the save is guarded on the status loaded. */
@Singleton
public class CancelMaintenanceWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(CancelMaintenanceWindowUseCase.class);

    private final MaintenanceWindowRepository repository;

    public CancelMaintenanceWindowUseCase(MaintenanceWindowRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Cancelling MaintenanceWindow ID: {}", id);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "cancel a maintenance window"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new MaintenanceWindowNotFoundException("MaintenanceWindow " + id + " not found.")))
                .flatMap(window -> {
                    var statusBefore = window.getStatus();
                    return repository.save(window, statusBefore, window.cancel(executor));
                });
    }
}
