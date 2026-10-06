package com.thinklab.application.usecase;

import com.thinklab.application.config.AlertingProperties;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.domain.repository.AlertRuleRepository;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** One tick of alerting: every tenant that has an active rule or an open alert is evaluated, a few at once. One tenant failing never stops the others. */
@Singleton
public class EvaluationRoundUseCase {

    private static final Logger log = LoggerFactory.getLogger(EvaluationRoundUseCase.class);

    private final AlertRuleRepository ruleRepository;
    private final AlertRepository alertRepository;
    private final AlertEvaluator evaluator;
    private final AlertingProperties properties;

    public EvaluationRoundUseCase(AlertRuleRepository ruleRepository, AlertRepository alertRepository, AlertEvaluator evaluator, AlertingProperties properties) {
        this.ruleRepository = ruleRepository;
        this.alertRepository = alertRepository;
        this.evaluator = evaluator;
        this.properties = properties;
    }

    /** Emits how many tenants were evaluated. */
    public Mono<Long> execute() {
        return Flux.merge(ruleRepository.activeTenants(), alertRepository.openTenants())
                .distinct()
                .flatMap(tenant -> evaluator.evaluate(tenant, AlertAccess.SYSTEM_EXECUTOR)
                        .onErrorResume(error -> {
                            log.warn("[ALERTING ROUND] Evaluating organisation {} failed: {}", tenant, error.getClass().getSimpleName());
                            return Mono.empty();
                        })
                        .then(Mono.just(1)), properties.getConcurrency())
                .count();
    }
}
