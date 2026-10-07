package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable
public record AlertRuleResponse(UUID id, UUID organisationId, String name, @Nullable UUID checkId, String impact, String urgency, UUID requesterId, String status,
                                @Nullable String notifyTarget, @Nullable String escalateTarget, @Nullable Integer escalateAfterMinutes, int reopenWithinMinutes,
                                Instant createdAt, Instant updatedAt) {}
