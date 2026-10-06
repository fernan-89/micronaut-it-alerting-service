package com.thinklab.infrastructure.scheduling;

import com.thinklab.application.usecase.EvaluationRoundUseCase;
import io.micronaut.context.annotation.Requires;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs an evaluation round every few seconds (ADR-030). A round still running when the next tick comes is not doubled: the tick is skipped.
 * Off with {@code thinklab.alerting.scheduler-enabled=false} (an instance that only serves the API, or a test).
 */
@Singleton
@Requires(property = "thinklab.alerting.scheduler-enabled", notEquals = "false")
public class EvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(EvaluationScheduler.class);

    private final EvaluationRoundUseCase round;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public EvaluationScheduler(EvaluationRoundUseCase round) {
        this.round = round;
    }

    @Scheduled(fixedDelay = "${thinklab.alerting.tick:5s}", initialDelay = "10s")
    void tick() {
        if (!running.compareAndSet(false, true)) {
            log.debug("[ALERTING ROUND] The previous round is still running; this tick is skipped.");
            return;
        }
        round.execute()
                .doFinally(signal -> running.set(false))
                .subscribe(count -> log.debug("[ALERTING ROUND] Evaluated {} organisation(s).", count),
                        error -> log.error("[ALERTING ROUND] The round failed: {}", error.getClass().getSimpleName()));
    }
}
