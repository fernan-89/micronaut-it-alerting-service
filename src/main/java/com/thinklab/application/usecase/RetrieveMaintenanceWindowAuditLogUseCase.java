package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.exception.MaintenanceWindowNotFoundException;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a window (BIAN Behavior Qualifier: {@code window/audit-log/retrieve}). Staff only. */
@Singleton
public class RetrieveMaintenanceWindowAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveMaintenanceWindowAuditLogUseCase.class);

    private final MaintenanceWindowRepository repository;

    public RetrieveMaintenanceWindowAuditLogUseCase(MaintenanceWindowRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of MaintenanceWindow ID: {}", id);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "read the audit trail of a maintenance window"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new MaintenanceWindowNotFoundException("MaintenanceWindow " + id + " not found.")))
                .map(window -> window.getAuditTrail().stream().map(AlertMapper::toResponse).collect(Collectors.toList()));
    }
}
