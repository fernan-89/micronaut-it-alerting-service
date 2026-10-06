package com.thinklab.domain.repository;

import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleAuditEntry;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Outbound Port for AlertRule persistence. {@link #save} is a guarded write on the status loaded (ADR-033); every lookup is tenant-scoped except {@link #activeTenants}. */
public interface AlertRuleRepository {

    /** Inserts the rule; a name already used in the organisation is {@link com.thinklab.domain.exception.DuplicateAlertRuleException}. */
    Mono<AlertRule> create(AlertRule rule);

    Mono<AlertRule> findById(UUID id, UUID organisationId);

    /** Oldest first, so the oldest of several matching rules is the one that opens the alert. */
    Flux<AlertRule> findAll(UUID organisationId);

    Mono<Void> save(AlertRule rule, RuleStatus expectedStatus, RuleAuditEntry auditEntry);

    /** The tenants that have at least one ACTIVE rule: the ones the monitor has to look at. */
    Flux<UUID> activeTenants();
}
