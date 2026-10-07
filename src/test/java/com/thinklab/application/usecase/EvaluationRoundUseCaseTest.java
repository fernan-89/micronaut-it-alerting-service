package com.thinklab.application.usecase;

import com.thinklab.application.config.AlertingProperties;
import com.thinklab.application.dto.response.EvaluationResponse;
import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.exception.AlertAccessDeniedException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.domain.repository.AlertRuleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EvaluationRoundUseCaseTest {

    @Mock private AlertRuleRepository rules;
    @Mock private AlertRepository alerts;
    @Mock private AlertEvaluator evaluator;

    private final AlertingProperties properties = new AlertingProperties();

    @Test
    @DisplayName("a round looks at every tenant with an active rule or an open alert once; one tenant failing never stops the others")
    void round() {
        UUID both = UUID.randomUUID();
        UUID onlyRules = UUID.randomUUID();
        UUID onlyAlerts = UUID.randomUUID();
        UUID failing = UUID.randomUUID();
        when(rules.activeTenants()).thenReturn(Flux.just(both, onlyRules, failing));
        when(alerts.openTenants()).thenReturn(Flux.just(both, onlyAlerts));
        when(evaluator.evaluate(both, "system:alerting")).thenReturn(Mono.just(new EvaluationResponse(0, 0, 0, 0, 0)));
        when(evaluator.evaluate(onlyRules, "system:alerting")).thenReturn(Mono.just(new EvaluationResponse(1, 0, 1, 0, 0)));
        when(evaluator.evaluate(onlyAlerts, "system:alerting")).thenReturn(Mono.just(new EvaluationResponse(0, 1, 0, 0, 0)));
        when(evaluator.evaluate(failing, "system:alerting")).thenReturn(Mono.error(new UpstreamUnavailableException("The health monitor could not be read.")));

        StepVerifier.create(new EvaluationRoundUseCase(rules, alerts, evaluator, properties).execute()).expectNext(4L).verifyComplete();

        verify(evaluator).evaluate(both, "system:alerting");
    }

    @Test
    @DisplayName("with no tenant to look at, a round does nothing")
    void emptyRound() {
        when(rules.activeTenants()).thenReturn(Flux.empty());
        when(alerts.openTenants()).thenReturn(Flux.empty());

        StepVerifier.create(new EvaluationRoundUseCase(rules, alerts, evaluator, properties).execute()).expectNext(0L).verifyComplete();

        verify(evaluator, never()).evaluate(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the properties hold what they are given")
    void properties() {
        properties.setConcurrency(2);

        assertEquals(2, properties.getConcurrency());
        assertEquals(5, new AlertingProperties().getConcurrency());
    }

    @Test
    @DisplayName("the helpers are utility classes, and a requester is refused")
    void utilities() throws ReflectiveOperationException {
        for (Class<?> type : List.of(AlertAccess.class, AlertMapper.class)) {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            assertThrows(InvocationTargetException.class, constructor::newInstance);
        }
        AlertAccess.requireStaff(null, "x");
        AlertAccess.requireStaff("AGENT", "x");
        assertThrows(AlertAccessDeniedException.class, () -> AlertAccess.requireStaff("REQUESTER", "x"));
    }
}
