package com.thinklab.application.dto.response;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.serde.annotation.Serdeable;

import java.time.Instant;

/** One notice to a webhook: which ({@code OPENED_0}, {@code RESOLVED_0}, {@code ESCALATED_1}...: the event and the cycle of the outage), how often it was tried, when it got through, and the fixed reason it did not. */
@Serdeable
public record NoticeResponse(String notice, int attempts, @Nullable Instant lastAttemptAt, @Nullable Instant sentAt, @Nullable String lastError) {}
