package com.thinklab.application.usecase;

import com.thinklab.application.dto.response.EvaluationResponse;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Use Case for looking at the tenant right now (BIAN Behavior Qualifier: {@code evaluation/execute}): the same evaluation the scheduler runs, and a 502 when the monitor cannot be reached. */
@Singleton
public class EvaluateAlertsUseCase {

    private static final Logger log = LoggerFactory.getLogger(EvaluateAlertsUseCase.class);

    private final AlertEvaluator evaluator;

    public EvaluateAlertsUseCase(AlertEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    public Mono<EvaluationResponse> execute(UUID organisationId, String executor, String role) {
        log.info("[USE CASE] Evaluating the alerts of organisation: {}", organisationId);

        return Mono.fromRunnable(() -> AlertAccess.requireStaff(role, "evaluate alerts"))
                .then(Mono.defer(() -> evaluator.evaluate(organisationId, executor)));
    }
}
