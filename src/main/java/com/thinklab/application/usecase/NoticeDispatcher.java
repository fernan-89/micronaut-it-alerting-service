package com.thinklab.application.usecase;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.Notice;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.port.IncidentsPort;
import com.thinklab.domain.port.NoticePort;
import com.thinklab.domain.repository.AlertRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells people (ADR-036): the notices an evaluation owes, to the webhooks the rules name.
 * <ul>
 *   <li>{@code OPENED} / {@code REOPENED} for an OPEN alert, as soon as its incident is linked (or a minute after it began, so a down incident
 *       service never keeps people in the dark), unless a maintenance window silences the check;</li>
 *   <li>{@code RESOLVED} for an alert resolved in the last hour;</li>
 *   <li>{@code ESCALATED} when the incident is still NEW (nobody acknowledged it) {@code escalateAfterMinutes} after the outage began, to the
 *       escalation target, once per cycle of the outage.</li>
 * </ul>
 * A notice is sent by the one caller that wins an atomic claim (never twice at once, with any number of instances), is tried at most
 * {@value #MAX_ATTEMPTS} times, at least {@code RETRY_GAP} apart, and its failure is kept on the alert as a fixed reason: it never fails the evaluation.
 */
@Singleton
public class NoticeDispatcher {

    static final int MAX_ATTEMPTS = 3;
    static final Duration RETRY_GAP = Duration.ofSeconds(30);
    static final Duration RESOLVED_NOTICE_WINDOW = Duration.ofHours(1);
    static final Duration INCIDENT_GRACE = Duration.ofMinutes(1);
    static final String NOT_NEEDED = "not needed: the incident was acknowledged";
    private static final Set<String> STILL_UNACKNOWLEDGED = Set.of("NEW");

    private static final Logger log = LoggerFactory.getLogger(NoticeDispatcher.class);

    private final AlertRepository alerts;
    private final IncidentsPort incidents;
    private final NoticePort notices;
    private final Clock clock;

    public NoticeDispatcher(AlertRepository alerts, IncidentsPort incidents, NoticePort notices, Clock clock) {
        this.alerts = alerts;
        this.incidents = incidents;
        this.notices = notices;
        this.clock = clock;
    }

    /** Sends what is owed for these alerts and answers how many notices got through. */
    public Mono<Integer> deliver(List<Alert> open, List<Alert> resolved, Map<UUID, AlertRule> rulesById, List<MaintenanceWindow> windows) {
        Instant now = Instant.now(clock);
        List<Mono<Boolean>> owed = new ArrayList<>();
        for (Alert alert : open) {
            AlertRule rule = rulesById.get(alert.getRuleId());
            if (rule == null || silenced(windows, alert, now)) {
                continue;
            }
            String event = alert.getReopenCount() == 0 ? "OPENED" : "REOPENED";
            if (alert.getIncidentId() != null || !now.isBefore(alert.activeSince().plus(INCIDENT_GRACE))) {
                owed.add(send(alert, event, rule.getOptions().notifyTarget(), rule, now));
            }
            if (isDueForEscalation(alert, rule, now)) {
                owed.add(escalate(alert, rule, now));
            }
        }
        for (Alert alert : resolved) {
            AlertRule rule = rulesById.get(alert.getRuleId());
            if (rule != null && alert.getResolvedAt() != null && now.isBefore(alert.getResolvedAt().plus(RESOLVED_NOTICE_WINDOW))) {
                owed.add(send(alert, "RESOLVED", rule.getOptions().notifyTarget(), rule, now));
            }
        }
        return Flux.concat(owed).filter(Boolean::booleanValue).count().map(Long::intValue);
    }

    private static boolean silenced(List<MaintenanceWindow> windows, Alert alert, Instant now) {
        return windows.stream().anyMatch(window -> window.silences(alert.getCheckId(), now));
    }

    private static boolean isDueForEscalation(Alert alert, AlertRule rule, Instant now) {
        AlertRule.Options options = rule.getOptions();
        return options.escalateTarget() != null && alert.getIncidentId() != null
                && !now.isBefore(alert.activeSince().plus(Duration.ofMinutes(options.escalateAfterMinutes())));
    }

    /** One notice, if it is owed: claim it, send it, record what came of it. Answers whether it got through. */
    private Mono<Boolean> send(Alert alert, String event, String target, AlertRule rule, Instant now) {
        Notice state = alert.notice(event);
        if (target == null || state.sentAt() != null || state.attempts() >= MAX_ATTEMPTS) {
            return Mono.just(false);
        }
        String key = alert.noticeKey(event);
        return alerts.claimNotice(alert.getId(), alert.getOrganisationId(), key, now, RETRY_GAP, MAX_ATTEMPTS)
                .flatMap(claimed -> claimed ? transmit(alert, event, target, key, rule, now) : Mono.just(false));
    }

    private Mono<Boolean> transmit(Alert alert, String event, String target, String key, AlertRule rule, Instant now) {
        NoticePort.Notice notice = new NoticePort.Notice(event, alert.getId(), alert.getIncidentId(), alert.getCheckName(), alert.getLastError(),
                rule.getImpact().name(), rule.getUrgency().name(), now);
        return notices.send(target, notice)
                .then(Mono.defer(() -> alerts.recordNotice(alert.getId(), alert.getOrganisationId(), key, now, null)).thenReturn(true))
                .onErrorResume(UpstreamUnavailableException.class, failure -> {
                    log.warn("[ALERTING] The {} notice of alert {} did not get through: {}", event, alert.getId(), failure.getMessage());
                    return alerts.recordNotice(alert.getId(), alert.getOrganisationId(), key, null, failure.getMessage()).thenReturn(false);
                });
    }

    /** The escalation is only worth sending while the incident is still NEW; once somebody acknowledged it, it is marked as not needed and not looked at again. */
    private Mono<Boolean> escalate(Alert alert, AlertRule rule, Instant now) {
        Notice state = alert.notice("ESCALATED");
        if (state.sentAt() != null || state.attempts() >= MAX_ATTEMPTS) {
            return Mono.just(false);
        }
        String key = alert.noticeKey("ESCALATED");
        return alerts.claimNotice(alert.getId(), alert.getOrganisationId(), key, now, RETRY_GAP, MAX_ATTEMPTS)
                .flatMap(claimed -> claimed ? incidents.status(alert.getOrganisationId(), alert.getIncidentId())
                        .flatMap(status -> STILL_UNACKNOWLEDGED.contains(status)
                                ? transmit(alert, "ESCALATED", rule.getOptions().escalateTarget(), key, rule, now)
                                : alerts.recordNotice(alert.getId(), alert.getOrganisationId(), key, now, NOT_NEEDED).thenReturn(false))
                        .onErrorResume(UpstreamUnavailableException.class, failure -> {
                            log.warn("[ALERTING] The incident of alert {} could not be read to decide an escalation: {}", alert.getId(), failure.getMessage());
                            return alerts.recordNotice(alert.getId(), alert.getOrganisationId(), key, null, failure.getMessage()).thenReturn(false);
                        }) : Mono.just(false));
    }
}
