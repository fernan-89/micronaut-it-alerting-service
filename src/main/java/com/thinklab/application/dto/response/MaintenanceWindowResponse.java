package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;
import java.util.UUID;

@Serdeable
public record MaintenanceWindowResponse(UUID id, UUID organisationId, String name, @Nullable UUID checkId, Instant startsAt, Instant endsAt, String status,
                                        Instant createdAt, Instant updatedAt) {}
