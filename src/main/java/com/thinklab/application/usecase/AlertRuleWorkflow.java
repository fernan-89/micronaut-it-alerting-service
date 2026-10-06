package com.thinklab.application.usecase;

import com.thinklab.domain.exception.AlertRuleNotFoundException;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleAuditEntry;
import com.thinklab.domain.repository.AlertRuleRepository;
import jakarta.inject.Singleton;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.function.Function;

/** The shape every staff change to a rule shares: staff only, load (a 404 if it is not the tenant's), apply, save with the status loaded. */
@Singleton
public class AlertRuleWorkflow {

    private final AlertRuleRepository repository;

    public AlertRuleWorkflow(AlertRuleRepository repository) {
        this.repository = repository;
    }

    public Mono<Void> apply(UUID id, UUID organisationId, String role, String operation, Function<AlertRule, RuleAuditEntry> action) {
        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, operation))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new AlertRuleNotFoundException("AlertRule " + id + " not found.")))
                .flatMap(rule -> {
                    var statusBefore = rule.getStatus();
                    RuleAuditEntry entry = action.apply(rule);
                    return repository.save(rule, statusBefore, entry);
                });
    }
}
