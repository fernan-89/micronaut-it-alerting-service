package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AlertRuleResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.exception.AlertRuleNotFoundException;
import com.thinklab.domain.repository.AlertRuleRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for reading one rule of the tenant (BIAN Behavior Qualifier: {@code rule/retrieve}). Staff only. */
@Singleton
public class RetrieveAlertRuleUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAlertRuleUseCase.class);

    private final AlertRuleRepository repository;

    public RetrieveAlertRuleUseCase(AlertRuleRepository repository) {
        this.repository = repository;
    }

    public Mono<AlertRuleResponse> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving AlertRule by ID: {}", id);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "read an alert rule"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new AlertRuleNotFoundException("AlertRule " + id + " not found.")))
                .map(AlertMapper::toResponse);
    }
}
