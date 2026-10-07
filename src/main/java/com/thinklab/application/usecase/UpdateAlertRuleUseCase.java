package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.UpdateAlertRuleRequest;
import com.thinklab.domain.model.AlertRule;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for changing a rule (BIAN Behavior Qualifier: {@code rule/update}). */
@Singleton
public class UpdateAlertRuleUseCase {

    private static final Logger log = LoggerFactory.getLogger(UpdateAlertRuleUseCase.class);

    private final AlertRuleWorkflow workflow;

    public UpdateAlertRuleUseCase(AlertRuleWorkflow workflow) {
        this.workflow = workflow;
    }

    public Mono<Void> execute(UUID id, UUID organisationId, UpdateAlertRuleRequest request, String executor, String role) {
        log.info("[USE CASE] Updating AlertRule ID: {}", id);

        return workflow.apply(id, organisationId, role, "change an alert rule",
                rule -> rule.update(request.name(), request.checkId(), request.impact(), request.urgency(), request.requesterId(),
                        AlertRule.Options.of(request.notifyTarget(), request.escalateTarget(), request.escalateAfterMinutes(), request.reopenWithinMinutes()), executor));
    }
}
