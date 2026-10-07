package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.MaintenanceWindowResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for listing the windows of the tenant (BIAN Behavior Qualifier: {@code window/retrieve}, collection), newest start first. Staff only. */
@Singleton
public class RetrieveMaintenanceWindowsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveMaintenanceWindowsUseCase.class);

    private final MaintenanceWindowRepository repository;

    public RetrieveMaintenanceWindowsUseCase(MaintenanceWindowRepository repository) {
        this.repository = repository;
    }

    public Flux<MaintenanceWindowResponse> execute(UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the MaintenanceWindows of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "list maintenance windows"))
                .thenMany(Flux.defer(() -> repository.findAll(organisationId)))
                .map(AlertMapper::toResponse);
    }
}
