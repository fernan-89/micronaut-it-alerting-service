package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.EvaluationResponse;
import com.thinklab.domain.exception.DuplicateAlertException;
import com.thinklab.domain.exception.InvalidAlertStatusException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.port.HealthChecksPort;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import com.thinklab.domain.port.IncidentsPort;
import com.thinklab.domain.port.IncidentsPort.IncidentDraft;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.domain.repository.AlertRuleRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * One look at a tenant (ADR-030), the whole of alerting: the scheduler runs it for every tenant, and staff can run it for theirs.
 * <ol>
 *   <li><b>Resolve:</b> an OPEN alert whose check is UP again is resolved with a write guarded on its status, and only the instance that won
 *       that write comments on the incident, so the comment is never doubled;</li>
 *   <li><b>Retry:</b> an OPEN alert that has no incident yet (the incident service was down) and whose check is still DOWN gets its incident;</li>
 *   <li><b>Open:</b> a DOWN, ACTIVE check with no OPEN alert and an ACTIVE rule that covers it (the oldest rule wins) gets an alert, saved first,
 *       and then its incident. A paused check is not alerted on: its health is whatever it was when it stopped being probed.</li>
 * </ol>
 * A check the monitor no longer lists, or one that is paused, leaves its alert as it is.
 */
@Singleton
public class AlertEvaluator {

    private static final Logger log = LoggerFactory.getLogger(AlertEvaluator.class);

    private final AlertRuleRepository ruleRepository;
    private final AlertRepository alertRepository;
    private final HealthChecksPort health;
    private final IncidentsPort incidents;
    private final HashServicePort hashService;

    public AlertEvaluator(AlertRuleRepository ruleRepository, AlertRepository alertRepository, HealthChecksPort health, IncidentsPort incidents, HashServicePort hashService) {
        this.ruleRepository = ruleRepository;
        this.alertRepository = alertRepository;
        this.health = health;
        this.incidents = incidents;
        this.hashService = hashService;
    }

    /** Counts of what happened, mutable only inside one evaluation. */
    private static final class Tally {
        int opened;
        int resolved;
        int incidentsOpened;
    }

    public Mono<EvaluationResponse> evaluate(UUID organisationId, String executor) {
        return Mono.zip(
                        ruleRepository.findAll(organisationId).collectList(),
                        health.list(organisationId).collectMap(ObservedCheck::id, Function.identity()),
                        alertRepository.findOpen(organisationId).collectList())
                .flatMap(seen -> {
                    Tally tally = new Tally();
                    List<AlertRule> rules = seen.getT1();
                    Map<UUID, ObservedCheck> checks = seen.getT2();
                    List<Alert> open = seen.getT3();
                    Map<UUID, AlertRule> ruleById = rules.stream().collect(Collectors.toMap(AlertRule::getId, Function.identity()));
                    Map<UUID, Alert> openByCheck = open.stream().collect(Collectors.toMap(Alert::getCheckId, Function.identity(), (a, b) -> a));
                    return Flux.fromIterable(open).concatMap(alert -> resolveIfRecovered(alert, checks.get(alert.getCheckId()), executor, tally))
                            .thenMany(Flux.fromIterable(open).concatMap(alert -> retryIncident(alert, ruleById.get(alert.getRuleId()), checks.get(alert.getCheckId()), executor, tally)))
                            .thenMany(Flux.fromIterable(checks.values()).concatMap(check -> openIfDown(organisationId, check, rules, openByCheck, executor, tally)))
                            .then(Mono.fromSupplier(() -> new EvaluationResponse(tally.opened, tally.resolved, tally.incidentsOpened)));
                });
    }

    private Mono<Void> resolveIfRecovered(Alert alert, ObservedCheck check, String executor, Tally tally) {
        if (check == null || !check.isUp()) {
            return Mono.empty();
        }
        var entry = alert.resolve(executor);
        return alertRepository.save(alert, AlertStatus.OPEN, entry)
                .then(Mono.defer(() -> {
                    tally.resolved++;
                    if (alert.getIncidentId() == null) {
                        return Mono.<Void>empty();
                    }
                    return incidents.comment(alert.getOrganisationId(), alert.getIncidentId(), "The health check " + alert.getCheckName() + " is answering again.")
                            .onErrorResume(UpstreamUnavailableException.class, failure -> {
                                log.warn("[ALERTING] The alert {} was resolved but the incident could not be told: {}", alert.getId(), failure.getMessage());
                                return Mono.empty();
                            });
                }))
                .onErrorResume(InvalidAlertStatusException.class, lost -> Mono.empty());
    }

    private Mono<Void> retryIncident(Alert alert, AlertRule rule, ObservedCheck check, String executor, Tally tally) {
        if (alert.getStatus() != AlertStatus.OPEN || alert.getIncidentId() != null || rule == null || check == null || !check.isDown()) {
            return Mono.empty();
        }
        return openIncident(alert, rule, check, executor, tally);
    }

    private Mono<Void> openIfDown(UUID organisationId, ObservedCheck check, List<AlertRule> rules, Map<UUID, Alert> openByCheck, String executor, Tally tally) {
        if (!check.isDown() || !"ACTIVE".equals(check.status()) || openByCheck.containsKey(check.id())) {
            return Mono.empty();
        }
        AlertRule rule = rules.stream().filter(r -> r.getStatus() == RuleStatus.ACTIVE && r.covers(check.id())).findFirst().orElse(null);
        if (rule == null) {
            return Mono.empty();
        }
        return hashService.generateSovereignId("alert-creation")
                .map(id -> Alert.createNew(id, organisationId, rule.getId(), check.id(), check.name(), check.assetId(), check.error(), executor))
                .flatMap(alertRepository::create)
                .flatMap(alert -> {
                    tally.opened++;
                    return openIncident(alert, rule, check, executor, tally);
                })
                .onErrorResume(DuplicateAlertException.class, lost -> Mono.empty());
    }

    private Mono<Void> openIncident(Alert alert, AlertRule rule, ObservedCheck check, String executor, Tally tally) {
        IncidentDraft draft = new IncidentDraft("Health check down: " + check.name(),
                "The health check " + check.name() + " is down" + (check.error() != null ? " (" + check.error() + ")" : "") + ". Opened by alerting rule " + rule.getName() + ".",
                rule.getImpact(), rule.getUrgency(), rule.getRequesterId(), check.assetId());
        return incidents.open(alert.getOrganisationId(), draft)
                .flatMap(incidentId -> {
                    var entry = alert.linkIncident(incidentId, executor);
                    tally.incidentsOpened++;
                    return alertRepository.save(alert, AlertStatus.OPEN, entry);
                })
                .onErrorResume(UpstreamUnavailableException.class, failure -> {
                    log.warn("[ALERTING] The incident of alert {} could not be opened: {}", alert.getId(), failure.getMessage());
                    var entry = alert.recordIncidentFailure(failure.getMessage(), executor);
                    return alertRepository.save(alert, AlertStatus.OPEN, entry);
                });
    }
}
