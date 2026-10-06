package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

@Serdeable
public record AuditEntryResponse(Instant occurredAt, String action, String executor, @Nullable String fromStatus, String toStatus, @Nullable String detail) {}
