package com.thinklab.application.usecase;

import com.thinklab.domain.exception.DuplicateAlertException;
import com.thinklab.domain.exception.InvalidAlertStatusException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.HealthChecksPort;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import com.thinklab.domain.port.IncidentsPort;
import com.thinklab.domain.port.IncidentsPort.IncidentDraft;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.domain.repository.AlertRuleRepository;
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

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlertEvaluatorTest {

    private static final String EXECUTOR = "system:alerting";

    @Mock private AlertRuleRepository rules;
    @Mock private AlertRepository alerts;
    @Mock private HealthChecksPort health;
    @Mock private IncidentsPort incidents;
    @Mock private HashServicePort hashService;

    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final UUID assetId = UUID.randomUUID();
    private AlertEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new AlertEvaluator(rules, alerts, health, incidents, hashService);
        lenient().when(alerts.save(any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(alerts.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        lenient().when(hashService.generateSovereignId("alert-creation")).thenAnswer(call -> Mono.just(UUID.randomUUID()));
    }

    private AlertRule rule(String name, UUID checkId) {
        return AlertRule.createNew(UUID.randomUUID(), org, name, checkId, Severity.HIGH, Severity.MEDIUM, requester, "op");
    }

    private ObservedCheck check(UUID id, String health, String status, String error) {
        return new ObservedCheck(id, "Intranet", assetId, health, status, error);
    }

    private void world(List<AlertRule> ruleList, List<ObservedCheck> checks, List<Alert> open) {
        when(rules.findAll(org)).thenReturn(Flux.fromIterable(ruleList));
        when(health.list(org)).thenReturn(Flux.fromIterable(checks));
        when(alerts.findOpen(org)).thenReturn(Flux.fromIterable(open));
    }

    private Alert openAlert(AlertRule rule, UUID checkId, UUID incidentId) {
        Alert alert = Alert.createNew(UUID.randomUUID(), org, rule.getId(), checkId, "Intranet", assetId, "timeout", EXECUTOR);
        if (incidentId != null) {
            alert.linkIncident(incidentId, EXECUTOR);
        }
        return alert;
    }

    private void evaluates(int opened, int resolved, int incidentsOpened) {
        StepVerifier.create(evaluator.evaluate(org, EXECUTOR))
                .assertNext(result -> assertEquals(opened + "/" + resolved + "/" + incidentsOpened, result.opened() + "/" + result.resolved() + "/" + result.incidentsOpened()))
                .verifyComplete();
    }

    // ------------------------------------------------------------------ Opening

    @Test
    @DisplayName("a DOWN active check that a rule covers gets an alert saved first and then its incident, filed as the rule says")
    void opens() {
        AlertRule rule = rule("Production", null);
        UUID checkId = UUID.randomUUID();
        UUID incident = UUID.randomUUID();
        world(List.of(rule), List.of(check(checkId, "DOWN", "ACTIVE", "timeout")), List.of());
        when(incidents.open(eq(org), any())).thenReturn(Mono.just(incident));

        evaluates(1, 0, 1);

        ArgumentCaptor<IncidentDraft> draft = ArgumentCaptor.forClass(IncidentDraft.class);
        verify(incidents).open(eq(org), draft.capture());
        assertEquals("Health check down: Intranet", draft.getValue().title());
        assertTrue(draft.getValue().description().contains("(timeout)") && draft.getValue().description().contains("Production"));
        assertEquals(Severity.HIGH, draft.getValue().impact());
        assertEquals(Severity.MEDIUM, draft.getValue().urgency());
        assertEquals(requester, draft.getValue().requesterId());
        assertEquals(assetId, draft.getValue().assetId());
        ArgumentCaptor<Alert> saved = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).save(saved.capture(), eq(AlertStatus.OPEN), any());
        assertEquals(incident, saved.getValue().getIncidentId());
        assertEquals(checkId, saved.getValue().getCheckId());
    }

    @Test
    @DisplayName("a check with no error code still gets an incident, whose text just does not name one")
    void noErrorCode() {
        world(List.of(rule("Production", null)), List.of(check(UUID.randomUUID(), "DOWN", "ACTIVE", null)), List.of());
        when(incidents.open(eq(org), any())).thenReturn(Mono.just(UUID.randomUUID()));

        evaluates(1, 0, 1);

        ArgumentCaptor<IncidentDraft> draft = ArgumentCaptor.forClass(IncidentDraft.class);
        verify(incidents).open(eq(org), draft.capture());
        assertTrue(!draft.getValue().description().contains("()") && draft.getValue().description().contains("is down."));
    }

    @Test
    @DisplayName("nothing is opened for a check that is UP, UNKNOWN or paused, one that no active rule covers, or one that already has an open alert")
    void opensNothing() {
        AlertRule paused = rule("Paused rule", null);
        paused.pause("op");
        AlertRule forOther = rule("Other check", UUID.randomUUID());
        UUID covered = UUID.randomUUID();
        AlertRule forCovered = rule("Covered", covered);
        Alert existing = openAlert(forCovered, covered, UUID.randomUUID());
        world(List.of(paused, forOther, forCovered),
                List.of(check(UUID.randomUUID(), "UP", "ACTIVE", null), check(UUID.randomUUID(), "UNKNOWN", "ACTIVE", null), check(UUID.randomUUID(), "DOWN", "PAUSED", "timeout"),
                        check(UUID.randomUUID(), "DOWN", "ACTIVE", "timeout"), check(covered, "DOWN", "ACTIVE", "timeout")),
                List.of(existing));

        evaluates(0, 0, 0);

        verify(alerts, never()).create(any());
        verify(incidents, never()).open(any(), any());
    }

    @Test
    @DisplayName("when several active rules cover a check, the oldest one opens the alert, and a paused older one is skipped")
    void oldestRuleWins() {
        AlertRule paused = rule("Paused first", null);
        paused.pause("op");
        AlertRule oldest = rule("Oldest", null);
        AlertRule newer = rule("Newer", null);
        world(List.of(paused, oldest, newer), List.of(check(UUID.randomUUID(), "DOWN", "ACTIVE", "timeout")), List.of());
        when(incidents.open(eq(org), any())).thenReturn(Mono.just(UUID.randomUUID()));

        evaluates(1, 0, 1);

        ArgumentCaptor<Alert> created = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).create(created.capture());
        assertEquals(oldest.getId(), created.getValue().getRuleId());
    }

    @Test
    @DisplayName("an alert another instance opened first is a lost race, not an error: nothing is counted and no incident is opened")
    void lostInsertRace() {
        world(List.of(rule("Production", null)), List.of(check(UUID.randomUUID(), "DOWN", "ACTIVE", "timeout")), List.of());
        doReturn(Mono.error(new DuplicateAlertException("already open"))).when(alerts).create(any());

        evaluates(0, 0, 0);

        verify(incidents, never()).open(any(), any());
    }

    // ------------------------------------------------------------------ The incident service is down, then is back

    @Test
    @DisplayName("when the incident cannot be opened the alert stays OPEN with a short reason, and the next evaluation retries it")
    void incidentRetried() {
        AlertRule rule = rule("Production", null);
        UUID checkId = UUID.randomUUID();
        world(List.of(rule), List.of(check(checkId, "DOWN", "ACTIVE", "timeout")), List.of());
        when(incidents.open(eq(org), any())).thenReturn(Mono.error(new UpstreamUnavailableException("The incident service did not accept the incident to be opened (HTTP 503).")));

        evaluates(1, 0, 0);

        ArgumentCaptor<Alert> failed = ArgumentCaptor.forClass(Alert.class);
        verify(alerts).save(failed.capture(), eq(AlertStatus.OPEN), any());
        Alert alert = failed.getValue();
        assertNull(alert.getIncidentId());
        assertTrue(alert.getProblem().contains("503"));

        UUID incident = UUID.randomUUID();
        world(List.of(rule), List.of(check(checkId, "DOWN", "ACTIVE", "timeout")), List.of(alert));
        when(incidents.open(eq(org), any())).thenReturn(Mono.just(incident));

        evaluates(0, 0, 1);

        assertEquals(incident, alert.getIncidentId());
        assertNull(alert.getProblem());
    }

    @Test
    @DisplayName("an open alert is not retried when it has its incident, when its rule or check is gone, or when its check is not DOWN")
    void retryConditions() {
        AlertRule rule = rule("Production", null);
        UUID withIncident = UUID.randomUUID();
        UUID ruleGone = UUID.randomUUID();
        UUID checkGone = UUID.randomUUID();
        UUID notDown = UUID.randomUUID();
        Alert a = openAlert(rule, withIncident, UUID.randomUUID());
        Alert b = Alert.createNew(UUID.randomUUID(), org, UUID.randomUUID(), ruleGone, "Intranet", null, "timeout", EXECUTOR);
        Alert c = openAlert(rule, checkGone, null);
        Alert d = openAlert(rule, notDown, null);
        world(List.of(rule), List.of(check(withIncident, "DOWN", "ACTIVE", "timeout"), check(ruleGone, "DOWN", "ACTIVE", "timeout"), check(notDown, "UNKNOWN", "PAUSED", null)),
                List.of(a, b, c, d));

        evaluates(0, 0, 0);

        verify(incidents, never()).open(any(), any());
    }

    // ------------------------------------------------------------------ Resolving

    @Test
    @DisplayName("an open alert whose check is UP again is resolved with a guarded write, and the incident gets an internal note")
    void resolves() {
        AlertRule rule = rule("Production", null);
        UUID checkId = UUID.randomUUID();
        UUID incident = UUID.randomUUID();
        Alert alert = openAlert(rule, checkId, incident);
        world(List.of(rule), List.of(check(checkId, "UP", "ACTIVE", null)), List.of(alert));
        when(incidents.comment(eq(org), eq(incident), any())).thenReturn(Mono.empty());

        evaluates(0, 1, 0);

        assertEquals(AlertStatus.RESOLVED, alert.getStatus());
        verify(alerts).save(eq(alert), eq(AlertStatus.OPEN), any());
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(incidents).comment(eq(org), eq(incident), text.capture());
        assertTrue(text.getValue().contains("Intranet") && text.getValue().contains("answering again"));
    }

    @Test
    @DisplayName("an alert that never got an incident is resolved without a comment; a comment that cannot be made does not undo the resolution")
    void resolveEdges() {
        AlertRule rule = rule("Production", null);
        UUID noIncidentCheck = UUID.randomUUID();
        UUID commentFailsCheck = UUID.randomUUID();
        UUID incident = UUID.randomUUID();
        Alert noIncident = openAlert(rule, noIncidentCheck, null);
        Alert commentFails = openAlert(rule, commentFailsCheck, incident);
        world(List.of(rule), List.of(check(noIncidentCheck, "UP", "ACTIVE", null), check(commentFailsCheck, "UP", "ACTIVE", null)), List.of(noIncident, commentFails));
        when(incidents.comment(eq(org), eq(incident), any())).thenReturn(Mono.error(new UpstreamUnavailableException("down")));

        evaluates(0, 2, 0);

        verify(incidents, times(1)).comment(any(), any(), any());
        assertEquals(AlertStatus.RESOLVED, commentFails.getStatus());
    }

    @Test
    @DisplayName("a resolution another instance made first is a lost race: not counted, and the incident is not commented twice")
    void lostResolveRace() {
        AlertRule rule = rule("Production", null);
        UUID checkId = UUID.randomUUID();
        Alert alert = openAlert(rule, checkId, UUID.randomUUID());
        world(List.of(rule), List.of(check(checkId, "UP", "ACTIVE", null)), List.of(alert));
        when(alerts.save(any(), any(), any())).thenReturn(Mono.error(new InvalidAlertStatusException("lost")));

        evaluates(0, 0, 0);

        verify(incidents, never()).comment(any(), any(), any());
    }

    @Test
    @DisplayName("an open alert is left alone while its check is still DOWN, paused or no longer listed")
    void leavesAlone() {
        AlertRule rule = rule("Production", null);
        UUID down = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        Alert stillDown = openAlert(rule, down, UUID.randomUUID());
        Alert deleted = openAlert(rule, gone, UUID.randomUUID());
        world(List.of(rule), List.of(check(down, "DOWN", "ACTIVE", "timeout")), List.of(stillDown, deleted));

        evaluates(0, 0, 0);

        assertEquals(AlertStatus.OPEN, stillDown.getStatus());
        assertEquals(AlertStatus.OPEN, deleted.getStatus());
    }

    @Test
    @DisplayName("when the health monitor cannot be read the evaluation fails with that error, and nothing is changed")
    void monitorDown() {
        when(rules.findAll(org)).thenReturn(Flux.empty());
        when(alerts.findOpen(org)).thenReturn(Flux.empty());
        when(health.list(org)).thenReturn(Flux.error(new UpstreamUnavailableException("The health monitor could not be read (HTTP 503).")));

        StepVerifier.create(evaluator.evaluate(org, EXECUTOR)).expectError(UpstreamUnavailableException.class).verify();

        verify(alerts, never()).create(any());
    }

    @Test
    @DisplayName("two OPEN alerts for one check (which the unique index should prevent) never break the round: the first one stands for the check")
    void duplicateOpenAlerts() {
        AlertRule rule = rule("Production", null);
        UUID checkId = UUID.randomUUID();
        world(List.of(rule), List.of(check(checkId, "DOWN", "ACTIVE", "timeout")),
                List.of(openAlert(rule, checkId, UUID.randomUUID()), openAlert(rule, checkId, UUID.randomUUID())));

        evaluates(0, 0, 0);

        verify(incidents, never()).open(any(), any());
    }
}
