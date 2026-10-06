package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.exception.AlertRuleNotFoundException;
import com.thinklab.domain.repository.AlertRuleRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Use Case for the forensic ledger of a rule (BIAN Behavior Qualifier: {@code rule/audit-log/retrieve}). Staff only. */
@Singleton
public class RetrieveAlertRuleAuditLogUseCase {

    private static final Logger log = LoggerFactory.getLogger(RetrieveAlertRuleAuditLogUseCase.class);

    private final AlertRuleRepository repository;

    public RetrieveAlertRuleAuditLogUseCase(AlertRuleRepository repository) {
        this.repository = repository;
    }

    public Mono<List<AuditEntryResponse>> execute(UUID id, UUID organisationId, String role) {
        log.info("[USE CASE] Retrieving the audit log of AlertRule ID: {}", id);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "read the audit trail of an alert rule"))
                .then(Mono.defer(() -> repository.findById(id, organisationId)))
                .switchIfEmpty(Mono.error(new AlertRuleNotFoundException("AlertRule " + id + " not found.")))
                .map(rule -> rule.getAuditTrail().stream().map(AlertMapper::toResponse).collect(Collectors.toList()));
    }
}
