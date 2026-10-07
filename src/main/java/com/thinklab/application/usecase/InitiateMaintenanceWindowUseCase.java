package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateMaintenanceWindowRequest;
import com.thinklab.application.dto.response.MaintenanceWindowResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Use Case for planning a silence (BIAN Behavior Qualifier: {@code window/initiate}). Staff only. */
@Singleton
public class InitiateMaintenanceWindowUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateMaintenanceWindowUseCase.class);

    private final HashServicePort hashServicePort;
    private final MaintenanceWindowRepository repository;
    private final Clock clock;

    public InitiateMaintenanceWindowUseCase(HashServicePort hashServicePort, MaintenanceWindowRepository repository, Clock clock) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
        this.clock = clock;
    }

    public Mono<MaintenanceWindowResponse> execute(UUID organisationId, InitiateMaintenanceWindowRequest request, String executor, String role) {
        log.info("[USE CASE] Planning the MaintenanceWindow [{}] for organisation: {}", request.name(), organisationId);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "plan a maintenance window"))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("maintenance-window-creation")))
                .map(id -> MaintenanceWindow.createNew(id, organisationId, request.name(), request.checkId(), request.startsAt(), request.endsAt(), Instant.now(clock), executor))
                .flatMap(repository::create)
                .map(AlertMapper::toResponse);
    }
}
