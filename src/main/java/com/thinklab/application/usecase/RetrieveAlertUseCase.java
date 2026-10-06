package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AlertResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.exception.AlertNotFoundException;
import com.thinklab.domain.repository.AlertRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one alert of the tenant (BIAN Behavior Qualifier: {@code retrieve}). Staff only. */
@Singleton
public class RetrieveAlertUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAlertUseCase.class);

    private final AlertRepository repository;

    public RetrieveAlertUseCase(AlertRepository repository) {
        this.repository = repository;
    }

    public Mono<AlertResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving Alert by ID: {}", id);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "read an alert"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new AlertNotFoundException("Alert " + id + " not found.")))
                .map(AlertMapper::toResponse);
    }
}
