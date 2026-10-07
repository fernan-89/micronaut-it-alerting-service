package com.thinklab.application.usecase;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.Alert.Notice;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.Options;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.port.IncidentsPort;
import com.thinklab.domain.port.NoticePort;
import com.thinklab.domain.repository.AlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What people are told, when, once, and what a failure does (ADR-036). */
@ExtendWith(MockitoExtension.class)
class NoticeDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final String HOOK = "THINKLAB_ALERT_HOOK_OPS";
    private static final String ESCALATION = "THINKLAB_ALERT_HOOK_BOSS";

    @Mock private AlertRepository alerts;
    @Mock private IncidentsPort incidents;
    @Mock private NoticePort notices;

    private final UUID org = UUID.randomUUID();
    private final UUID checkId = UUID.randomUUID();
    private NoticeDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new NoticeDispatcher(alerts, incidents, notices, Clock.fixed(NOW, ZoneOffset.UTC));
        lenient().when(alerts.claimNotice(any(), eq(org), any(), any(), any(), eq(NoticeDispatcher.MAX_ATTEMPTS))).thenReturn(Mono.just(true));
        lenient().when(alerts.recordNotice(any(), eq(org), any(), any(), any())).thenReturn(Mono.empty());
        lenient().when(notices.send(any(), any())).thenReturn(Mono.empty());
    }

    private AlertRule rule(Options options) {
        return AlertRule.createNew(UUID.randomUUID(), org, "Production", null, Severity.HIGH, Severity.MEDIUM, UUID.randomUUID(), options, "op");
    }

    private Alert open(AlertRule rule, Instant openedAt, UUID incident, int reopenCount, Instant reopenedAt, Map<String, Notice> sent) {
        return Alert.reconstitute(UUID.randomUUID(), org, rule.getId(), checkId, "Intranet", null, AlertStatus.OPEN, openedAt, null, incident, "timeout", null, openedAt,
                reopenCount, reopenedAt, sent, List.of());
    }

    private Alert resolved(AlertRule rule, Instant resolvedAt) {
        return Alert.reconstitute(UUID.randomUUID(), org, rule.getId(), checkId, "Intranet", null, AlertStatus.RESOLVED, resolvedAt.minus(Duration.ofMinutes(10)), resolvedAt,
                UUID.randomUUID(), "timeout", null, resolvedAt, 0, null, Map.of(), List.of());
    }

    private Alert openWithIncident(AlertRule rule, Instant openedAt) {
        return open(rule, openedAt, UUID.randomUUID(), 0, null, Map.of());
    }

    private void delivers(int expected, List<Alert> open, List<Alert> resolved, Map<UUID, AlertRule> byId, List<MaintenanceWindow> windows) {
        StepVerifier.create(dispatcher.deliver(open, resolved, byId, windows)).expectNext(expected).verifyComplete();
    }

    private Map<UUID, AlertRule> byId(AlertRule... rules) {
        return Arrays.stream(rules).collect(Collectors.toMap(AlertRule::getId, r -> r));
    }

    @Test
    @DisplayName("an OPEN alert with its incident is announced as OPENED to the rule's webhook, claimed first and recorded as sent after")
    void opened() {
        AlertRule rule = rule(new Options(HOOK, null, null, 30));
        Alert alert = openWithIncident(rule, NOW.minus(Duration.ofMinutes(2)));

        delivers(1, List.of(alert), List.of(), byId(rule), List.of());

        ArgumentCaptor<NoticePort.Notice> sent = ArgumentCaptor.forClass(NoticePort.Notice.class);
        verify(notices).send(eq(HOOK), sent.capture());
        assertEquals("OPENED", sent.getValue().event());
        assertEquals(alert.getId(), sent.getValue().alertId());
        assertEquals(alert.getIncidentId(), sent.getValue().incidentId());
        assertEquals("HIGH", sent.getValue().impact());
        assertEquals("MEDIUM", sent.getValue().urgency());
        assertEquals(NOW, sent.getValue().at());
        verify(alerts).claimNotice(alert.getId(), org, "OPENED_0", NOW, NoticeDispatcher.RETRY_GAP, NoticeDispatcher.MAX_ATTEMPTS);
        verify(alerts).recordNotice(alert.getId(), org, "OPENED_0", NOW, null);
    }

    @Test
    @DisplayName("a reopened alert is announced as REOPENED, under the key of its own cycle")
    void reopened() {
        AlertRule rule = rule(new Options(HOOK, null, null, 30));
        Alert alert = open(rule, NOW.minus(Duration.ofHours(1)), UUID.randomUUID(), 2, NOW.minus(Duration.ofMinutes(5)),
                Map.of("OPENED_0", new Notice(1, NOW, NOW, null)));

        delivers(1, List.of(alert), List.of(), byId(rule), List.of());

        verify(notices).send(eq(HOOK), argThat(notice -> "REOPENED".equals(notice.event())));
        verify(alerts).claimNotice(eq(alert.getId()), eq(org), eq("REOPENED_2"), any(), any(), anyInt());
    }

    @Test
    @DisplayName("an alert still without its incident is held for a minute (so the notice can name it), then announced anyway")
    void graceForTheIncident() {
        AlertRule rule = rule(new Options(HOOK, null, null, 30));

        delivers(0, List.of(open(rule, NOW.minus(Duration.ofSeconds(30)), null, 0, null, Map.of())), List.of(), byId(rule), List.of());
        verifyNoInteractions(notices);

        delivers(1, List.of(open(rule, NOW.minus(NoticeDispatcher.INCIDENT_GRACE), null, 0, null, Map.of())), List.of(), byId(rule), List.of());
        verify(notices).send(eq(HOOK), argThat(notice -> notice.incidentId() == null));
    }

    @Test
    @DisplayName("an alert whose rule is gone, or that a maintenance window silences right now, tells nobody; a window of another check or of the past does not silence")
    void silencedOrOrphan() {
        AlertRule rule = rule(new Options(HOOK, null, null, 30));
        Alert alert = openWithIncident(rule, NOW.minus(Duration.ofMinutes(5)));
        MaintenanceWindow silencing = MaintenanceWindow.createNew(UUID.randomUUID(), org, "Patching", checkId, NOW.minus(Duration.ofMinutes(1)), NOW.plus(Duration.ofHours(1)), NOW, "op");

        delivers(0, List.of(alert), List.of(), Map.of(), List.of());
        delivers(0, List.of(alert), List.of(), byId(rule), List.of(silencing));
        verifyNoInteractions(notices);

        MaintenanceWindow other = MaintenanceWindow.createNew(UUID.randomUUID(), org, "Other", UUID.randomUUID(), NOW.minus(Duration.ofMinutes(1)), NOW.plus(Duration.ofHours(1)), NOW, "op");
        MaintenanceWindow later = MaintenanceWindow.createNew(UUID.randomUUID(), org, "Later", null, NOW.plus(Duration.ofHours(1)), NOW.plus(Duration.ofHours(2)), NOW, "op");
        delivers(1, List.of(alert), List.of(), byId(rule), List.of(other, later));
    }

    @Test
    @DisplayName("a rule with no webhook, a notice already sent and a notice tried three times are not sent again; a lost claim sends nothing")
    void notOwed() {
        AlertRule silent = rule(Options.NONE);
        delivers(0, List.of(openWithIncident(silent, NOW.minus(Duration.ofMinutes(5)))), List.of(), byId(silent), List.of());

        AlertRule rule = rule(new Options(HOOK, null, null, 30));
        Alert sent = open(rule, NOW.minus(Duration.ofMinutes(5)), UUID.randomUUID(), 0, null, Map.of("OPENED_0", new Notice(1, NOW, NOW, null)));
        Alert exhausted = open(rule, NOW.minus(Duration.ofMinutes(5)), UUID.randomUUID(), 0, null, Map.of("OPENED_0", new Notice(3, NOW, null, "x")));
        delivers(0, List.of(sent, exhausted), List.of(), byId(rule), List.of());
        verifyNoInteractions(notices);
        verify(alerts, never()).claimNotice(any(), any(), any(), any(), any(), anyInt());

        Alert contested = openWithIncident(rule, NOW.minus(Duration.ofMinutes(5)));
        when(alerts.claimNotice(eq(contested.getId()), eq(org), any(), any(), any(), anyInt())).thenReturn(Mono.just(false));
        delivers(0, List.of(contested), List.of(), byId(rule), List.of());
        verifyNoInteractions(notices);
    }

    @Test
    @DisplayName("a webhook that fails is kept as a fixed reason on the alert, counts as not delivered and never fails the evaluation")
    void failure() {
        AlertRule rule = rule(new Options(HOOK, null, null, 30));
        Alert alert = openWithIncident(rule, NOW.minus(Duration.ofMinutes(5)));
        when(notices.send(eq(HOOK), any())).thenReturn(Mono.error(new UpstreamUnavailableException("The notification webhook could not be reached.")));

        delivers(0, List.of(alert), List.of(), byId(rule), List.of());

        verify(alerts).recordNotice(alert.getId(), org, "OPENED_0", null, "The notification webhook could not be reached.");
        verify(alerts, never()).recordNotice(any(), any(), any(), eq(NOW), isNull());
    }

    @Test
    @DisplayName("a resolution is announced for an hour, to the rule's webhook; later, with no time recorded or with no rule, it is not")
    void resolution() {
        AlertRule rule = rule(new Options(HOOK, null, null, 30));
        Alert recent = resolved(rule, NOW.minus(Duration.ofMinutes(10)));
        Alert old = resolved(rule, NOW.minus(NoticeDispatcher.RESOLVED_NOTICE_WINDOW));
        Alert untimed = Alert.reconstitute(UUID.randomUUID(), org, rule.getId(), checkId, "Intranet", null, AlertStatus.RESOLVED, NOW, null, null, null, null, NOW, 0, null, Map.of(), List.of());
        Alert orphan = resolved(rule(Options.NONE), NOW.minus(Duration.ofMinutes(1)));

        delivers(1, List.of(), List.of(recent, old, untimed, orphan), Map.of(rule.getId(), rule), List.of());
        verify(notices).send(eq(HOOK), argThat(notice -> "RESOLVED".equals(notice.event()) && notice.alertId().equals(recent.getId())));

        delivers(0, List.of(), List.of(recent), Map.of(), List.of());
    }

    // ------------------------------------------------------------------ Escalation

    private AlertRule escalating() {
        return rule(new Options(HOOK, ESCALATION, 10, 30));
    }

    @Test
    @DisplayName("an incident still NEW after the escalation time is escalated to the escalation target, once")
    void escalates() {
        AlertRule rule = escalating();
        Alert alert = openWithIncident(rule, NOW.minus(Duration.ofMinutes(10)));
        when(incidents.status(org, alert.getIncidentId())).thenReturn(Mono.just("NEW"));

        delivers(2, List.of(alert), List.of(), byId(rule), List.of());

        verify(notices).send(eq(HOOK), argThat(notice -> "OPENED".equals(notice.event())));
        verify(notices).send(eq(ESCALATION), argThat(notice -> "ESCALATED".equals(notice.event())));
        verify(alerts).recordNotice(alert.getId(), org, "ESCALATED_0", NOW, null);
    }

    @Test
    @DisplayName("an incident somebody acknowledged is marked as not needed (and counts as nothing sent)")
    void acknowledged() {
        AlertRule rule = escalating();
        Alert alert = open(rule, NOW.minus(Duration.ofMinutes(11)), UUID.randomUUID(), 0, null, Map.of("OPENED_0", new Notice(1, NOW, NOW, null)));
        when(incidents.status(org, alert.getIncidentId())).thenReturn(Mono.just("IN_PROGRESS"));

        delivers(0, List.of(alert), List.of(), byId(rule), List.of());

        verify(alerts).recordNotice(alert.getId(), org, "ESCALATED_0", NOW, NoticeDispatcher.NOT_NEEDED);
        verify(notices, never()).send(eq(ESCALATION), any());
    }

    @Test
    @DisplayName("an incident that cannot be read leaves the escalation to a later round, with the fixed reason")
    void incidentUnreadable() {
        AlertRule rule = escalating();
        Alert alert = open(rule, NOW.minus(Duration.ofMinutes(11)), UUID.randomUUID(), 0, null, Map.of("OPENED_0", new Notice(1, NOW, NOW, null)));
        when(incidents.status(org, alert.getIncidentId())).thenReturn(Mono.error(new UpstreamUnavailableException("The incident service could not be reached.")));

        delivers(0, List.of(alert), List.of(), byId(rule), List.of());

        verify(alerts).recordNotice(alert.getId(), org, "ESCALATED_0", null, "The incident service could not be reached.");
    }

    @Test
    @DisplayName("no escalation before its time, without an incident, without a target, once sent or tried three times, or when another instance holds the claim")
    void notDue() {
        AlertRule rule = escalating();
        Map<String, Notice> opened = Map.of("OPENED_0", new Notice(1, NOW, NOW, null));
        Alert early = open(rule, NOW.minus(Duration.ofMinutes(9)), UUID.randomUUID(), 0, null, opened);
        Alert noIncident = open(rule, NOW.minus(Duration.ofMinutes(30)), null, 0, null, Map.of("OPENED_0", new Notice(1, NOW, NOW, null)));
        Alert done = open(rule, NOW.minus(Duration.ofMinutes(30)), UUID.randomUUID(), 0, null,
                Map.of("OPENED_0", new Notice(1, NOW, NOW, null), "ESCALATED_0", new Notice(1, NOW, NOW, null)));
        Alert exhausted = open(rule, NOW.minus(Duration.ofMinutes(30)), UUID.randomUUID(), 0, null,
                Map.of("OPENED_0", new Notice(1, NOW, NOW, null), "ESCALATED_0", new Notice(3, NOW, null, "x")));
        AlertRule withoutTarget = rule(new Options(HOOK, null, null, 30));
        Alert noTarget = open(withoutTarget, NOW.minus(Duration.ofMinutes(30)), UUID.randomUUID(), 0, null, opened);

        delivers(0, List.of(early, noIncident, done, exhausted, noTarget), List.of(), byId(rule, withoutTarget), List.of());
        verifyNoInteractions(incidents);

        Alert contested = open(rule, NOW.minus(Duration.ofMinutes(30)), UUID.randomUUID(), 0, null, opened);
        when(alerts.claimNotice(eq(contested.getId()), eq(org), eq("ESCALATED_0"), any(), any(), anyInt())).thenReturn(Mono.just(false));
        delivers(0, List.of(contested), List.of(), byId(rule), List.of());
        verifyNoInteractions(incidents);
    }

    @Test
    @DisplayName("a reopened alert escalates in its own cycle, counted from the reopening")
    void escalationPerCycle() {
        AlertRule rule = escalating();
        Alert alert = open(rule, NOW.minus(Duration.ofHours(3)), UUID.randomUUID(), 1, NOW.minus(Duration.ofMinutes(10)),
                Map.of("OPENED_0", new Notice(1, NOW, NOW, null), "ESCALATED_0", new Notice(1, NOW, NOW, null), "REOPENED_1", new Notice(1, NOW, NOW, null)));
        when(incidents.status(org, alert.getIncidentId())).thenReturn(Mono.just("NEW"));

        delivers(1, List.of(alert), List.of(), byId(rule), List.of());

        verify(alerts).claimNotice(eq(alert.getId()), eq(org), eq("ESCALATED_1"), any(), any(), anyInt());
    }
}
