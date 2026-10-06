package com.thinklab.infrastructure.adapter.out.persistence;

import com.mongodb.reactivestreams.client.MongoClient;
import com.thinklab.domain.exception.DuplicateAlertException;
import com.thinklab.domain.exception.DuplicateAlertRuleException;
import com.thinklab.domain.exception.InvalidAlertRuleStatusException;
import com.thinklab.domain.exception.InvalidAlertStatusException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.domain.repository.AlertRepository.Filter;
import com.thinklab.domain.repository.AlertRuleRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.test.support.TestPropertyProvider;
import jakarta.inject.Inject;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The alerting persistence against a real MongoDB: the unique rule name, the partial unique index that allows ONE open alert per check (and
 * a new one once it is resolved), the status-guarded saves (the second writer from the same state loses), the tenants the scheduler looks at,
 * and the indexes created at startup. The scheduler is switched off: these tests drive the repositories themselves.
 */
@MicronautTest(packages = "com.thinklab", transactional = false)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AlertingPersistenceIT implements TestPropertyProvider {

    private static final String DATABASE = "alerting_it";
    private static final String EXECUTOR = "system:alerting";

    @Override
    public Map<String, String> getProperties() {
        return Map.of("mongodb.uri", MongoContainer.uri(DATABASE), "thinklab.alerting.scheduler-enabled", "false");
    }

    @Inject AlertRuleRepository rules;
    @Inject AlertRepository alerts;
    @Inject MongoClient mongoClient;

    private AlertRule newRule(UUID organisation, String name, UUID checkId) {
        return AlertRule.createNew(UUID.randomUUID(), organisation, name, checkId, Severity.HIGH, Severity.MEDIUM, UUID.randomUUID(), "op-1");
    }

    private Alert newAlert(UUID organisation, UUID checkId) {
        return Alert.createNew(UUID.randomUUID(), organisation, UUID.randomUUID(), checkId, "Intranet", UUID.randomUUID(), "timeout", EXECUTOR);
    }

    // ------------------------------------------------------------------ Rules

    @Test
    @DisplayName("a rule is read back whole, only for its own organisation, oldest first")
    void ruleRoundTrip() {
        UUID organisation = UUID.randomUUID();
        UUID check = UUID.randomUUID();
        AlertRule first = rules.create(newRule(organisation, "First", check)).block();
        rules.create(newRule(organisation, "Second", null)).block();

        AlertRule found = rules.findById(first.getId(), organisation).block();

        assertEquals(check, found.getCheckId());
        assertEquals(Severity.HIGH, found.getImpact());
        assertEquals(RuleStatus.ACTIVE, found.getStatus());
        assertEquals(1, found.getAuditTrail().size());
        assertNull(rules.findById(first.getId(), UUID.randomUUID()).block());
        assertEquals(List.of("First", "Second"), rules.findAll(organisation).map(AlertRule::getName).collectList().block());
        assertTrue(rules.findAll(UUID.randomUUID()).collectList().block().isEmpty());
    }

    @Test
    @DisplayName("two rules with the same name in one organisation: the second loses; in another organisation it is fine; a rename onto a taken name loses too")
    void ruleNameIsUnique() {
        UUID organisation = UUID.randomUUID();
        rules.create(newRule(organisation, "Same", null)).block();

        assertThrows(DuplicateAlertRuleException.class, () -> rules.create(newRule(organisation, "Same", null)).block());
        rules.create(newRule(UUID.randomUUID(), "Same", null)).block();
        AlertRule other = rules.create(newRule(organisation, "Other", null)).block();
        AlertRule loaded = rules.findById(other.getId(), organisation).block();
        var entry = loaded.update("Same", null, Severity.LOW, Severity.LOW, loaded.getRequesterId(), "op-1");

        assertThrows(DuplicateAlertRuleException.class, () -> rules.save(loaded, RuleStatus.ACTIVE, entry).block());
    }

    @Test
    @DisplayName("a rule save is guarded by the status loaded: the second writer from the same state loses")
    void ruleStatusGuard() {
        UUID organisation = UUID.randomUUID();
        AlertRule created = rules.create(newRule(organisation, "Guarded", null)).block();
        AlertRule first = rules.findById(created.getId(), organisation).block();
        AlertRule second = rules.findById(created.getId(), organisation).block();

        var pause = first.pause("op-1");
        rules.save(first, RuleStatus.ACTIVE, pause).block();
        var secondPause = second.pause("op-2");

        assertThrows(InvalidAlertRuleStatusException.class, () -> rules.save(second, RuleStatus.ACTIVE, secondPause).block());
        AlertRule stored = rules.findById(created.getId(), organisation).block();
        assertEquals(RuleStatus.PAUSED, stored.getStatus());
        assertEquals(2, stored.getAuditTrail().size());
    }

    // ------------------------------------------------------------------ Alerts

    @Test
    @DisplayName("an alert is read back whole, only for its own organisation; the list filters by status and by check, newest first")
    void alertRoundTripAndFilters() {
        UUID organisation = UUID.randomUUID();
        UUID check = UUID.randomUUID();
        Alert older = alerts.create(newAlert(organisation, check)).block();
        var resolved = older.resolve(EXECUTOR);
        alerts.save(older, AlertStatus.OPEN, resolved).block();
        Alert newer = alerts.create(newAlert(organisation, check)).block();
        alerts.create(newAlert(organisation, UUID.randomUUID())).block();

        Alert found = alerts.findById(older.getId(), organisation).block();

        assertEquals(AlertStatus.RESOLVED, found.getStatus());
        assertEquals(2, found.getAuditTrail().size());
        assertNull(alerts.findById(older.getId(), UUID.randomUUID()).block());
        assertEquals(List.of(newer.getId(), older.getId()), alerts.findAll(organisation, new Filter(null, check)).map(Alert::getId).collectList().block());
        assertEquals(List.of(older.getId()), alerts.findAll(organisation, new Filter(AlertStatus.RESOLVED, null)).map(Alert::getId).collectList().block());
        assertEquals(2, alerts.findAll(organisation, new Filter(AlertStatus.OPEN, null)).collectList().block().size());
        assertEquals(2, alerts.findOpen(organisation).collectList().block().size());
        assertTrue(alerts.findAll(UUID.randomUUID(), new Filter(null, null)).collectList().block().isEmpty());
    }

    @Test
    @DisplayName("ONE open alert per check: a second one loses, even from two writers; once resolved, a new outage may open a new alert")
    void oneOpenAlertPerCheck() {
        UUID organisation = UUID.randomUUID();
        UUID check = UUID.randomUUID();
        Alert first = alerts.create(newAlert(organisation, check)).block();

        assertThrows(DuplicateAlertException.class, () -> alerts.create(newAlert(organisation, check)).block());
        assertEquals(1, alerts.findOpen(organisation).collectList().block().size());
        alerts.create(newAlert(UUID.randomUUID(), check)).block();
        alerts.save(first, AlertStatus.OPEN, first.resolve(EXECUTOR)).block();

        Alert next = alerts.create(newAlert(organisation, check)).block();

        assertEquals(AlertStatus.OPEN, alerts.findById(next.getId(), organisation).block().getStatus());
        assertEquals(2, alerts.findAll(organisation, new Filter(null, check)).collectList().block().size());
    }

    @Test
    @DisplayName("an alert save is guarded by the status loaded: only the winner of a resolve records it; the incident is kept")
    void alertStatusGuard() {
        UUID organisation = UUID.randomUUID();
        Alert created = alerts.create(newAlert(organisation, UUID.randomUUID())).block();
        Alert first = alerts.findById(created.getId(), organisation).block();
        Alert second = alerts.findById(created.getId(), organisation).block();
        UUID incident = UUID.randomUUID();

        alerts.save(first, AlertStatus.OPEN, first.linkIncident(incident, EXECUTOR)).block();
        var firstResolve = first.resolve(EXECUTOR);
        alerts.save(first, AlertStatus.OPEN, firstResolve).block();
        var lost = second.resolve(EXECUTOR);

        assertThrows(InvalidAlertStatusException.class, () -> alerts.save(second, AlertStatus.OPEN, lost).block());
        Alert stored = alerts.findById(created.getId(), organisation).block();
        assertEquals(AlertStatus.RESOLVED, stored.getStatus());
        assertEquals(incident, stored.getIncidentId());
        assertEquals(3, stored.getAuditTrail().size());
    }

    // ------------------------------------------------------------------ Tenants

    @Test
    @DisplayName("the scheduler looks at the tenants with an ACTIVE rule or an OPEN alert, and at no one else")
    void tenants() {
        UUID withRule = UUID.randomUUID();
        UUID pausedOnly = UUID.randomUUID();
        UUID withOpenAlert = UUID.randomUUID();
        UUID resolvedOnly = UUID.randomUUID();
        rules.create(newRule(withRule, "Active", null)).block();
        AlertRule paused = rules.create(newRule(pausedOnly, "Paused", null)).block();
        rules.save(paused, RuleStatus.ACTIVE, paused.pause("op-1")).block();
        alerts.create(newAlert(withOpenAlert, UUID.randomUUID())).block();
        Alert resolved = alerts.create(newAlert(resolvedOnly, UUID.randomUUID())).block();
        alerts.save(resolved, AlertStatus.OPEN, resolved.resolve(EXECUTOR)).block();

        List<UUID> active = rules.activeTenants().collectList().block();
        List<UUID> open = alerts.openTenants().collectList().block();

        assertTrue(active.contains(withRule) && !active.contains(pausedOnly));
        assertTrue(open.contains(withOpenAlert) && !open.contains(resolvedOnly));
    }

    // ------------------------------------------------------------------ Indexes

    @Test
    @DisplayName("startup created the indexes: the unique rule name, and the partial unique one on OPEN alerts")
    void indexesExist() {
        assertTrue(indexNames("alert_rules").containsAll(List.of("organisationId_1_name_1", "status_1")));
        assertTrue(indexNames("alerts").containsAll(List.of("organisationId_1_checkId_1_open", "organisationId_1_status_1_openedAt_-1", "status_1")));
    }

    private List<String> indexNames(String collection) {
        return Flux.from(mongoClient.getDatabase(DATABASE).getCollection(collection).listIndexes()).map(index -> ((Document) index).getString("name")).collectList().block();
    }
}
