package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.MaintenanceWindowResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.exception.MaintenanceWindowNotFoundException;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one window of the tenant (BIAN Behavior Qualifier: {@code window/retrieve}). Staff only; another tenant is a 404. */
@Singleton
public class RetrieveMaintenanceWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveMaintenanceWindowUseCase.class);

    private final MaintenanceWindowRepository repository;

    public RetrieveMaintenanceWindowUseCase(MaintenanceWindowRepository repository) {
        this.repository = repository;
    }

    public Mono<MaintenanceWindowResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving MaintenanceWindow ID: {}", id);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "read a maintenance window"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new MaintenanceWindowNotFoundException("MaintenanceWindow " + id + " not found.")))
                .map(AlertMapper::toResponse);
    }
}
