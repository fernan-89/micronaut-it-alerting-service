package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable
public record AlertResponse(UUID id, UUID organisationId, UUID ruleId, UUID checkId, String checkName, @Nullable UUID assetId, String status, Instant openedAt,
                            @Nullable Instant resolvedAt, @Nullable UUID incidentId, @Nullable String lastError, @Nullable String problem, Instant updatedAt) {}
