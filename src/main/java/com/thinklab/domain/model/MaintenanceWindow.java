package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidMaintenanceWindowStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Aggregate Root of a planned silence: from {@code startsAt} to {@code endsAt} no alert is opened or reopened and nobody is told (a
 * notice or an escalation) for the check it covers, or for every check when it covers none in particular (ADR-035). It never resolves an
 * alert: a check that recovers is still resolved. A window can be cancelled but not edited, so what happened is what the audit trail says.
 */
public class MaintenanceWindow {

    public static final Duration MAX_LENGTH = Duration.ofDays(30);

    private final UUID id;
    private final UUID organisationId;
    private final String name;
    private final UUID checkId;
    private final Instant startsAt;
    private final Instant endsAt;
    private WindowStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<WindowAuditEntry> auditTrail;

    private MaintenanceWindow(UUID id, UUID organisationId, String name, UUID checkId, Instant startsAt, Instant endsAt, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.checkId = checkId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = WindowStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new WindowAuditEntry(this.createdAt, "INITIATED", executor, null, WindowStatus.ACTIVE,
                (checkId == null ? "Window for every check" : "Window for one check") + ", from " + startsAt + " to " + endsAt + "."));
    }

    private MaintenanceWindow(UUID id, UUID organisationId, String name, UUID checkId, Instant startsAt, Instant endsAt, WindowStatus status,
                              Instant createdAt, Instant updatedAt, List<WindowAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.checkId = checkId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.status = status != null ? status : WindowStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    public static MaintenanceWindow createNew(UUID id, UUID organisationId, String name, UUID checkId, Instant startsAt, Instant endsAt, Instant now, String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for MaintenanceWindow creation.");
        }
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new IllegalArgumentException("Name is mandatory for a MaintenanceWindow (up to 80 characters).");
        }
        if (startsAt == null || endsAt == null || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("A maintenance window needs a start and an end after it.");
        }
        if (Duration.between(startsAt, endsAt).compareTo(MAX_LENGTH) > 0) {
            throw new IllegalArgumentException("A maintenance window cannot be longer than " + MAX_LENGTH.toDays() + " days.");
        }
        if (!endsAt.isAfter(now)) {
            throw new IllegalArgumentException("A maintenance window cannot end in the past.");
        }
        requireExecutor(executor);
        return new MaintenanceWindow(id, organisationId, name, checkId, startsAt, endsAt, executor);
    }

    public static MaintenanceWindow reconstitute(UUID id, UUID organisationId, String name, UUID checkId, Instant startsAt, Instant endsAt, WindowStatus status,
                                                 Instant createdAt, Instant updatedAt, List<WindowAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null || startsAt == null || endsAt == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Name, start and end are mandatory to reconstitute a MaintenanceWindow.");
        }
        return new MaintenanceWindow(id, organisationId, name, checkId, startsAt, endsAt, status, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code window/control/cancel}. ACTIVE -&gt; CANCELLED (terminal); the silence stops at once. */
    public WindowAuditEntry cancel(String executor) {
        if (this.status != WindowStatus.ACTIVE) {
            throw new InvalidMaintenanceWindowStatusException(String.format("Illegal transition: MaintenanceWindow is [%s], expected [%s].", this.status, WindowStatus.ACTIVE));
        }
        requireExecutor(executor);
        this.status = WindowStatus.CANCELLED;
        this.updatedAt = Instant.now();
        WindowAuditEntry entry = new WindowAuditEntry(this.updatedAt, "CANCELLED", executor, WindowStatus.ACTIVE, WindowStatus.CANCELLED, "Cancelled: the check is no longer silenced.");
        this.auditTrail.add(entry);
        return entry;
    }

    /** Is that check silenced at that moment? The start is included and the end is not. */
    public boolean silences(UUID candidateCheckId, Instant moment) {
        return status == WindowStatus.ACTIVE && !moment.isBefore(startsAt) && moment.isBefore(endsAt) && (checkId == null || checkId.equals(candidateCheckId));
    }

    private static void requireExecutor(String executor) {
        if (executor == null || executor.isBlank()) {
            throw new IllegalArgumentException("Executor is mandatory for auditable MaintenanceWindow mutations.");
        }
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public UUID getCheckId() { return checkId; }
    public Instant getStartsAt() { return startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public WindowStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<WindowAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    public enum WindowStatus { ACTIVE, CANCELLED }

    public record WindowAuditEntry(Instant occurredAt, String action, String executor, WindowStatus fromStatus, WindowStatus toStatus, String detail) {}
}
