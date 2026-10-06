package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

/** What one evaluation did: alerts opened, alerts resolved (the check answers again), and incidents opened (new alerts and earlier ones that had none yet). */
@Serdeable
public record EvaluationResponse(int opened, int resolved, int incidentsOpened) {}
