package com.thinklab.infrastructure.adapter.out.integration.health;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.infrastructure.adapter.out.integration.health.HealthMonitoringAdapter.CheckApiResponse;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class HealthMonitoringAdapterTest {

    private final UUID tenant = UUID.randomUUID();
    private final HealthMonitoringApiClient client = mock(HealthMonitoringApiClient.class);
    private final HealthMonitoringAdapter adapter = new HealthMonitoringAdapter(client);

    @Test
    @DisplayName("the checks of the tenant are read as the service identity, keeping only what alerting needs")
    void lists() {
        UUID id = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        when(client.list(tenant.toString(), "system:alerting")).thenReturn(Mono.just(List.of(
                new CheckApiResponse(id, "Intranet", asset, "DOWN", "ACTIVE", "timeout"),
                new CheckApiResponse(UUID.randomUUID(), "Db", null, "UP", "PAUSED", null))));

        StepVerifier.create(adapter.list(tenant))
                .assertNext(check -> {
                    assertEquals(id, check.id());
                    assertEquals("Intranet", check.name());
                    assertEquals(asset, check.assetId());
                    assertTrue(check.isDown());
                    assertFalse(check.isUp());
                    assertEquals("timeout", check.error());
                })
                .assertNext(check -> {
                    assertTrue(check.isUp());
                    assertFalse(check.isDown());
                    assertEquals("PAUSED", check.status());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a refusal names the status and never repeats the answer; an unreachable monitor says so")
    void failures() {
        HttpResponse<Object> unavailable = HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("secret detail of the answer");
        when(client.list(tenant.toString(), "system:alerting"))
                .thenReturn(Mono.error(new HttpClientResponseException("boom secret detail", unavailable)))
                .thenReturn(Mono.error(new IllegalStateException("connect refused secret detail")));

        StepVerifier.create(adapter.list(tenant)).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertEquals("The health monitor could not be read (HTTP 503).", error.getMessage());
        }).verify();
        StepVerifier.create(adapter.list(tenant))
                .expectErrorSatisfies(error -> assertEquals("The health monitor could not be read.", error.getMessage())).verify();
    }
}
