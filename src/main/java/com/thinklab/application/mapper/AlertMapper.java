package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AlertResponse;
import com.thinklab.application.dto.response.AlertRuleResponse;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertAuditEntry;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleAuditEntry;

public final class AlertMapper {

    private AlertMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static AlertRuleResponse toResponse(AlertRule rule) {
        return new AlertRuleResponse(rule.getId(), rule.getOrganisationId(), rule.getName(), rule.getCheckId(), rule.getImpact().name(), rule.getUrgency().name(),
                rule.getRequesterId(), rule.getStatus().name(), rule.getCreatedAt(), rule.getUpdatedAt());
    }

    public static AlertResponse toResponse(Alert alert) {
        return new AlertResponse(alert.getId(), alert.getOrganisationId(), alert.getRuleId(), alert.getCheckId(), alert.getCheckName(), alert.getAssetId(),
                alert.getStatus().name(), alert.getOpenedAt(), alert.getResolvedAt(), alert.getIncidentId(), alert.getLastError(), alert.getProblem(), alert.getUpdatedAt());
    }

    public static AuditEntryResponse toResponse(RuleAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(), entry.detail());
    }

    public static AuditEntryResponse toResponse(AlertAuditEntry entry) {
        return new AuditEntryResponse(entry.occurredAt(), entry.action(), entry.executor(), entry.fromStatus() != null ? entry.fromStatus().name() : null,
                entry.toStatus().name(), entry.detail());
    }
}
