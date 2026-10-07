package com.thinklab.application.dto.request;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/** DTO for {@code window/initiate}. {@code checkId} left out silences every check of the tenant; the window is at most 30 days and cannot end in the past. */
@Serdeable
public record InitiateMaintenanceWindowRequest(
        @NotBlank(message = "Name is required")
        @Size(max = 80, message = "Name must not exceed 80 characters")
        String name,
        @Nullable UUID checkId,
        @NotNull(message = "The start is required") Instant startsAt,
        @NotNull(message = "The end is required") Instant endsAt
) {}
