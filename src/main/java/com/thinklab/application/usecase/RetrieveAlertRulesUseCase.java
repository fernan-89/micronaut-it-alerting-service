package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AlertRuleResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.repository.AlertRuleRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for listing the tenant's rules (BIAN Behavior Qualifier: {@code rule/retrieve}, collection). */
@Singleton
public class RetrieveAlertRulesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAlertRulesUseCase.class);

    private final AlertRuleRepository repository;

    public RetrieveAlertRulesUseCase(AlertRuleRepository repository) {
        this.repository = repository;
    }

    public Flux<AlertRuleResponse> execute(UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the AlertRules of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "list alert rules"))
                .thenMany(Flux.defer(() -> repository.findAll(organisationId)))
                .map(AlertMapper::toResponse);
    }
}
