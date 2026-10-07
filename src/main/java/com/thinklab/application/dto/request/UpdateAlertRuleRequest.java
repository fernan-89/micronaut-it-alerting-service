package com.thinklab.application.dto.request;

import com.thinklab.domain.model.AlertRule.Severity;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** DTO for {@code rule/update}: the whole definition again. */
@Serdeable
public record UpdateAlertRuleRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
        @Nullable UUID checkId,
        @NotNull(message = "Impact is required") Severity impact,
        @NotNull(message = "Urgency is required") Severity urgency,
        @NotNull(message = "The person the incident is filed for is required") UUID requesterId,
        /** The NAME of an environment variable holding a webhook address, starting with THINKLAB_ALERT_HOOK_ (never the address). */
        @Nullable String notifyTarget,
        @Nullable String escalateTarget,
        @Nullable @Min(value = 1, message = "Escalation time must be at least 1 minute") @Max(value = 1440, message = "Escalation time must not exceed 1440 minutes") Integer escalateAfterMinutes,
        /** Minutes after a resolution in which the check going down again reopens that alert (0 = never); left out means 30. */
        @Nullable @Min(value = 0, message = "Reopen time must not be negative") @Max(value = 1440, message = "Reopen time must not exceed 1440 minutes") Integer reopenWithinMinutes
) {}
