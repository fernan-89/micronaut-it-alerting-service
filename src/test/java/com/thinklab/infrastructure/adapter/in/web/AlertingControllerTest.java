package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateAlertRuleRequest;
import com.thinklab.application.dto.request.UpdateAlertRuleRequest;
import com.thinklab.application.dto.response.AlertResponse;
import com.thinklab.application.dto.response.AlertRuleResponse;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.EvaluationResponse;
import com.thinklab.application.usecase.ControlAlertRuleUseCase;
import com.thinklab.application.usecase.EvaluateAlertsUseCase;
import com.thinklab.application.usecase.InitiateAlertRuleUseCase;
import com.thinklab.application.usecase.RetrieveAlertAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveAlertRuleAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveAlertRuleUseCase;
import com.thinklab.application.usecase.RetrieveAlertRulesUseCase;
import com.thinklab.application.usecase.RetrieveAlertUseCase;
import com.thinklab.application.usecase.RetrieveAlertsUseCase;
import com.thinklab.application.usecase.UpdateAlertRuleUseCase;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.domain.repository.AlertRepository;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/** The controller only reads headers and delegates: tenant, executor and role always travel to the use case. */
@ExtendWith(MockitoExtension.class)
class AlertingControllerTest {

    private static final String EXECUTOR = "op-1";
    private final UUID tenant = UUID.randomUUID();
    private final String tenantHeader = tenant.toString();
    private final UUID id = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();

    @Mock private InitiateAlertRuleUseCase initiateRule;
    @Mock private RetrieveAlertRuleUseCase retrieveRule;
    @Mock private RetrieveAlertRulesUseCase retrieveRules;
    @Mock private UpdateAlertRuleUseCase updateRule;
    @Mock private ControlAlertRuleUseCase controlRule;
    @Mock private RetrieveAlertRuleAuditLogUseCase ruleAuditLog;
    @Mock private RetrieveAlertUseCase retrieveAlert;
    @Mock private RetrieveAlertsUseCase retrieveAlerts;
    @Mock private RetrieveAlertAuditLogUseCase alertAuditLog;
    @Mock private EvaluateAlertsUseCase evaluate;

    private AlertingController controller;

    @BeforeEach
    void setUp() {
        controller = new AlertingController(initiateRule, retrieveRule, retrieveRules, updateRule, controlRule, ruleAuditLog, retrieveAlert, retrieveAlerts, alertAuditLog, evaluate);
    }

    private AlertRuleResponse rule() {
        return new AlertRuleResponse(id, tenant, "Production", null, "HIGH", "MEDIUM", requester, "ACTIVE", Instant.now(), Instant.now());
    }

    private AlertResponse alert() {
        return new AlertResponse(id, tenant, UUID.randomUUID(), UUID.randomUUID(), "Intranet", null, "OPEN", Instant.now(), null, null, "timeout", null, Instant.now());
    }

    private List<AuditEntryResponse> trail() {
        return List.of(new AuditEntryResponse(Instant.now(), "OPENED", "system:alerting", null, "OPEN", "d"));
    }

    @Test
    @DisplayName("the rule write routes delegate with the tenant, the executor and the role")
    void ruleWrites() {
        var initiateRequest = new InitiateAlertRuleRequest("Production", null, Severity.HIGH, Severity.MEDIUM, requester);
        var updateRequest = new UpdateAlertRuleRequest("Production", null, Severity.LOW, Severity.LOW, requester);
        when(initiateRule.execute(tenant, initiateRequest, EXECUTOR, "AGENT")).thenReturn(Mono.just(rule()));
        when(updateRule.execute(id, tenant, updateRequest, EXECUTOR, "AGENT")).thenReturn(Mono.empty());
        when(controlRule.execute(id, tenant, ControlAlertRuleUseCase.Action.PAUSE, EXECUTOR, "AGENT")).thenReturn(Mono.empty());
        when(controlRule.execute(id, tenant, ControlAlertRuleUseCase.Action.RESUME, EXECUTOR, "AGENT")).thenReturn(Mono.empty());

        StepVerifier.create(controller.initiateRule(tenantHeader, EXECUTOR, "AGENT", initiateRequest)).assertNext(r -> assertEquals(HttpStatus.CREATED, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.updateRule(id, tenantHeader, EXECUTOR, "AGENT", updateRequest)).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.pauseRule(id, tenantHeader, EXECUTOR, "AGENT")).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.resumeRule(id, tenantHeader, EXECUTOR, "AGENT")).assertNext(r -> assertEquals(HttpStatus.NO_CONTENT, r.getStatus())).verifyComplete();
    }

    @Test
    @DisplayName("the rule read routes delegate with the tenant and the role")
    void ruleReads() {
        when(retrieveRule.execute(id, tenant, "AGENT")).thenReturn(Mono.just(rule()));
        when(retrieveRules.execute(tenant, "AGENT")).thenReturn(Flux.just(rule()));
        when(ruleAuditLog.execute(id, tenant, "AGENT")).thenReturn(Mono.just(trail()));

        StepVerifier.create(controller.retrieveRule(id, tenantHeader, "AGENT")).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveRules(tenantHeader, "AGENT")).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(controller.ruleAuditLog(id, tenantHeader, "AGENT")).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("the alert read routes delegate with the tenant and the role, and the filters of the list travel together")
    void alertReads() {
        UUID check = UUID.randomUUID();
        var filter = new AlertRepository.Filter(AlertStatus.OPEN, check);
        when(retrieveAlert.execute(id, tenant, "AGENT")).thenReturn(Mono.just(alert()));
        when(retrieveAlerts.execute(tenant, filter, "AGENT")).thenReturn(Flux.just(alert()));
        when(alertAuditLog.execute(id, tenant, "AGENT")).thenReturn(Mono.just(trail()));

        StepVerifier.create(controller.retrieve(id, tenantHeader, "AGENT")).assertNext(r -> assertEquals(HttpStatus.OK, r.getStatus())).verifyComplete();
        StepVerifier.create(controller.retrieveAll(tenantHeader, "AGENT", AlertStatus.OPEN, check)).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
        StepVerifier.create(controller.alertAuditLog(id, tenantHeader, "AGENT")).assertNext(list -> assertEquals(1, list.size())).verifyComplete();
    }

    @Test
    @DisplayName("evaluating now delegates as the person who asked and answers what it did")
    void evaluateNow() {
        when(evaluate.execute(tenant, EXECUTOR, "AGENT")).thenReturn(Mono.just(new EvaluationResponse(1, 0, 1)));

        StepVerifier.create(controller.evaluate(tenantHeader, EXECUTOR, "AGENT")).assertNext(r -> {
            assertEquals(HttpStatus.OK, r.getStatus());
            assertEquals(1, r.body().opened());
        }).verifyComplete();
    }
}
