package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.EvaluationResponse;
import com.thinklab.domain.exception.DuplicateAlertException;
import com.thinklab.domain.exception.InvalidAlertStatusException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.HealthChecksPort;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import com.thinklab.domain.port.IncidentsPort;
import com.thinklab.domain.port.IncidentsPort.IncidentDraft;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.domain.repository.AlertRuleRepository;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One look at a tenant (ADR-030), the whole of alerting: the scheduler runs it for every tenant, and staff can run it for theirs.
 * <ol>
 *   <li><b>Resolve:</b> an OPEN alert whose check is UP again is resolved with a write guarded on its status, and only the instance that won
 *       that write comments on the incident, so the comment is never doubled;</li>
 *   <li><b>Retry:</b> an OPEN alert that has no incident yet (the incident service was down) and whose check is still DOWN gets its incident;</li>
 *   <li><b>Open or reopen:</b> a DOWN, ACTIVE check with no OPEN alert, that no maintenance window silences and that an ACTIVE rule covers (the
 *       oldest rule wins). If its newest alert was resolved less than the rule's {@code reopenWithinMinutes} ago and its incident is still being
 *       worked, that alert is REOPENED and the incident gets a note (ADR-034); otherwise a new alert is saved first and then its incident;</li>
 *   <li><b>Tell people:</b> the notices owed, to the webhooks the rules name ({@link NoticeDispatcher}).</li>
 * </ol>
 * The incident is opened under the alert id as idempotency key, so a retry, an instance that lost the race or a crash between the incident and
 * its link can never open a second one. A check the monitor no longer lists, or one that is paused, leaves its alert as it is.
 */
@Singleton
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);
    private static final Set<String> FINISHED_INCIDENT = Set.of("RESOLVED", "CLOSED", "CANCELLED");

    private final AlertRuleRepository ruleRepository;
    private final AlertRepository alertRepository;
    private final MaintenanceWindowRepository windowRepository;
    private final HealthChecksPort health;
    private final IncidentsPort incidents;
    private final HashServicePort hashService;
    private final NoticeDispatcher dispatcher;
    private final Clock clock;

    public AlertEvaluator(AlertRuleRepository ruleRepository, AlertRepository alertRepository, MaintenanceWindowRepository windowRepository, HealthChecksPort health,
                          IncidentsPort incidents, HashServicePort hashService, NoticeDispatcher dispatcher, Clock clock) {
        this.ruleRepository = ruleRepository;
        this.alertRepository = alertRepository;
        this.windowRepository = windowRepository;
        this.health = health;
        this.incidents = incidents;
        this.hashService = hashService;
        this.dispatcher = dispatcher;
        this.clock = clock;
    }

    /** What one evaluation sees and does, mutable only inside it. */
    private static final class Round {
        final UUID organisationId;
        final String executor;
        final Instant now;
        final List<AlertRule> rules;
        final Map<UUID, AlertRule> ruleById;
        final Map<UUID, ObservedCheck> checks;
        final List<MaintenanceWindow> windows;
        final List<Alert> open;
        final List<Alert> resolved;
        int opened;
        int resolvedCount;
        int incidentsOpened;
        int reopened;

        Round(UUID organisationId, String executor, Instant now, List<AlertRule> rules, Map<UUID, ObservedCheck> checks, List<MaintenanceWindow> windows,
              List<Alert> open, List<Alert> resolvedRecently) {
            this.organisationId = organisationId;
            this.executor = executor;
            this.now = now;
            this.rules = rules;
            this.ruleById = rules.stream().collect(Collectors.toMap(AlertRule::getId, Function.identity()));
            this.checks = checks;
            this.windows = windows;
            this.open = new ArrayList<>(open);
            this.resolved = new ArrayList<>(resolvedRecently);
        }

        boolean silenced(UUID checkId) {
            return windows.stream().anyMatch(window -> window.silences(checkId, now));
        }

        boolean hasOpenAlert(UUID checkId) {
            return open.stream().anyMatch(alert -> alert.getCheckId().equals(checkId));
        }
    }

    public Mono<EvaluationResponse> evaluate(UUID organisationId, String executor) {
        Instant now = Instant.now(clock);
        return Mono.zip(
                        ruleRepository.findAll(organisationId).collectList(),
                        health.list(organisationId).collectMap(ObservedCheck::id, Function.identity()),
                        alertRepository.findOpen(organisationId).collectList(),
                        windowRepository.findCurrent(organisationId, now).collectList(),
                        alertRepository.findResolvedSince(organisationId, now.minus(Duration.ofMinutes(AlertRule.Options.MAX_MINUTES))).collectList())
                .flatMap(seen -> {
                    Round round = new Round(organisationId, executor, now, seen.getT1(), seen.getT2(), seen.getT4(), seen.getT3(), seen.getT5());
                    List<Alert> toResolve = List.copyOf(round.open);
                    return Flux.fromIterable(toResolve).concatMap(alert -> resolveIfRecovered(round, alert))
                            .thenMany(Flux.fromIterable(List.copyOf(round.open)).concatMap(alert -> retryIncident(round, alert)))
                            .thenMany(Flux.fromIterable(round.checks.values()).concatMap(check -> openOrReopen(round, check)))
                            .then(Mono.defer(() -> dispatcher.deliver(round.open, round.resolved, round.ruleById, round.windows)))
                            .map(notified -> new EvaluationResponse(round.opened, round.resolvedCount, round.incidentsOpened, round.reopened, notified));
                });
    }

    private Mono<Void> resolveIfRecovered(Round round, Alert alert) {
        ObservedCheck check = round.checks.get(alert.getCheckId());
        if (check == null || !check.isUp()) {
            return Mono.empty();
        }
        var entry = alert.resolve(round.executor);
        return alertRepository.save(alert, AlertStatus.OPEN, entry)
                .then(Mono.defer(() -> {
                    round.resolvedCount++;
                    round.open.remove(alert);
                    round.resolved.add(alert);
                    if (alert.getIncidentId() == null) {
                        return Mono.<Void>empty();
                    }
                    return comment(alert, "The health check " + alert.getCheckName() + " is answering again.", "was resolved but the incident could not be told");
                }))
                .onErrorResume(InvalidAlertStatusException.class, lost -> Mono.empty());
    }

    private Mono<Void> comment(Alert alert, String text, String problem) {
        return incidents.comment(alert.getOrganisationId(), alert.getIncidentId(), text)
                .onErrorResume(UpstreamUnavailableException.class, failure -> {
                    log.warn("[ALERTING] The alert {} {}: {}", alert.getId(), problem, failure.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> retryIncident(Round round, Alert alert) {
        ObservedCheck check = round.checks.get(alert.getCheckId());
        AlertRule rule = round.ruleById.get(alert.getRuleId());
        if (alert.getStatus() != AlertStatus.OPEN || alert.getIncidentId() != null || rule == null || check == null || !check.isDown()) {
            return Mono.empty();
        }
        return openIncident(round, alert, rule, check);
    }

    private Mono<Void> openOrReopen(Round round, ObservedCheck check) {
        if (!check.isDown() || !"ACTIVE".equals(check.status()) || round.hasOpenAlert(check.id()) || round.silenced(check.id())) {
            return Mono.empty();
        }
        AlertRule rule = round.rules.stream().filter(r -> r.getStatus() == RuleStatus.ACTIVE && r.covers(check.id())).findFirst().orElse(null);
        if (rule == null) {
            return Mono.empty();
        }
        Instant reopenAfter = round.now.minus(Duration.ofMinutes(rule.getOptions().reopenWithinMinutes()));
        Alert candidate = rule.getOptions().reopenWithinMinutes() == 0 ? null : round.resolved.stream()
                .filter(alert -> alert.getCheckId().equals(check.id()) && alert.getResolvedAt() != null && !alert.getResolvedAt().isBefore(reopenAfter))
                .max(Comparator.comparing(Alert::getResolvedAt)).orElse(null);
        if (candidate == null) {
            return openNew(round, check, rule);
        }
        if (candidate.getIncidentId() == null) {
            return reopen(round, candidate, rule, check);
        }
        return incidents.status(round.organisationId, candidate.getIncidentId())
                .flatMap(status -> FINISHED_INCIDENT.contains(status) ? openNew(round, check, rule) : reopen(round, candidate, rule, check))
                .onErrorResume(UpstreamUnavailableException.class, failure -> {
                    log.warn("[ALERTING] The incident of alert {} could not be read to decide a reopening: {}", candidate.getId(), failure.getMessage());
                    return Mono.empty();
                });
    }

    private Mono<Void> openNew(Round round, ObservedCheck check, AlertRule rule) {
        return hashService.generateSovereignId("alert-creation")
                .map(id -> Alert.createNew(id, round.organisationId, rule.getId(), check.id(), check.name(), check.assetId(), check.error(), round.executor))
                .flatMap(alertRepository::create)
                .flatMap(alert -> {
                    round.opened++;
                    round.open.add(alert);
                    return openIncident(round, alert, rule, check);
                })
                .onErrorResume(DuplicateAlertException.class, lost -> Mono.empty());
    }

    private Mono<Void> reopen(Round round, Alert alert, AlertRule rule, ObservedCheck check) {
        var entry = alert.reopen(round.executor);
        return alertRepository.save(alert, AlertStatus.RESOLVED, entry)
                .then(Mono.defer(() -> {
                    round.reopened++;
                    round.resolved.remove(alert);
                    round.open.add(alert);
                    if (alert.getIncidentId() == null) {
                        return openIncident(round, alert, rule, check);
                    }
                    return comment(alert, "The health check " + alert.getCheckName() + " is down again" + (check.error() != null ? " (" + check.error() + ")" : "")
                            + "; this incident is being worked again.", "was reopened but the incident could not be told");
                }))
                .onErrorResume(InvalidAlertStatusException.class, lost -> Mono.empty())
                .onErrorResume(DuplicateAlertException.class, lost -> Mono.empty());
    }

    private Mono<Void> openIncident(Round round, Alert alert, AlertRule rule, ObservedCheck check) {
        IncidentDraft draft = new IncidentDraft("Health check down: " + check.name(),
                "The health check " + check.name() + " is down" + (check.error() != null ? " (" + check.error() + ")" : "") + ". Opened by alerting rule " + rule.getName() + ".",
                rule.getImpact(), rule.getUrgency(), rule.getRequesterId(), check.assetId(), alert.getId().toString());
        return incidents.open(alert.getOrganisationId(), draft)
                .flatMap(incidentId -> {
                    var entry = alert.linkIncident(incidentId, round.executor);
                    return alertRepository.saveIncidentLink(alert, entry).doOnNext(linked -> {
                        if (linked) {
                            round.incidentsOpened++;
                        }
                    }).then();
                })
                .onErrorResume(UpstreamUnavailableException.class, failure -> {
                    log.warn("[ALERTING] The incident of alert {} could not be opened: {}", alert.getId(), failure.getMessage());
                    var entry = alert.recordIncidentFailure(failure.getMessage(), round.executor);
                    return alertRepository.save(alert, AlertStatus.OPEN, entry);
                });
    }
}
