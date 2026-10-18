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
    private Source source;
    private UUID checkId;
    private Severity impact;
    private Severity urgency;
    private UUID requesterId;
    private Options options;
    private RuleStatus status;
    private final Instant createdAt;
    private Instant updatedAt;
    private final List<RuleAuditEntry> auditTrail;

    private AlertRule(UUID id, UUID organisationId, String name, Source source, UUID checkId, Severity impact, Severity urgency, UUID requesterId, Options options, String executor) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.source = source;
        this.checkId = checkId;
        this.impact = impact;
        this.urgency = urgency;
        this.requesterId = requesterId;
        this.options = options;
        this.status = RuleStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
        this.auditTrail = new ArrayList<>();
        this.auditTrail.add(new RuleAuditEntry(this.createdAt, "INITIATED", executor, null, RuleStatus.ACTIVE, checkId == null ? "Rule for every " + source.noun() + " of the tenant." : "Rule for one " + source.noun() + "."));
    }

    private AlertRule(UUID id, UUID organisationId, String name, Source source, UUID checkId, Severity impact, Severity urgency, UUID requesterId, Options options, RuleStatus status,
                      Instant createdAt, Instant updatedAt, List<RuleAuditEntry> auditTrail) {
        this.id = id;
        this.organisationId = organisationId;
        this.name = name;
        this.source = source != null ? source : Source.HEALTH_CHECK;
        this.checkId = checkId;
        this.impact = impact;
        this.urgency = urgency;
        this.requesterId = requesterId;
        this.options = options != null ? options : Options.NONE;
        this.status = status != null ? status : RuleStatus.ACTIVE;
        this.createdAt = createdAt != null ? createdAt : Instant.now();
        this.updatedAt = updatedAt != null ? updatedAt : this.createdAt;
        this.auditTrail = auditTrail != null ? new ArrayList<>(auditTrail) : new ArrayList<>();
    }

    /** A rule about health checks, which is what every rule was before the registries could be watched (ADR-037). */
    public static AlertRule createNew(UUID id, UUID organisationId, String name, UUID checkId, Severity impact, Severity urgency, UUID requesterId, Options options, String executor) {
        return createNew(id, organisationId, name, Source.HEALTH_CHECK, checkId, impact, urgency, requesterId, options, executor);
    }

    public static AlertRule createNew(UUID id, UUID organisationId, String name, Source source, UUID checkId, Severity impact, Severity urgency, UUID requesterId, Options options,
                                      String executor) {
        if (id == null || organisationId == null) {
            throw new IllegalArgumentException("ID and Organisation ID are mandatory for AlertRule creation.");
        }
        validate(name, source, impact, urgency, requesterId, options);
        requireExecutor(executor);
        return new AlertRule(id, organisationId, name, source, checkId, impact, urgency, requesterId, options, executor);
    }

    public static AlertRule reconstitute(UUID id, UUID organisationId, String name, UUID checkId, Severity impact, Severity urgency, UUID requesterId, Options options, RuleStatus status,
                                         Instant createdAt, Instant updatedAt, List<RuleAuditEntry> auditTrail) {
        return reconstitute(id, organisationId, name, Source.HEALTH_CHECK, checkId, impact, urgency, requesterId, options, status, createdAt, updatedAt, auditTrail);
    }

    public static AlertRule reconstitute(UUID id, UUID organisationId, String name, Source source, UUID checkId, Severity impact, Severity urgency, UUID requesterId, Options options,
                                         RuleStatus status, Instant createdAt, Instant updatedAt, List<RuleAuditEntry> auditTrail) {
        if (id == null || organisationId == null || name == null || impact == null || urgency == null || requesterId == null) {
            throw new IllegalArgumentException("ID, Organisation ID, Name, Impact, Urgency and Requester are mandatory to reconstitute an AlertRule.");
        }
        return new AlertRule(id, organisationId, name, source, checkId, impact, urgency, requesterId, options, status, createdAt, updatedAt, auditTrail);
    }

    // --- Domain Behaviors ---

    /** Behavior Qualifier: {@code rule/update}. Everything, in any status. */
    public RuleAuditEntry update(String newName, UUID newCheckId, Severity newImpact, Severity newUrgency, UUID newRequesterId, Options newOptions, String executor) {
        return update(newName, this.source, newCheckId, newImpact, newUrgency, newRequesterId, newOptions, executor);
    }

    public RuleAuditEntry update(String newName, Source newSource, UUID newCheckId, Severity newImpact, Severity newUrgency, UUID newRequesterId, Options newOptions, String executor) {
        validate(newName, newSource, newImpact, newUrgency, newRequesterId, newOptions);
        requireExecutor(executor);
        this.name = newName;
        this.source = newSource;
        this.checkId = newCheckId;
        this.impact = newImpact;
        this.urgency = newUrgency;
        this.requesterId = newRequesterId;
        this.options = newOptions;
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

    /** Does this rule cover that watched thing: from its source, and either named or, with no id, every one of that source? */
    public boolean covers(Source candidateSource, UUID candidateId) {
        return this.source == candidateSource && covers(candidateId);
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

    private static void validate(String name, Source source, Severity impact, Severity urgency, UUID requesterId, Options options) {
        if (name == null || name.isBlank() || name.length() > 80) {
            throw new IllegalArgumentException("Name is mandatory for an AlertRule (up to 80 characters).");
        }
        if (source == null) {
            throw new IllegalArgumentException("The source of the rule is mandatory.");
        }
        if (impact == null || urgency == null) {
            throw new IllegalArgumentException("The impact and the urgency of the incident are mandatory.");
        }
        if (requesterId == null) {
            throw new IllegalArgumentException("The person the incident is filed for is mandatory.");
        }
        if (options == null) {
            throw new IllegalArgumentException("The options of the rule are mandatory (use none for the defaults).");
        }
        options.validate();
    }

    // --- Getters ---

    public UUID getId() { return id; }
    public UUID getOrganisationId() { return organisationId; }
    public String getName() { return name; }
    public Source getSource() { return source; }
    public UUID getCheckId() { return checkId; }
    public Severity getImpact() { return impact; }
    public Severity getUrgency() { return urgency; }
    public UUID getRequesterId() { return requesterId; }
    public Options getOptions() { return options; }
    public RuleStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<RuleAuditEntry> getAuditTrail() { return Collections.unmodifiableList(auditTrail); }

    // --- Nested Value Objects ---

    /** Same three levels the incident service uses for impact and for urgency. */
    public enum Severity { LOW, MEDIUM, HIGH }

    public enum RuleStatus { ACTIVE, PAUSED }

    /**
     * What a rule watches (ADR-037, ADR-038): the health checks, or the promises the registries judge when read. For the registries {@code checkId}
     * is the id of one policy (for {@code VENDOR}, one agreement), and left out means every one of that registry.
     */
    public enum Source {
        HEALTH_CHECK("check"), BACKUP("backup policy"), PATCH("patch policy"), CAPACITY("capacity policy"), VENDOR("vendor agreement");

        private final String noun;

        Source(String noun) {
            this.noun = noun;
        }

        public String noun() {
            return noun;
        }
    }

    public record RuleAuditEntry(Instant occurredAt, String action, String executor, RuleStatus fromStatus, RuleStatus toStatus, String detail) {}

    /**
     * What a rule does besides opening an incident. The two targets are the NAMES of environment variables that hold a webhook address (never
     * the address, and only names that start with {@code THINKLAB_ALERT_HOOK_}): so no address or secret is stored, shown or audited.
     * {@code escalateAfterMinutes} is how long an incident may stay unacknowledged before the escalation target is told; {@code reopenWithinMinutes}
     * is how soon after a resolution the same check going down again reopens that alert instead of opening a new one (0 = never).
     */
    public record Options(String notifyTarget, String escalateTarget, Integer escalateAfterMinutes, int reopenWithinMinutes) {

        public static final Options NONE = new Options(null, null, null, 0);
        public static final int MAX_MINUTES = 1440;
        public static final int DEFAULT_REOPEN_MINUTES = 30;
        private static final String TARGET_PATTERN = "THINKLAB_ALERT_HOOK_[A-Z0-9_]{1,40}";

        /** The options a request asked for; a reopen time left out is the default. */
        public static Options of(String notifyTarget, String escalateTarget, Integer escalateAfterMinutes, Integer reopenWithinMinutes) {
            return new Options(notifyTarget, escalateTarget, escalateAfterMinutes, reopenWithinMinutes != null ? reopenWithinMinutes : DEFAULT_REOPEN_MINUTES);
        }

        void validate() {
            requireTarget(notifyTarget, "notify");
            requireTarget(escalateTarget, "escalation");
            if (escalateTarget == null && escalateAfterMinutes != null) {
                throw new IllegalArgumentException("An escalation time needs an escalation target.");
            }
            if (escalateTarget != null && (escalateAfterMinutes == null || escalateAfterMinutes < 1 || escalateAfterMinutes > MAX_MINUTES)) {
                throw new IllegalArgumentException("The escalation time must be between 1 and " + MAX_MINUTES + " minutes.");
            }
            if (reopenWithinMinutes < 0 || reopenWithinMinutes > MAX_MINUTES) {
                throw new IllegalArgumentException("The reopen time must be between 0 and " + MAX_MINUTES + " minutes.");
            }
        }

        private static void requireTarget(String target, String what) {
            if (target != null && !target.matches(TARGET_PATTERN)) {
                throw new IllegalArgumentException("The " + what + " target must name an environment variable that starts with THINKLAB_ALERT_HOOK_.");
            }
        }
    }
}
