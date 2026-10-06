package com.thinklab.application.usecase;

import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleAuditEntry;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for pausing and resuming a rule (BIAN Behavior Qualifier: {@code rule/control/*}). */
@Singleton
public class ControlAlertRuleUseCase {

    private static final Logger log = LoggerFactory.getLogger(ControlAlertRuleUseCase.class);

    /** What a person can ask for; each constant says how the aggregate performs it. */
    public enum Action {
        PAUSE {
            @Override RuleAuditEntry apply(AlertRule rule, String executor) { return rule.pause(executor); }
        },
        RESUME {
            @Override RuleAuditEntry apply(AlertRule rule, String executor) { return rule.resume(executor); }
        };

        abstract RuleAuditEntry apply(AlertRule rule, String executor);
    }

    private final AlertRuleWorkflow workflow;

    public ControlAlertRuleUseCase(AlertRuleWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, Action action, String executor, String role) {
        log.info("[USE CASE] {} on AlertRule ID: {}", action, id);

        return workflow.apply(id, organisationId, role, "pause or resume an alert rule", rule -> action.apply(rule, executor));
    }
}
