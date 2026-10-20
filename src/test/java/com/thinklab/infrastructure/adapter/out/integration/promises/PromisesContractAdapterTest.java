package com.thinklab.infrastructure.adapter.out.integration.promises;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import com.thinklab.infrastructure.adapter.out.integration.promises.PromisesContractAdapter.PromiseDto;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class PromisesContractAdapterTest {

    private static final Instant AT = Instant.parse("2026-10-20T12:00:00Z");
    private final HttpClient http = mock(HttpClient.class);
    private final UUID tenant = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();

    private static RegistryConfig registry(String name, String url, String path) {
        RegistryConfig config = new RegistryConfig(name);
        config.setUrl(url);
        config.setPath(path);
        return config;
    }

    private PromisesContractAdapter adapter() {
        return new PromisesContractAdapter(http, List.of(registry("BACKUP", "http://backup:8080", "/it-backup-registry/v1"), registry("DR", "http://dr:8080", "/it-dr-registry/v1"),
                registry("cloud-inventory", "http://inventory:8080", "/it-cloud-inventory/v1")));
    }

    @Test
    @DisplayName("a broken promise is DOWN with its fixed code, a kept one UP, anything else not judged; the id, name, asset and status are the registry's, the source is the one asked")
    void verdicts() {
        UUID broken = UUID.randomUUID();
        when(http.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(reactor.core.publisher.Mono.just(List.of(
                new PromiseDto(broken, "Orders failover", asset, "BROKEN", "DR_EXERCISE_FAILED", "ACTIVE", AT), new PromiseDto(UUID.randomUUID(), "Web", asset, "KEPT", null, "ACTIVE", AT),
                new PromiseDto(UUID.randomUUID(), "New", null, "NOT_JUDGED", null, "ACTIVE", AT), new PromiseDto(UUID.randomUUID(), "Odd", null, "SOMETHING_NEW", "X", "PAUSED", AT),
                new PromiseDto(UUID.randomUUID(), "None", null, null, "X", "ACTIVE", AT), new PromiseDto(UUID.randomUUID(), "Kept with a stray code", null, "KEPT", "STRAY", "ACTIVE", AT))));

        StepVerifier.create(adapter().list(tenant, Source.DR).collectList()).assertNext(checks -> {
            assertEquals(List.of("DOWN", "UP", "UNKNOWN", "UNKNOWN", "UNKNOWN", "UP"), checks.stream().map(ObservedCheck::health).toList());
            assertEquals("DR_EXERCISE_FAILED", checks.get(0).error());
            assertNull(checks.get(1).error());
            assertNull(checks.get(3).error());
            assertNull(checks.get(5).error());
            assertEquals(broken, checks.get(0).id());
            assertEquals("Orders failover", checks.get(0).name());
            assertEquals(asset, checks.get(0).assetId());
            assertEquals("PAUSED", checks.get(3).status());
            assertTrue(checks.stream().allMatch(c -> c.source() == Source.DR));
        }).verifyComplete();
    }

    @Test
    @DisplayName("the registry's own route is asked for the tenant as the service identity, with the window and the limit of the contract")
    void request() {
        when(http.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(reactor.core.publisher.Mono.just(List.of()));

        StepVerifier.create(adapter().list(tenant, Source.BACKUP)).verifyComplete();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).retrieve(request.capture(), any(Argument.class));
        assertEquals("http://backup:8080/it-backup-registry/v1/promises/retrieve?changedWithinDays=30&limit=500", request.getValue().getUri().toString());
        assertEquals(tenant.toString(), request.getValue().getHeaders().get("X-Tenant-Id"));
        assertEquals("system:alerting", request.getValue().getHeaders().get("X-Executor"));
    }

    @Test
    @DisplayName("a source is found by its name however the property key was normalised (CLOUD_INVENTORY is read as cloud-inventory)")
    void normalisedNames() {
        when(http.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(reactor.core.publisher.Mono.just(List.of()));

        StepVerifier.create(adapter().list(tenant, Source.CLOUD_INVENTORY)).verifyComplete();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).retrieve(request.capture(), any(Argument.class));
        assertTrue(request.getValue().getUri().toString().startsWith("http://inventory:8080/it-cloud-inventory/v1/promises/retrieve"));
    }

    @Test
    @DisplayName("a registry that cannot be read is an upstream failure that says which registry and which status, and never repeats its answer")
    void failures() {
        HttpClientResponseException refused = new HttpClientResponseException("secret-detail", HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).body("secret-detail"));
        when(http.retrieve(any(HttpRequest.class), any(Argument.class))).thenReturn(reactor.core.publisher.Mono.error(refused), reactor.core.publisher.Mono.error(new IllegalStateException("secret-detail")));

        StepVerifier.create(adapter().list(tenant, Source.DR)).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertTrue(error.getMessage().contains("(HTTP 500)"));
            assertTrue(error.getMessage().contains("recovery plan"));
            assertFalse(error.getMessage().contains("secret"));
        }).verify();
        StepVerifier.create(adapter().list(tenant, Source.DR)).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertFalse(error.getMessage().contains("secret"));
            assertFalse(error.getMessage().contains("HTTP"));
        }).verify();
    }

    @Test
    @DisplayName("a source with no registry configured, such as the health checks, is not read from one")
    void unconfigured() {
        StepVerifier.create(adapter().list(tenant, Source.HEALTH_CHECK)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(adapter().list(tenant, Source.PATCH)).expectError(IllegalArgumentException.class).verify();
        assertEquals("BACKUP", registry("BACKUP", "u", "p").getName());
        assertEquals("u", registry("BACKUP", "u", "p").getUrl());
        assertEquals("p", registry("BACKUP", "u", "p").getPath());
        assertThrows(NullPointerException.class, () -> new PromisesContractAdapter(http, null));
    }
}
