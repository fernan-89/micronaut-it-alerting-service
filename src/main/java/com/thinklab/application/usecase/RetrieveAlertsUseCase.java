package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AlertResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.repository.AlertRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for listing the tenant's alerts, newest first, filterable by status and by check (BIAN Behavior Qualifier: {@code retrieve}, collection). */
@Singleton
public class RetrieveAlertsUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAlertsUseCase.class);

    private final AlertRepository repository;

    public RetrieveAlertsUseCase(AlertRepository repository) {
        this.repository = repository;
    }

    public Flux<AlertResponse> execute(UUID organisationId, AlertRepository.Filter filter, String role) {
        log.info("[USE CASE] Retrieving the Alerts of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "list alerts"))
                .thenMany(Flux.defer(() -> repository.findAll(organisationId, filter)))
                .map(AlertMapper::toResponse);
    }
}
