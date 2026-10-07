package com.thinklab.infrastructure.adapter.out.persistence.entity;

import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.model.MaintenanceWindow.WindowAuditEntry;
import com.thinklab.domain.model.MaintenanceWindow.WindowStatus;
import io.micronaut.core.annotation.Introspected;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Infrastructure-specific representation of the MaintenanceWindow Aggregate for MongoDB. */
@Introspected
public class MaintenanceWindowDocument {

    @BsonId
    private UUID id;

    private UUID organisationId;
    private String name;
    private UUID checkId;
    private Instant startsAt;
    private Instant endsAt;
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
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
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

        public static AuditEntryDocument fromDomain(WindowAuditEntry entry) {
            return new AuditEntryDocument(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                    entry.toStatus().name(), entry.detail());
        }

        // toStatus has no null branch: fromDomain always writes entry.toStatus().name().
        WindowAuditEntry toDomain() {
            return new WindowAuditEntry(occurredAt, action, executor, fromStatus != null ? WindowStatus.valueOf(fromStatus) : null, WindowStatus.valueOf(toStatus), detail);
        }
    }

    public static final class MaintenanceWindowPersistenceMapper {

        private MaintenanceWindowPersistenceMapper() { throw new UnsupportedOperationException(); }

        public static MaintenanceWindowDocument toDocument(MaintenanceWindow window) {
            MaintenanceWindowDocument doc = new MaintenanceWindowDocument();
            doc.setId(window.getId());
            doc.setOrganisationId(window.getOrganisationId());
            doc.setName(window.getName());
            doc.setCheckId(window.getCheckId());
            doc.setStartsAt(window.getStartsAt());
            doc.setEndsAt(window.getEndsAt());
            doc.setStatus(window.getStatus().name());
            doc.setCreatedAt(window.getCreatedAt());
            doc.setUpdatedAt(window.getUpdatedAt());
            doc.setAuditTrail(window.getAuditTrail().stream().map(AuditEntryDocument::fromDomain).collect(Collectors.toCollection(ArrayList::new)));
            return doc;
        }

        public static MaintenanceWindow toDomain(MaintenanceWindowDocument doc) {
            return MaintenanceWindow.reconstitute(doc.getId(), doc.getOrganisationId(), doc.getName(), doc.getCheckId(), doc.getStartsAt(), doc.getEndsAt(),
                    WindowStatus.valueOf(doc.getStatus()), doc.getCreatedAt(), doc.getUpdatedAt(),
                    doc.getAuditTrail().stream().map(AuditEntryDocument::toDomain).collect(Collectors.toList()));
        }
    }
}
