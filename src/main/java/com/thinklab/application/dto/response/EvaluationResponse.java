package com.thinklab.application.dto.response;

import io.micronaut.serde.annotation.Serdeable;

/** What one evaluation did: alerts opened, alerts resolved (the check answers again), incidents opened (new alerts and earlier ones that had none yet), alerts reopened (the check went down again soon after) and notices that got through. */
@Serdeable
public record EvaluationResponse(int opened, int resolved, int incidentsOpened, int reopened, int notified) {}
