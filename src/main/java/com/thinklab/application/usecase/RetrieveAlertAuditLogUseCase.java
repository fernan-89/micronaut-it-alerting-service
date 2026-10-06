package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.exception.AlertNotFoundException;
import com.thinklab.domain.repository.AlertRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of an alert (BIAN Behavior Qualifier: {@code audit-log/retrieve}): when it opened, when its incident did, when it resolved. Staff only. */
@Singleton
public class RetrieveAlertAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAlertAuditLogUseCase.class);

    private final AlertRepository repository;

    public RetrieveAlertAuditLogUseCase(AlertRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of Alert ID: {}", id);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "read the audit trail of an alert"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new AlertNotFoundException("Alert " + id + " not found.")))
                .map(alert -> alert.getAuditTrail().stream().map(AlertMapper::toResponse).collect(Collectors.toList()));
    }
}
