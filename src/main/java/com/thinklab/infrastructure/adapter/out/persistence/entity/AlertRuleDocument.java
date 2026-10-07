package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleAuditEntry;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import com.thinklab.domain.model.AlertRule.Severity;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the AlertRule Aggregate for MongoDB. */
@Introspected
public class AlertRuleDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String name;
    private UUID checkId;
    private String impact;
    private String urgency;
    private UUID requesterId;
    private String notifyTarget;
    private String escalateTarget;
    private Integer escalateAfterMinutes;
    private int reopenWithinMinutes;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public UUID getCheckId() { return checkId; }
    public void setCheckId(UUID checkId) { this.checkId = checkId; }
    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }
    public String getUrgency() { return urgency; }
    public void setUrgency(String urgency) { this.urgency = urgency; }
    public UUID getRequesterId() { return requesterId; }
    public void setRequesterId(UUID requesterId) { this.requesterId = requesterId; }
    public String getNotifyTarget() { return notifyTarget; }
    public void setNotifyTarget(String notifyTarget) { this.notifyTarget = notifyTarget; }
    public String getEscalateTarget() { return escalateTarget; }
    public void setEscalateTarget(String escalateTarget) { this.escalateTarget = escalateTarget; }
    public Integer getEscalateAfterMinutes() { return escalateAfterMinutes; }
    public void setEscalateAfterMinutes(Integer escalateAfterMinutes) { this.escalateAfterMinutes = escalateAfterMinutes; }
    public int getReopenWithinMinutes() { return reopenWithinMinutes; }
    public void setReopenWithinMinutes(int reopenWithinMinutes) { this.reopenWithinMinutes = reopenWithinMinutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(RuleAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        RuleAuditEntry toDomain() {
            return new RuleAuditEntry(occurredAt, action, executor, fromStatus != null ? RuleStatus.valueOf(fromStatus) : null, RuleStatus.valueOf(toStatus), detail);
        }
    }

    public static final class AlertRulePersistenceMapper {

        private AlertRulePersistenceMapper() { throw new UnsupportedOperationException(); }

        public static AlertRuleDocument toDocument(AlertRule rule) {
            AlertRuleDocument doc = new AlertRuleDocument();
            doc.setId(rule.getId());
            doc.setOrganisationId(rule.getOrganisationId());
            doc.setName(rule.getName());
            doc.setCheckId(rule.getCheckId());
            doc.setImpact(rule.getImpact().name());
            doc.setUrgency(rule.getUrgency().name());
            doc.setRequesterId(rule.getRequesterId());
            doc.setNotifyTarget(rule.getOptions().notifyTarget());
            doc.setEscalateTarget(rule.getOptions().escalateTarget());
            doc.setEscalateAfterMinutes(rule.getOptions().escalateAfterMinutes());
            doc.setReopenWithinMinutes(rule.getOptions().reopenWithinMinutes());
            doc.setStatus(rule.getStatus().name());
            doc.setCreatedAt(rule.getCreatedAt());
            doc.setUpdatedAt(rule.getUpdatedAt());
            doc.setAuditTrail(rule.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static AlertRule toDomain(AlertRuleDocument doc) {
            return AlertRule.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getName(), doc.getCheckId(), Severity.valueOf(doc.getImpact()), Severity.valueOf(doc.getUrgency()),
                    doc.getRequesterId(),
                    new AlertRule.Options(doc.getNotifyTarget(), doc.getEscalateTarget(), doc.getEscalateAfterMinutes(), doc.getReopenWithinMinutes()), RuleStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
