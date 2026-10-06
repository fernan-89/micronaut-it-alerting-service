package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidAlertRuleStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Secondary aggregate of the IT Alerting Service Domain: what to do when a health check goes DOWN. A rule names one check, or every check of
 * the tenant ({@code checkId} left empty), and the incident to open: its impact and urgency (the incident service derives the priority) and
 * the person it is filed for, which that service requires of staff. A rule can be paused: it then opens nothing new, while the alerts it
 * already opened are still resolved when their check recovers (ADR-030).
 */
public class AlertRule {

    private final UUID id;
    private final UUID organisationId;
    private String name;
    private UUID checkId;
    private Severity impact;
    private Severity urgency;
    private UUID requesterId;
    private RuleStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<RuleAuditEntry> auditTrail;

    private AlertRule(UUID id, UUID organisationId, String name, UUID checkId, Severity impact, Severity urgency, UUID requesterId, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.checkId = checkId;
        this.impact = impact;
        this.urgency = urgency;
        this.requesterId = requesterId;
        this.status = RuleStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new RuleAuditEntry(this.createdAt, "INITIATED", executor, null, RuleStatus.ACTIVE, checkId == null ? "Rule for every check of the tenant." : "Rule for one check."));
    }

    private AlertRule(UUID id, UUID organisationId, String name, UUID checkId, Severity impact, Severity urgency, UUID requesterId, RuleStatus status,
                      Instant createdAt, Instant updatedAt, List<RuleAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.checkId = checkId;
        this.impact = impact;
        this.urgency = urgency;
        this.requesterId = requesterId;
        this.status = status != null ? status : RuleStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static AlertRule createNew(UUID id, UUID organisationId, String name, UUID checkId, Severity impact, Severity urgency, UUID requesterId, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for AlertRule creation.");
        }
        validate(name, impact, urgency, requesterId);
        requireExecutor(executor);
        return new AlertRule(id, organisationId, name, checkId, impact, urgency, requesterId, executor);
    }

    public static AlertRule reconstitute(UUID id, UUID organisationId, String name, UUID checkId, Severity impact, Severity urgency, UUID requesterId, RuleStatus status,
                                         Instant createdAt, Instant updatedAt, List<RuleAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null || impact == null || urgency == null || requesterId == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Name, Impact, Urgency and Requester are mandatory to reconstitute an AlertRule.");
        }
        return new AlertRule(id, organisationId, name, checkId, impact, urgency, requesterId, status, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code rule/update}. Everything, in any status. */
    public RuleAuditEntry update(String newName, UUID newCheckId, Severity newImpact, Severity newUrgency, UUID newRequesterId, String executor) {
        validate(newName, newImpact, newUrgency, newRequesterId);
        requireExecutor(executor);
        this.name = newName;
        this.checkId = newCheckId;
        this.impact = newImpact;
        this.urgency = newUrgency;
        this.requesterId = newRequesterId;
        return record("UPDATED", executor, this.status, this.status, "Rule updated.");
    }

    /** Behavior Qualifier: {@code rule/control/pause}. ACTIVE -&gt; PAUSED: opens no new alert. */
    public RuleAuditEntry pause(String executor) {
        requireStatus(RuleStatus.ACTIVE);
        requireExecutor(executor);
        this.status = RuleStatus.PAUSED;
        return record("PAUSED", executor, RuleStatus.ACTIVE, RuleStatus.PAUSED, "Paused: no new alert is opened.");
    }

    /** Behavior Qualifier: {@code rule/control/resume}. PAUSED -&gt; ACTIVE. */
    public RuleAuditEntry resume(String executor) {
        requireStatus(RuleStatus.PAUSED);
        requireExecutor(executor);
        this.status = RuleStatus.ACTIVE;
        return record("RESUMED", executor, RuleStatus.PAUSED, RuleStatus.ACTIVE, "Resumed.");
    }

    /** Does this rule cover that check? A rule with no check covers every check of the tenant. */
    public boolean covers(UUID candidateCheckId) {
        return this.checkId == null || this.checkId.equals(candidateCheckId);
    }

    // --- Internal helpers ---

    private RuleAuditEntry record(String action, String executor, RuleStatus from, RuleStatus to, String detail) {
        this.updatedAt = Instant.now();
        RuleAuditEntry entry = new RuleAuditEntry(this.updatedAt, action, executor, from, to, detail);
        this.auditTrail.add(entry);
        return entry;
    }

    private void requireStatus(RuleStatus expected) {
        if (this.status != expected) {
            throw new InvalidAlertRuleStatusException(String.format("Illegal transition: AlertRule is [%s], expected [%s].", this.status, expected));
        }
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable AlertRule mutations.");
        }
    }

    private static void validate(String name, Severity impact, Severity urgency, UUID requesterId) {
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new IllegalArgumentException("Name is mandatory for an AlertRule (up to 80 characters).");
        }
        if (impact == null || urgency == null) {
            throw new IllegalArgumentException("The impact and the urgency of the incident are mandatory.");
        }
        if (requesterId == null) {
            throw new IllegalArgumentException("The person the incident is filed for is mandatory.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public UUID getCheckId() { return checkId; }
    public Severity getImpact() { return impact; }
    public Severity getUrgency() { return urgency; }
    public UUID getRequesterId() { return requesterId; }
    public RuleStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<RuleAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    /** Same three levels the incident service uses for impact and for urgency. */
    public enum Severity { LOW, MEDIUM, HIGH }

    public enum RuleStatus { ACTIVE, PAUSED }

    public record RuleAuditEntry(Instant occurredAt, String action, String executor, RuleStatus fromStatus, RuleStatus toStatus, String detail) {}
}
