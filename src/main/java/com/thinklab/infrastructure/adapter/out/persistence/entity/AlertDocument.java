package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertAuditEntry;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.Alert.Notice;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the Alert Aggregate for MongoDB. It holds the check's id, name, asset and fixed-vocabulary error: never what a target said. */
@Introspected
public class AlertDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private UUID ruleId;
    private UUID checkId;
    private String checkName;
    private UUID assetId;
    private String status;
    private Instant openedAt;
    private Instant resolvedAt;
    private UUID incidentId;
    private String lastError;
    private String problem;
    private Instant updatedAt;
    private int reopenCount;
    private Instant reopenedAt;
    private Map<String, NoticeDocument> notices = new HashMap<>();
    private List<AuditEntryDocument> auditTrail = new ArrayList<>();

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getOrganisationId() { return organisationId; }
    public void setOrganisationId(UUID organisationId) { this.organisationId = organisationId; }
    public UUID getRuleId() { return ruleId; }
    public void setRuleId(UUID ruleId) { this.ruleId = ruleId; }
    public UUID getCheckId() { return checkId; }
    public void setCheckId(UUID checkId) { this.checkId = checkId; }
    public String getCheckName() { return checkName; }
    public void setCheckName(String checkName) { this.checkName = checkName; }
    public UUID getAssetId() { return assetId; }
    public void setAssetId(UUID assetId) { this.assetId = assetId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
    public UUID getIncidentId() { return incidentId; }
    public void setIncidentId(UUID incidentId) { this.incidentId = incidentId; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public String getProblem() { return problem; }
    public void setProblem(String problem) { this.problem = problem; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public int getReopenCount() { return reopenCount; }
    public void setReopenCount(int reopenCount) { this.reopenCount = reopenCount; }
    public Instant getReopenedAt() { return reopenedAt; }
    public void setReopenedAt(Instant reopenedAt) { this.reopenedAt = reopenedAt; }
    public Map<String, NoticeDocument> getNotices() { return notices; }
    public void setNotices(Map<String, NoticeDocument> notices) { this.notices = notices; }
    public List<AuditEntryDocument> getAuditTrail() { return auditTrail; }
    public void setAuditTrail(List<AuditEntryDocument> auditTrail) { this.auditTrail = auditTrail; }

    @Introspected
    public record NoticeDocument(int attempts, Instant lastAttemptAt, Instant sentAt, String lastError) {

        static NoticeDocument fromDomain(Notice notice) {
            return new NoticeDocument(notice.attempts(), notice.lastAttemptAt(), notice.sentAt(), notice.lastError());
        }

        Notice toDomain() {
            return new Notice(attempts, lastAttemptAt, sentAt, lastError);
        }
    }

    @Introspected
    public record AuditEntryDocument(Instant occurredAt, String action, String executor, String fromStatus, String toStatus, String detail) {

        public static AuditEntryDocument fromDomain(AlertAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        AlertAuditEntry toDomain() {
            return new AlertAuditEntry(occurredAt, action, executor, fromStatus != null ? AlertStatus.valueOf(fromStatus) : null, AlertStatus.valueOf(toStatus), detail);
        }
    }

    public static final class AlertPersistenceMapper {

        private AlertPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static AlertDocument toDocument(Alert alert) {
            AlertDocument doc = new AlertDocument();
            doc.setId(alert.getId());
            doc.setOrganisationId(alert.getOrganisationId());
            doc.setRuleId(alert.getRuleId());
            doc.setCheckId(alert.getCheckId());
            doc.setCheckName(alert.getCheckName());
            doc.setAssetId(alert.getAssetId());
            doc.setStatus(alert.getStatus().name());
            doc.setOpenedAt(alert.getOpenedAt());
            doc.setResolvedAt(alert.getResolvedAt());
            doc.setIncidentId(alert.getIncidentId());
            doc.setLastError(alert.getLastError());
            doc.setProblem(alert.getProblem());
            doc.setUpdatedAt(alert.getUpdatedAt());
            doc.setReopenCount(alert.getReopenCount());
            doc.setReopenedAt(alert.getReopenedAt());
            doc.setNotices(alert.getNotices().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> NoticeDocument.fromDomain(entry.getValue()))));
            doc.setAuditTrail(alert.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static Alert toDomain(AlertDocument doc) {
            return Alert.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getRuleId(), doc.getCheckId(), doc.getCheckName(), doc.getAssetId(), AlertStatus.valueOf(doc.getStatus()),
                    doc.getOpenedAt(), doc.getResolvedAt(), doc.getIncidentId(), doc.getLastError(), doc.getProblem(), doc.getUpdatedAt(),
                    doc.getReopenCount(), doc.getReopenedAt(),
                    doc.getNotices().entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().toDomain())),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
