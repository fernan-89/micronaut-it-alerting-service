package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidAlertStatusException;
import com.thinklab.domain.model.Alert.AlertAuditEntry;
import com.thinklab.domain.model.Alert.AlertStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertTest {

    private final UUID org = UUID.randomUUID();

    private Alert alert(String error) {
        return Alert.createNew(UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(), "Intranet", UUID.randomUUID(), error, "system:alerting");
    }

    private static void rejects(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    @Test
    @DisplayName("a new alert is OPEN, with no incident yet and an OPENED entry that says which check and why")
    void create() {
        Alert alert = alert("timeout");

        assertEquals(AlertStatus.OPEN, alert.getStatus());
        assertNull(alert.getIncidentId());
        assertNull(alert.getProblem());
        assertNull(alert.getResolvedAt());
        assertEquals("OPENED", alert.getAuditTrail().get(0).action());
        assertTrue(alert.getAuditTrail().get(0).detail().contains("Intranet") && alert.getAuditTrail().get(0).detail().contains("timeout"));
        assertEquals("timeout", alert.getLastError());
        assertEquals("Intranet", alert.getCheckName());
        assertNotNull(alert.getAssetId());
        assertEquals(alert.getOpenedAt(), alert.getUpdatedAt());
        assertEquals(org, alert.getOrganisationId());
        assertNotNull(alert.getRuleId());
        assertNotNull(alert.getCheckId());
        assertTrue(alert(null).getAuditTrail().get(0).detail().endsWith("is down."));
    }

    @Test
    @DisplayName("creation guards: ids, check name and executor")
    void createGuards() {
        UUID id = UUID.randomUUID();
        rejects(() -> Alert.createNew(null, org, id, id, "n", null, null, "op"));
        rejects(() -> Alert.createNew(id, null, id, id, "n", null, null, "op"));
        rejects(() -> Alert.createNew(id, org, null, id, "n", null, null, "op"));
        rejects(() -> Alert.createNew(id, org, id, null, "n", null, null, "op"));
        rejects(() -> Alert.createNew(id, org, id, id, null, null, null, "op"));
        rejects(() -> Alert.createNew(id, org, id, id, " ", null, null, "op"));
        rejects(() -> Alert.createNew(id, org, id, id, "n", null, null, null));
        rejects(() -> Alert.createNew(id, org, id, id, "n", null, null, " "));
    }

    @Test
    @DisplayName("an incident is linked, which clears an earlier failure to open one")
    void linkIncident() {
        Alert alert = alert("timeout");
        alert.recordIncidentFailure("The incident service did not accept the incident (HTTP 503).", "system:alerting");
        UUID incident = UUID.randomUUID();

        AlertAuditEntry entry = alert.linkIncident(incident, "system:alerting");

        assertEquals("INCIDENT_OPENED", entry.action());
        assertEquals(incident, alert.getIncidentId());
        assertNull(alert.getProblem());
        assertEquals(AlertStatus.OPEN, alert.getStatus());
        assertEquals(AlertStatus.OPEN, entry.fromStatus());
        rejects(() -> alert.linkIncident(null, "op"));
        rejects(() -> alert.linkIncident(incident, null));
    }

    @Test
    @DisplayName("a failure to open the incident keeps one short sanitised line, and a missing reason has a default")
    void incidentFailure() {
        Alert alert = alert("timeout");

        alert.recordIncidentFailure("line one\nline two\t" + "x".repeat(300), "system:alerting");
        assertEquals(Alert.MAX_PROBLEM_LENGTH, alert.getProblem().length());
        assertTrue(!alert.getProblem().contains("\n"));
        alert.recordIncidentFailure(null, "system:alerting");
        assertEquals("incident not opened", alert.getProblem());
        alert.recordIncidentFailure("  ", "system:alerting");
        assertEquals("incident not opened", alert.getProblem());
        alert.recordIncidentFailure("short", "system:alerting");
        assertEquals("short", alert.getProblem());
        assertEquals(AlertStatus.OPEN, alert.getStatus());
    }

    @Test
    @DisplayName("resolving is terminal: OPEN -> RESOLVED with its time and entry; nothing more can happen to it")
    void resolve() {
        Alert alert = alert("timeout");

        AlertAuditEntry entry = alert.resolve("system:alerting");

        assertEquals(AlertStatus.RESOLVED, alert.getStatus());
        assertNotNull(alert.getResolvedAt());
        assertEquals("RESOLVED", entry.action());
        assertEquals(AlertStatus.OPEN, entry.fromStatus());
        assertEquals(AlertStatus.RESOLVED, entry.toStatus());
        assertThrows(InvalidAlertStatusException.class, () -> alert.resolve("op"));
        assertThrows(InvalidAlertStatusException.class, () -> alert.linkIncident(UUID.randomUUID(), "op"));
        assertThrows(InvalidAlertStatusException.class, () -> alert.recordIncidentFailure("x", "op"));
        assertThrows(IllegalArgumentException.class, () -> alert("x").resolve(null));
    }

    @Test
    @DisplayName("reconstitute keeps what was stored, defaults what is missing, and refuses a missing identity")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-06T10:00:00Z");
        Alert full = Alert.reconstitute(id, org, id, id, "n", id, AlertStatus.RESOLVED, at, at, id, "timeout", "p", at, List.of(new AlertAuditEntry(at, "OPENED", "op", null, AlertStatus.OPEN, "d")));
        assertEquals(AlertStatus.RESOLVED, full.getStatus());
        assertEquals(1, full.getAuditTrail().size());

        Alert bare = Alert.reconstitute(id, org, id, id, "n", null, null, null, null, null, null, null, null, null);
        assertEquals(AlertStatus.OPEN, bare.getStatus());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getOpenedAt(), bare.getUpdatedAt());

        rejects(() -> Alert.reconstitute(null, org, id, id, "n", null, null, null, null, null, null, null, null, null));
        rejects(() -> Alert.reconstitute(id, null, id, id, "n", null, null, null, null, null, null, null, null, null));
        rejects(() -> Alert.reconstitute(id, org, null, id, "n", null, null, null, null, null, null, null, null, null));
        rejects(() -> Alert.reconstitute(id, org, id, null, "n", null, null, null, null, null, null, null, null, null));
        rejects(() -> Alert.reconstitute(id, org, id, id, null, null, null, null, null, null, null, null, null, null));
    }
}
