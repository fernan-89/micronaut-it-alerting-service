package com.thinklab.application.usecase;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.HealthChecksPort;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import com.thinklab.domain.port.IncidentsPort;
import com.thinklab.domain.port.IncidentsPort.IncidentDraft;
import com.thinklab.domain.port.PromisesPort;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.domain.repository.AlertRuleRepository;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Rules that watch the promises the registries judge (ADR-037): the same alert, incident and resolution as a health check, from another source. */
@ExtendWith(MockitoExtension.class)
class AlertEvaluatorPromisesTest {

    private static final String EXECUTOR = "system:alerting";

    @Mock private AlertRuleRepository rules;
    @Mock private AlertRepository alerts;
    @Mock private HealthChecksPort health;
    @Mock private PromisesPort promises;
    @Mock private IncidentsPort incidents;
    @Mock private HashServicePort hashService;
    @Mock private MaintenanceWindowRepository windows;
    @Mock private NoticeDispatcher dispatcher;

    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private AlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AlertEvaluator(rules, alerts, windows, health, promises, incidents, hashService, dispatcher, Clock.systemUTC());
        lenient().when(health.list(org)).thenReturn(Flux.empty());
        lenient().when(alerts.findOpen(org)).thenReturn(Flux.empty());
        lenient().when(windows.findCurrent(any(), any())).thenReturn(Flux.empty());
        lenient().when(alerts.findResolvedSince(any(), any())).thenReturn(Flux.empty());
        lenient().when(alerts.saveIncidentLink(any(), any())).thenReturn(Mono.just(true));
        lenient().when(dispatcher.deliver(any(), any(), any(), any())).thenReturn(Mono.just(0));
        lenient().when(alerts.save(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(alerts.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        lenient().when(hashService.generateSovereignId("alert-creation")).thenAnswer(call -> Mono.just(UUID.randomUUID()));
        lenient().when(incidents.open(eq(org), any())).thenReturn(Mono.just(UUID.randomUUID()));
    }

    private AlertRule rule(Source source, UUID policyId) {
        return AlertRule.createNew(UUID.randomUUID(), org, "Promises", source, policyId, Severity.HIGH, Severity.MEDIUM, requester, AlertRule.Options.NONE, "op");
    }

    private ObservedCheck policy(Source source, UUID id, String health, String status, String error) {
        return new ObservedCheck(id, "Nightly", assetId, health, status, error, source);
    }

    private void evaluates(int opened, int resolved, int incidentsOpened) {
        StepVerifier.create(evaluator.evaluate(org, EXECUTOR))
                .assertNext(result -> assertEquals(opened + "/" + resolved + "/" + incidentsOpened, result.opened() + "/" + result.resolved() + "/" + result.incidentsOpened()))
                .verifyComplete();
    }

    @Test
    @DisplayName("a broken promise that a rule of its source covers gets an alert and an incident that says a promise is not being kept")
    void opensForABrokenPromise() {
        AlertRule rule = rule(Source.BACKUP, null);
        when(rules.findAll(org)).thenReturn(Flux.just(rule));
        when(promises.list(org, Source.BACKUP)).thenReturn(Flux.just(policy(Source.BACKUP, UUID.randomUUID(), "DOWN", "ACTIVE", "BACKUP_RPO_BREACHED")));

        evaluates(1, 0, 1);

        ArgumentCaptor<IncidentDraft> draft = ArgumentCaptor.forClass(IncidentDraft.class);
        verify(incidents).open(eq(org), draft.capture());
        assertEquals("Promise broken: Nightly", draft.getValue().title());
        assertEquals("The backup policy Nightly is not being kept (BACKUP_RPO_BREACHED). Opened by alerting rule Promises.", draft.getValue().description());
        assertEquals(assetId, draft.getValue().assetId());
    }

    @Test
    @DisplayName("a rule for one policy covers only that policy; a rule of another source covers none of it")
    void coversBySourceAndId() {
        UUID watched = UUID.randomUUID();
        when(rules.findAll(org)).thenReturn(Flux.just(rule(Source.PATCH, watched), rule(Source.CAPACITY, null)));
        when(promises.list(org, Source.PATCH)).thenReturn(Flux.just(policy(Source.PATCH, watched, "DOWN", "ACTIVE", "PATCH_OVERDUE"),
                policy(Source.PATCH, UUID.randomUUID(), "DOWN", "ACTIVE", "PATCH_OVERDUE")));
        when(promises.list(org, Source.CAPACITY)).thenReturn(Flux.just(policy(Source.CAPACITY, UUID.randomUUID(), "UP", "ACTIVE", null)));

        evaluates(1, 0, 1);
    }

    @Test
    @DisplayName("a rule for health checks never reads a registry, and a rule for a registry does not cover a health check")
    void healthRulesStayWithHealth() {
        UUID checkId = UUID.randomUUID();
        when(rules.findAll(org)).thenReturn(Flux.just(rule(Source.HEALTH_CHECK, null), rule(Source.BACKUP, checkId)));
        when(health.list(org)).thenReturn(Flux.just(policy(Source.HEALTH_CHECK, checkId, "DOWN", "ACTIVE", "timeout")));
        when(promises.list(org, Source.BACKUP)).thenReturn(Flux.empty());

        evaluates(1, 0, 1);

        verify(promises, never()).list(org, Source.PATCH);
        verify(promises, never()).list(org, Source.CAPACITY);
    }

    @Test
    @DisplayName("a paused rule does not make a registry be read")
    void pausedRuleReadsNothing() {
        AlertRule paused = rule(Source.CAPACITY, null);
        paused.pause("op");
        when(rules.findAll(org)).thenReturn(Flux.just(paused));

        evaluates(0, 0, 0);

        verify(promises, never()).list(any(), any());
    }

    @Test
    @DisplayName("a registry that cannot be read is skipped in this round: the health checks are still looked at and nothing of that registry changes")
    void unreachableRegistryIsSkipped() {
        UUID checkId = UUID.randomUUID();
        when(rules.findAll(org)).thenReturn(Flux.just(rule(Source.BACKUP, null), rule(Source.HEALTH_CHECK, null)));
        when(health.list(org)).thenReturn(Flux.just(policy(Source.HEALTH_CHECK, checkId, "DOWN", "ACTIVE", "timeout")));
        when(promises.list(org, Source.BACKUP)).thenReturn(Flux.error(new UpstreamUnavailableException("The backup policy registry could not be read (HTTP 503).")));

        evaluates(1, 0, 1);
    }

    @Test
    @DisplayName("a promise that is kept again resolves its alert, and one that is not judged leaves the alert as it is")
    void resolvesWhenKeptAgain() {
        AlertRule rule = rule(Source.BACKUP, null);
        UUID kept = UUID.randomUUID();
        UUID notJudged = UUID.randomUUID();
        Alert keptAlert = Alert.createNew(UUID.randomUUID(), org, rule.getId(), kept, "Nightly", assetId, "BACKUP_RPO_BREACHED", EXECUTOR);
        Alert pausedAlert = Alert.createNew(UUID.randomUUID(), org, rule.getId(), notJudged, "Weekly", assetId, "BACKUP_RPO_BREACHED", EXECUTOR);
        when(rules.findAll(org)).thenReturn(Flux.just(rule));
        when(alerts.findOpen(org)).thenReturn(Flux.just(keptAlert, pausedAlert));
        when(promises.list(org, Source.BACKUP)).thenReturn(Flux.just(policy(Source.BACKUP, kept, "UP", "ACTIVE", null), policy(Source.BACKUP, notJudged, "UNKNOWN", "PAUSED", null)));

        evaluates(0, 1, 0);

        assertEquals(AlertStatus.RESOLVED, keptAlert.getStatus());
        assertEquals(AlertStatus.OPEN, pausedAlert.getStatus());
        assertTrue(pausedAlert.getIncidentId() == null);
    }
}
