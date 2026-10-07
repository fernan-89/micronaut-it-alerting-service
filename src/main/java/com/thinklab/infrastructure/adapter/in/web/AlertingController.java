package com.thinklab.infrastructure.adapter.in.web;

import com.thinklab.application.dto.request.InitiateAlertRuleRequest;
import com.thinklab.application.dto.request.InitiateMaintenanceWindowRequest;
import com.thinklab.application.dto.request.UpdateAlertRuleRequest;
import com.thinklab.application.dto.response.AlertResponse;
import com.thinklab.application.dto.response.AlertRuleResponse;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.EvaluationResponse;
import com.thinklab.application.dto.response.MaintenanceWindowResponse;
import com.thinklab.application.usecase.CancelMaintenanceWindowUseCase;
import com.thinklab.application.usecase.ControlAlertRuleUseCase;
import com.thinklab.application.usecase.EvaluateAlertsUseCase;
import com.thinklab.application.usecase.InitiateAlertRuleUseCase;
import com.thinklab.application.usecase.InitiateMaintenanceWindowUseCase;
import com.thinklab.application.usecase.RetrieveAlertAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveAlertRuleAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveAlertRuleUseCase;
import com.thinklab.application.usecase.RetrieveAlertRulesUseCase;
import com.thinklab.application.usecase.RetrieveAlertUseCase;
import com.thinklab.application.usecase.RetrieveAlertsUseCase;
import com.thinklab.application.usecase.RetrieveMaintenanceWindowAuditLogUseCase;
import com.thinklab.application.usecase.RetrieveMaintenanceWindowUseCase;
import com.thinklab.application.usecase.RetrieveMaintenanceWindowsUseCase;
import com.thinklab.application.usecase.UpdateAlertRuleUseCase;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.repository.AlertRepository;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Inbound Web Adapter for the {@code it-alerting} Service Domain.
 *
 * <p><b>BIAN-Aligned Resource Model (ADR-013):</b> {@link com.thinklab.domain.model.Alert} is the Control Record, at the root (read-only:
 * alerts are opened and resolved by the evaluation, never by hand); {@link com.thinklab.domain.model.AlertRule} is the secondary aggregate,
 * under {@code /rule}. There is no {@code DELETE}: a rule is paused.
 *
 * <p><b>Tenant on every route, staff only (ADR-032):</b> {@code X-Tenant-Id} scopes every lookup (another tenant's alert is a 404) and
 * {@code X-Role} makes every route refuse a {@code REQUESTER} with 403 {@code ERR-ALR-00403}.
 */
@Controller("/it-alerting/v1")
public class AlertingController {

    private static final Logger log = LoggerFactory.getLogger(AlertingController.class);
    static final String TENANT_HEADER = "X-Tenant-Id";
    static final String EXECUTOR_HEADER = "X-Executor";
    static final String ROLE_HEADER = "X-Role";

    private final InitiateAlertRuleUseCase initiateRule;
    private final RetrieveAlertRuleUseCase retrieveRule;
    private final RetrieveAlertRulesUseCase retrieveRules;
    private final UpdateAlertRuleUseCase updateRule;
    private final ControlAlertRuleUseCase controlRule;
    private final RetrieveAlertRuleAuditLogUseCase ruleAuditLog;
    private final RetrieveAlertUseCase retrieveAlert;
    private final RetrieveAlertsUseCase retrieveAlerts;
    private final RetrieveAlertAuditLogUseCase alertAuditLog;
    private final EvaluateAlertsUseCase evaluate;
    private final InitiateMaintenanceWindowUseCase initiateWindow;
    private final RetrieveMaintenanceWindowUseCase retrieveWindow;
    private final RetrieveMaintenanceWindowsUseCase retrieveWindows;
    private final CancelMaintenanceWindowUseCase cancelWindow;
    private final RetrieveMaintenanceWindowAuditLogUseCase windowAuditLog;

    public AlertingController(InitiateAlertRuleUseCase initiateRule, RetrieveAlertRuleUseCase retrieveRule, RetrieveAlertRulesUseCase retrieveRules,
                              UpdateAlertRuleUseCase updateRule, ControlAlertRuleUseCase controlRule, RetrieveAlertRuleAuditLogUseCase ruleAuditLog,
                              RetrieveAlertUseCase retrieveAlert, RetrieveAlertsUseCase retrieveAlerts, RetrieveAlertAuditLogUseCase alertAuditLog,
                              EvaluateAlertsUseCase evaluate, InitiateMaintenanceWindowUseCase initiateWindow, RetrieveMaintenanceWindowUseCase retrieveWindow,
                              RetrieveMaintenanceWindowsUseCase retrieveWindows, CancelMaintenanceWindowUseCase cancelWindow, RetrieveMaintenanceWindowAuditLogUseCase windowAuditLog) {
        this.initiateRule = initiateRule;
        this.retrieveRule = retrieveRule;
        this.retrieveRules = retrieveRules;
        this.updateRule = updateRule;
        this.controlRule = controlRule;
        this.ruleAuditLog = ruleAuditLog;
        this.retrieveAlert = retrieveAlert;
        this.retrieveAlerts = retrieveAlerts;
        this.alertAuditLog = alertAuditLog;
        this.evaluate = evaluate;
        this.initiateWindow = initiateWindow;
        this.retrieveWindow = retrieveWindow;
        this.retrieveWindows = retrieveWindows;
        this.cancelWindow = cancelWindow;
        this.windowAuditLog = windowAuditLog;
    }

    // ------------------------------------------------------------------ Rules

    /** Behavior Qualifier: {@code rule/initiate}. What to do when a check goes down. */
    @Post("/rule/initiate")
    public Mono<HttpResponse<AlertRuleResponse>> initiateRule(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateAlertRuleRequest request
    ) {
        log.info("[ACTION: INITIATE_ALERT_RULE] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiateRule.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code rule/retrieve}. One rule of the tenant. */
    @Get("/rule/{id}/retrieve")
    public Mono<HttpResponse<AlertRuleResponse>> retrieveRule(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveRule.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code rule/retrieve} (collection), oldest first. */
    @Get("/rule/retrieve")
    public Mono<List<AlertRuleResponse>> retrieveRules(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveRules.execute(UUID.fromString(tenantId), role).collectList());
    }

    /** Behavior Qualifier: {@code rule/update}. The whole definition again. */
    @Put("/rule/{id}/update")
    public Mono<HttpResponse<Void>> updateRule(
            @PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid UpdateAlertRuleRequest request
    ) {
        return Mono.defer(() -> updateRule.execute(id, UUID.fromString(tenantId), request, executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code rule/control/pause}. ACTIVE -&gt; PAUSED: opens no new alert. */
    @Put("/rule/{id}/control/pause")
    public Mono<HttpResponse<Void>> pauseRule(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                              @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlAlertRuleUseCase.Action.PAUSE, executor, role);
    }

    /** Behavior Qualifier: {@code rule/control/resume}. PAUSED -&gt; ACTIVE. */
    @Put("/rule/{id}/control/resume")
    public Mono<HttpResponse<Void>> resumeRule(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                               @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        return control(id, tenantId, ControlAlertRuleUseCase.Action.RESUME, executor, role);
    }

    /** Behavior Qualifier: {@code rule/audit-log/retrieve}. */
    @Get("/rule/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> ruleAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> ruleAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    // ------------------------------------------------------------------ Maintenance windows

    /** Behavior Qualifier: {@code window/initiate}. A planned silence: no alert is opened or reopened and nobody is told for the check (or every check) meanwhile. */
    @Post("/window/initiate")
    public Mono<HttpResponse<MaintenanceWindowResponse>> initiateWindow(
            @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
            @Header(ROLE_HEADER) @Nullable String role, @Body @Valid InitiateMaintenanceWindowRequest request
    ) {
        log.info("[ACTION: INITIATE_MAINTENANCE_WINDOW] [EXECUTOR: {}] Received request for organisation: {}", executor, tenantId);

        return initiateWindow.execute(UUID.fromString(tenantId), request, executor, role).map(HttpResponse::created);
    }

    /** Behavior Qualifier: {@code window/retrieve}. One window of the tenant. */
    @Get("/window/{id}/retrieve")
    public Mono<HttpResponse<MaintenanceWindowResponse>> retrieveWindow(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveWindow.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code window/retrieve} (collection), newest start first. */
    @Get("/window/retrieve")
    public Mono<List<MaintenanceWindowResponse>> retrieveWindows(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveWindows.execute(UUID.fromString(tenantId), role).collectList());
    }

    /** Behavior Qualifier: {@code window/control/cancel}. ACTIVE -&gt; CANCELLED: the silence stops at once. */
    @Put("/window/{id}/control/cancel")
    public Mono<HttpResponse<Void>> cancelWindow(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId,
                                                 @Header(EXECUTOR_HEADER) @NotBlank String executor, @Header(ROLE_HEADER) @Nullable String role) {
        log.info("[ACTION: CANCEL_MAINTENANCE_WINDOW] [EXECUTOR: {}] for ID: {}", executor, id);

        return Mono.defer(() -> cancelWindow.execute(id, UUID.fromString(tenantId), executor, role)).thenReturn(HttpResponse.noContent());
    }

    /** Behavior Qualifier: {@code window/audit-log/retrieve}. */
    @Get("/window/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> windowAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> windowAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    // ------------------------------------------------------------------ Alerts

    /** Behavior Qualifier: {@code retrieve}. One alert of the tenant. */
    @Get("/{id}/retrieve")
    public Mono<HttpResponse<AlertResponse>> retrieve(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> retrieveAlert.execute(id, UUID.fromString(tenantId), role)).map(HttpResponse::ok);
    }

    /** Behavior Qualifier: {@code retrieve} (collection), newest first, by status and by the check it is about. */
    @Get("/retrieve")
    public Mono<List<AlertResponse>> retrieveAll(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role,
                                                 @QueryValue @Nullable AlertStatus status, @QueryValue @Nullable UUID checkId) {
        return Mono.defer(() -> retrieveAlerts.execute(UUID.fromString(tenantId), new AlertRepository.Filter(status, checkId), role).collectList());
    }

    /** Behavior Qualifier: {@code audit-log/retrieve}. When it opened, when its incident did, when it resolved. */
    @Get("/{id}/audit-log/retrieve")
    public Mono<List<AuditEntryResponse>> alertAuditLog(@PathVariable UUID id, @Header(TENANT_HEADER) @NotBlank String tenantId, @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> alertAuditLog.execute(id, UUID.fromString(tenantId), role));
    }

    /** Behavior Qualifier: {@code evaluation/execute}. Looks at the tenant right now, as the scheduler does; 502 when the monitor cannot be read. */
    @Put("/evaluation/execute")
    public Mono<HttpResponse<EvaluationResponse>> evaluate(@Header(TENANT_HEADER) @NotBlank String tenantId, @Header(EXECUTOR_HEADER) @NotBlank String executor,
                                                           @Header(ROLE_HEADER) @Nullable String role) {
        return Mono.defer(() -> evaluate.execute(UUID.fromString(tenantId), executor, role)).map(HttpResponse::ok);
    }

    private Mono<HttpResponse<Void>> control(UUID id, String tenantId, ControlAlertRuleUseCase.Action action, String executor, String role) {
        log.info("[ACTION: CONTROL_ALERT_RULE] [EXECUTOR: {}] {} for ID: {}", executor, action, id);

        return Mono.defer(() -> controlRule.execute(id, UUID.fromString(tenantId), action, executor, role)).thenReturn(HttpResponse.noContent());
    }
}
