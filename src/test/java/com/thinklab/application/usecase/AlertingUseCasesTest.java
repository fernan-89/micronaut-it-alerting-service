package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateAlertRuleRequest;
import com.thinklab.application.dto.request.UpdateAlertRuleRequest;
import com.thinklab.application.dto.response.EvaluationResponse;
import com.thinklab.domain.exception.AlertAccessDeniedException;
import com.thinklab.domain.exception.AlertNotFoundException;
import com.thinklab.domain.exception.AlertRuleNotFoundException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.domain.repository.AlertRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertingUseCasesTest {

    @Mock private AlertRuleRepository rules;
    @Mock private AlertRepository alerts;
    @Mock private HashServicePort hashService;
    @Mock private AlertEvaluator evaluator;

    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();

    private AlertRule rule() {
        return AlertRule.createNew(UUID.randomUUID(), org, "Production", null, Severity.HIGH, Severity.MEDIUM, requester, AlertRule.Options.NONE, "op");
    }

    private Alert alert() {
        return Alert.createNew(UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(), "Intranet", null, "timeout", "system:alerting");
    }

    // ------------------------------------------------------------------ Rules

    @Test
    @DisplayName("initiate takes a sovereign id and saves the rule; a requester is refused before an id is taken")
    void initiate() {
        when(hashService.generateSovereignId("alert-rule-creation")).thenReturn(Mono.just(UUID.randomUUID()));
        when(rules.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        InitiateAlertRuleUseCase useCase = new InitiateAlertRuleUseCase(hashService, rules);
        var request = new InitiateAlertRuleRequest("Production", null, Severity.HIGH, Severity.MEDIUM, requester, null, null, null, null);

        StepVerifier.create(useCase.execute(org, request, "op", "AGENT")).assertNext(response -> {
            assertEquals("ACTIVE", response.status());
            assertEquals("HIGH", response.impact());
            assertNull(response.checkId());
            assertEquals(requester, response.requesterId());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(org, request, "op", "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("update and pause/resume go through the workflow with the status loaded; a requester and an unknown rule are refused")
    void updateAndControl() {
        AlertRule rule = rule();
        when(rules.findById(rule.getId(), org)).thenReturn(Mono.just(rule));
        when(rules.save(any(), any(), any())).thenReturn(Mono.empty());
        AlertRuleWorkflow workflow = new AlertRuleWorkflow(rules);
        UpdateAlertRuleUseCase update = new UpdateAlertRuleUseCase(workflow);
        ControlAlertRuleUseCase control = new ControlAlertRuleUseCase(workflow);

        StepVerifier.create(update.execute(rule.getId(), org, new UpdateAlertRuleRequest("Renamed", null, Severity.LOW, Severity.LOW, requester, null, null, null, null), "op", "AGENT")).verifyComplete();
        assertEquals("Renamed", rule.getName());
        StepVerifier.create(control.execute(rule.getId(), org, ControlAlertRuleUseCase.Action.PAUSE, "op", null)).verifyComplete();
        assertEquals(RuleStatus.PAUSED, rule.getStatus());
        StepVerifier.create(control.execute(rule.getId(), org, ControlAlertRuleUseCase.Action.RESUME, "op", null)).verifyComplete();
        assertEquals(RuleStatus.ACTIVE, rule.getStatus());
        verify(rules).save(any(), eq(RuleStatus.PAUSED), any());

        StepVerifier.create(control.execute(rule.getId(), org, ControlAlertRuleUseCase.Action.PAUSE, "op", "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(rules.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(control.execute(missing, org, ControlAlertRuleUseCase.Action.PAUSE, "op", null)).expectError(AlertRuleNotFoundException.class).verify();
    }

    @Test
    @DisplayName("rule reads are for staff, scoped to the tenant, and 404 on an unknown rule")
    void ruleReads() {
        AlertRule rule = rule();
        rule.pause("op");
        when(rules.findById(rule.getId(), org)).thenReturn(Mono.just(rule));
        when(rules.findAll(org)).thenReturn(Flux.just(rule));

        StepVerifier.create(new RetrieveAlertRuleUseCase(rules).execute(rule.getId(), org, "AGENT")).assertNext(r -> assertEquals("Production", r.name())).verifyComplete();
        StepVerifier.create(new RetrieveAlertRulesUseCase(rules).execute(org, "AGENT")).expectNextCount(1).verifyComplete();
        StepVerifier.create(new RetrieveAlertRuleAuditLogUseCase(rules).execute(rule.getId(), org, "AGENT"))
                .assertNext(entries -> { assertEquals("INITIATED", entries.get(0).action()); assertEquals("ACTIVE", entries.get(1).fromStatus()); }).verifyComplete();

        StepVerifier.create(new RetrieveAlertRuleUseCase(rules).execute(rule.getId(), org, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        StepVerifier.create(new RetrieveAlertRulesUseCase(rules).execute(org, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        StepVerifier.create(new RetrieveAlertRuleAuditLogUseCase(rules).execute(rule.getId(), org, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(rules.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(new RetrieveAlertRuleUseCase(rules).execute(missing, org, null)).expectError(AlertRuleNotFoundException.class).verify();
        StepVerifier.create(new RetrieveAlertRuleAuditLogUseCase(rules).execute(missing, org, null)).expectError(AlertRuleNotFoundException.class).verify();
    }

    // ------------------------------------------------------------------ Alerts

    @Test
    @DisplayName("alert reads are for staff, scoped to the tenant, and 404 on an unknown alert; the audit log tells when it opened and resolved")
    void alertReads() {
        Alert alert = alert();
        alert.resolve("system:alerting");
        AlertRepository.Filter filter = new AlertRepository.Filter(AlertStatus.RESOLVED, null);
        when(alerts.findById(alert.getId(), org)).thenReturn(Mono.just(alert));
        when(alerts.findAll(org, filter)).thenReturn(Flux.just(alert));

        StepVerifier.create(new RetrieveAlertUseCase(alerts).execute(alert.getId(), org, "AGENT")).assertNext(r -> assertEquals("RESOLVED", r.status())).verifyComplete();
        StepVerifier.create(new RetrieveAlertsUseCase(alerts).execute(org, filter, "AGENT")).expectNextCount(1).verifyComplete();
        StepVerifier.create(new RetrieveAlertAuditLogUseCase(alerts).execute(alert.getId(), org, "AGENT"))
                .assertNext(entries -> assertEquals(List.of("OPENED", "RESOLVED"), entries.stream().map(e -> e.action()).toList())).verifyComplete();

        StepVerifier.create(new RetrieveAlertUseCase(alerts).execute(alert.getId(), org, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        StepVerifier.create(new RetrieveAlertsUseCase(alerts).execute(org, filter, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        StepVerifier.create(new RetrieveAlertAuditLogUseCase(alerts).execute(alert.getId(), org, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        UUID missing = UUID.randomUUID();
        when(alerts.findById(missing, org)).thenReturn(Mono.empty());
        StepVerifier.create(new RetrieveAlertUseCase(alerts).execute(missing, org, null)).expectError(AlertNotFoundException.class).verify();
        StepVerifier.create(new RetrieveAlertAuditLogUseCase(alerts).execute(missing, org, null)).expectError(AlertNotFoundException.class).verify();
    }

    // ------------------------------------------------------------------ Evaluation

    @Test
    @DisplayName("evaluating now runs the same evaluation as the scheduler, as the person who asked; a requester is refused")
    void evaluateNow() {
        when(evaluator.evaluate(org, "op")).thenReturn(Mono.just(new EvaluationResponse(1, 2, 3, 0, 0)));
        EvaluateAlertsUseCase useCase = new EvaluateAlertsUseCase(evaluator);

        StepVerifier.create(useCase.execute(org, "op", "AGENT")).assertNext(r -> assertEquals("1/2/3", r.opened() + "/" + r.resolved() + "/" + r.incidentsOpened())).verifyComplete();
        StepVerifier.create(useCase.execute(org, "op", "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        verify(evaluator, never()).evaluate(org, "other");
    }
}
