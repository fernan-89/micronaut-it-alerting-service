package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateAlertRuleRequest;
import com.thinklab.application.dto.response.AlertRuleResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.AlertRuleRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for saying what to do when a check goes down (BIAN Behavior Qualifier: {@code rule/initiate}). */
@Singleton
public class InitiateAlertRuleUseCase {

    private static final Logger log = LoggerFactory.getLogger(InitiateAlertRuleUseCase.class);

    private final HashServicePort hashServicePort;
    private final AlertRuleRepository repository;

    public InitiateAlertRuleUseCase(HashServicePort hashServicePort, AlertRuleRepository repository) {
        this.hashServicePort = hashServicePort;
        this.repository = repository;
    }

    public Mono<AlertRuleResponse> execute(UUID organisationId, InitiateAlertRuleRequest request, String executor, String role) {
        log.info("[USE CASE] Creating the AlertRule [{}] for organisation: {}", request.name(), organisationId);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "create an alert rule"))
                .then(Mono.defer(() -> hashServicePort.generateSovereignId("alert-rule-creation")))
                .map(id -> AlertRule.createNew(id, organisationId, request.name(), request.checkId(), request.impact(), request.urgency(), request.requesterId(), executor))
                .flatMap(repository::create)
                .map(AlertMapper::toResponse);
    }
}
