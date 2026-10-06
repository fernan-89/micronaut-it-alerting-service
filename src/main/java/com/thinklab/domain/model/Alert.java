package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidAlertStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root (BIAN Control Record) of the IT Alerting Service Domain: one outage of one health check. It is OPEN from the moment the
 * check is seen DOWN until it is seen UP, then RESOLVED (terminal); the next outage of the same check is a new alert (ADR-030). At most one
 * alert is OPEN per check, which a partial unique index guarantees whatever the number of instances, and it is saved BEFORE the incident is
 * opened: whoever wins the insert opens the incident, and an alert that has no incident yet (the incident service was down) is retried
 * every round. What is kept of the check is its id, its name, its asset and the fixed-vocabulary error: never anything the target said.
 */
public class Alert {

    public static final int MAX_PROBLEM_LENGTH = 100;

    private final UUID id;
    private final UUID organisationId;
    private final UUID ruleId;
    private final UUID checkId;
    private final String checkName;
    private final UUID assetId;
    private AlertStatus status;
    private final Instant openedAt;
    private Instant resolvedAt;
    private UUID incidentId;
    private String lastError;
    private String problem;
    private Instant updatedAt;
    private final List<AlertAuditEntry> auditTrail;

    private Alert(UUID id, UUID organisationId, UUID ruleId, UUID checkId, String checkName, UUID assetId, String error, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.ruleId = ruleId;
        this.checkId = checkId;
        this.checkName = checkName;
        this.assetId = assetId;
        this.lastError = error;
        this.status = AlertStatus.OPEN;
        this.openedAt = Instant.now();
        this.updatedAt = this.openedAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new AlertAuditEntry(this.openedAt, "OPENED", executor, null, AlertStatus.OPEN, "Check " + checkName + " is down" + (error != null ? ": " + error : "") + "."));
    }

    private Alert(UUID id, UUID organisationId, UUID ruleId, UUID checkId, String checkName, UUID assetId, AlertStatus status, Instant openedAt, Instant resolvedAt,
                  UUID incidentId, String lastError, String problem, Instant updatedAt, List<AlertAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.ruleId = ruleId;
        this.checkId = checkId;
        this.checkName = checkName;
        this.assetId = assetId;
        this.status = status != null ? status : AlertStatus.OPEN;
        this.openedAt = openedAt != null ? openedAt : Instant.now();
        this.resolvedAt = resolvedAt;
        this.incidentId = incidentId;
        this.lastError = lastError;
        this.problem = problem;
        this.updatedAt = updatedAt != null ? updatedAt : this.openedAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static Alert createNew(UUID id, UUID organisationId, UUID ruleId, UUID checkId, String checkName, UUID assetId, String error, String executor) {
        if (id == null || organisationId == null || ruleId == null || checkId == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Rule ID and Check ID are mandatory for Alert creation.");
        }
        if (checkName == null || checkName.isBlank()) {
            throw new IllegalArgumentException("The name of the check is mandatory for an Alert.");
        }
        requireExecutor(executor);
        return new Alert(id, organisationId, ruleId, checkId, checkName, assetId, error, executor);
    }

    public static Alert reconstitute(UUID id, UUID organisationId, UUID ruleId, UUID checkId, String checkName, UUID assetId, AlertStatus status, Instant openedAt,
                                     Instant resolvedAt, UUID incidentId, String lastError, String problem, Instant updatedAt, List<AlertAuditEntry> auditTrail) {
        if (id == null || organisationId == null || ruleId == null || checkId == null || checkName == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Rule ID, Check ID and Check name are mandatory to reconstitute an Alert.");
        }
        return new Alert(id, organisationId, ruleId, checkId, checkName, assetId, status, openedAt, resolvedAt, incidentId, lastError, problem, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** The incident was opened: the alert remembers it, and forgets an earlier failure to open one. */
    public AlertAuditEntry linkIncident(UUID openedIncidentId, String executor) {
        requireStatus(AlertStatus.OPEN);
        if (openedIncidentId == null) {
            throw new IllegalArgumentException("The incident id is mandatory to link an incident.");
        }
        this.incidentId = openedIncidentId;
        this.problem = null;
        return record("INCIDENT_OPENED", executor, "Incident " + openedIncidentId + " opened.");
    }

    /** The incident could not be opened: the alert stays OPEN, with a short reason, and the next round tries again. */
    public AlertAuditEntry recordIncidentFailure(String reason, String executor) {
        requireStatus(AlertStatus.OPEN);
        String bounded = reason == null || reason.isBlank() ? "incident not opened" : reason.replaceAll("[\\r\\n\\t]+", " ").trim();
        this.problem = bounded.length() > MAX_PROBLEM_LENGTH ? bounded.substring(0, MAX_PROBLEM_LENGTH) : bounded;
        return record("INCIDENT_FAILED", executor, "The incident could not be opened: " + this.problem + ".");
    }

    /** The check answers again: OPEN -&gt; RESOLVED (terminal). */
    public AlertAuditEntry resolve(String executor) {
        requireStatus(AlertStatus.OPEN);
        requireExecutor(executor);
        this.status = AlertStatus.RESOLVED;
        this.resolvedAt = Instant.now();
        this.updatedAt = this.resolvedAt;
        AlertAuditEntry entry = new AlertAuditEntry(this.updatedAt, "RESOLVED", executor, AlertStatus.OPEN, AlertStatus.RESOLVED, "Check " + checkName + " is answering again.");
        this.auditTrail.add(entry);
        return entry;
    }

    // --- Internal helpers ---

    private AlertAuditEntry record(String action, String executor, String detail) {
        requireExecutor(executor);
        this.updatedAt = Instant.now();
        AlertAuditEntry entry = new AlertAuditEntry(this.updatedAt, action, executor, this.status, this.status, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(AlertStatus expected) {
        if (this.status != expected) {
            throw new InvalidAlertStatusException(String.format("Illegal transition: Alert is [%s], expected [%s].", this.status, expected));
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable Alert mutations.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public UUID getRuleId() { return ruleId; }
    public UUID getCheckId() { return checkId; }
    public String getCheckName() { return checkName; }
    public UUID getAssetId() { return assetId; }
    public AlertStatus getStatus() { return status; }
    public Instant getOpenedAt() { return openedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public UUID getIncidentId() { return incidentId; }
    public String getLastError() { return lastError; }
    public String getProblem() { return problem; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<AlertAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum AlertStatus { OPEN, RESOLVED }

    public record AlertAuditEntry(Instant occurredAt, String action, String executor, AlertStatus fromStatus, AlertStatus toStatus, String detail) {}
}
