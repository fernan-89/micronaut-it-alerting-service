package com.thinklab.application.dto.request;

import com.thinklab.domain.model.AlertRule.Severity;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
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
        @NotNull(message = "The person the incident is filed for is required") UUID requesterId
) {}
