package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidAlertRuleStatusException;
import com.thinklab.domain.model.AlertRule.RuleAuditEntry;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import com.thinklab.domain.model.AlertRule.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertRuleTest {

    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();

    private AlertRule rule(UUID checkId) {
        return AlertRule.createNew(UUID.randomUUID(), org, "Production down", checkId, Severity.HIGH, Severity.MEDIUM, requester, AlertRule.Options.NONE, "op");
    }

    private static void rejects(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    @Test
    @DisplayName("a new rule is ACTIVE with an INITIATED entry that says what it covers")
    void create() {
        AlertRule all = rule(null);
        AlertRule one = rule(UUID.randomUUID());

        assertEquals(RuleStatus.ACTIVE, all.getStatus());
        assertEquals("INITIATED", all.getAuditTrail().get(0).action());
        assertNull(all.getAuditTrail().get(0).fromStatus());
        assertTrue(all.getAuditTrail().get(0).detail().contains("every check"));
        assertTrue(one.getAuditTrail().get(0).detail().contains("one check"));
        assertEquals(Severity.HIGH, all.getImpact());
        assertEquals(Severity.MEDIUM, all.getUrgency());
        assertEquals(requester, all.getRequesterId());
        assertEquals("Production down", all.getName());
        assertEquals(org, all.getOrganisationId());
        assertEquals(all.getCreatedAt(), all.getUpdatedAt());
    }

    @Test
    @DisplayName("creation guards: ids, name, impact, urgency, requester and executor")
    void createGuards() {
        UUID id = UUID.randomUUID();
        rejects(() -> AlertRule.createNew(null, org, "n", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, "op"));
        rejects(() -> AlertRule.createNew(id, null, "n", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, "op"));
        rejects(() -> AlertRule.createNew(id, org, null, null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, "op"));
        rejects(() -> AlertRule.createNew(id, org, " ", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, "op"));
        rejects(() -> AlertRule.createNew(id, org, "x".repeat(81), null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, "op"));
        rejects(() -> AlertRule.createNew(id, org, "n", null, null, Severity.LOW, requester, AlertRule.Options.NONE, "op"));
        rejects(() -> AlertRule.createNew(id, org, "n", null, Severity.LOW, null, requester, AlertRule.Options.NONE, "op"));
        rejects(() -> AlertRule.createNew(id, org, "n", null, Severity.LOW, Severity.LOW, null, AlertRule.Options.NONE, "op"));
        rejects(() -> AlertRule.createNew(id, org, "n", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, null));
        rejects(() -> AlertRule.createNew(id, org, "n", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, " "));
    }

    @Test
    @DisplayName("a rule with no check covers every check; one with a check covers only that one")
    void covers() {
        UUID check = UUID.randomUUID();

        assertTrue(rule(null).covers(check));
        assertTrue(rule(check).covers(check));
        assertFalse(rule(check).covers(UUID.randomUUID()));
    }

    @Test
    @DisplayName("update changes the whole definition, in any status, and records it")
    void update() {
        AlertRule rule = rule(null);
        UUID check = UUID.randomUUID();

        RuleAuditEntry entry = rule.update("Renamed", check, Severity.LOW, Severity.HIGH, requester, AlertRule.Options.NONE, "op-2");

        assertEquals("UPDATED", entry.action());
        assertEquals(RuleStatus.ACTIVE, entry.fromStatus());
        assertEquals("Renamed", rule.getName());
        assertEquals(check, rule.getCheckId());
        assertEquals(Severity.LOW, rule.getImpact());
        rule.pause("op");
        assertEquals(RuleStatus.PAUSED, rule.update("Again", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, "op").toStatus());
        rejects(() -> rule.update("", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, "op"));
        rejects(() -> rule.update("n", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, " "));
    }

    @Test
    @DisplayName("pause then resume follow the lifecycle, each with its entry; anything else is a 409-style refusal")
    void lifecycle() {
        AlertRule rule = rule(null);

        assertThrows(InvalidAlertRuleStatusException.class, () -> rule.resume("op"));
        RuleAuditEntry paused = rule.pause("op");
        assertEquals(RuleStatus.PAUSED, rule.getStatus());
        assertEquals(RuleStatus.ACTIVE, paused.fromStatus());
        assertThrows(InvalidAlertRuleStatusException.class, () -> rule.pause("op"));
        RuleAuditEntry resumed = rule.resume("op-2");
        assertEquals("RESUMED", resumed.action());
        assertEquals(RuleStatus.ACTIVE, rule.getStatus());
        assertEquals(3, rule.getAuditTrail().size());
        assertThrows(IllegalArgumentException.class, () -> rule.pause(null));
        rule.pause("op");
        assertThrows(IllegalArgumentException.class, () -> rule.resume(" "));
    }

    @Test
    @DisplayName("reconstitute keeps what was stored, defaults what is missing, and refuses a missing identity")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2026-10-06T10:00:00Z");
        AlertRule full = AlertRule.reconstitute(id, org, "n", UUID.randomUUID(), Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, RuleStatus.PAUSED, created, created,
                List.of(new RuleAuditEntry(created, "INITIATED", "op", null, RuleStatus.ACTIVE, "d")));
        assertEquals(RuleStatus.PAUSED, full.getStatus());
        assertEquals(1, full.getAuditTrail().size());

        AlertRule bare = AlertRule.reconstitute(id, org, "n", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, null, null, null, null);
        assertEquals(RuleStatus.ACTIVE, bare.getStatus());
        assertEquals(AlertRule.Options.NONE, AlertRule.reconstitute(id, org, "n", null, Severity.LOW, Severity.LOW, requester, null, null, null, null, null).getOptions());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());

        rejects(() -> AlertRule.reconstitute(null, org, "n", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, null, null, null, null));
        rejects(() -> AlertRule.reconstitute(id, null, "n", null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, null, null, null, null));
        rejects(() -> AlertRule.reconstitute(id, org, null, null, Severity.LOW, Severity.LOW, requester, AlertRule.Options.NONE, null, null, null, null));
        rejects(() -> AlertRule.reconstitute(id, org, "n", null, null, Severity.LOW, requester, AlertRule.Options.NONE, null, null, null, null));
        rejects(() -> AlertRule.reconstitute(id, org, "n", null, Severity.LOW, null, requester, AlertRule.Options.NONE, null, null, null, null));
        rejects(() -> AlertRule.reconstitute(id, org, "n", null, Severity.LOW, Severity.LOW, null, AlertRule.Options.NONE, null, null, null, null));
    }
}
