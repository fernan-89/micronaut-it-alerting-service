package com.thinklab.infrastructure.adapter.out.integration.incident;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.domain.port.IncidentsPort.IncidentDraft;
import com.thinklab.infrastructure.adapter.out.integration.incident.IncidentServiceAdapter.CommentApiRequest;
import com.thinklab.infrastructure.adapter.out.integration.incident.IncidentServiceAdapter.IncidentApiResponse;
import com.thinklab.infrastructure.adapter.out.integration.incident.IncidentServiceAdapter.OpenIncidentApiRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IncidentServiceAdapterTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();
    private final IncidentApiClient client = mock(IncidentApiClient.class);
    private final IncidentServiceAdapter adapter = new IncidentServiceAdapter(client);

    @Test
    @DisplayName("an incident is opened as the service identity, with the asset when there is one, and its id comes back")
    void opens() {
        UUID incident = UUID.randomUUID();
        UUID asset = UUID.randomUUID();
        when(client.open(eq(tenant.toString()), eq("system:alerting"), any())).thenReturn(Mono.just(new IncidentApiResponse(incident)));

        StepVerifier.create(adapter.open(tenant, new IncidentDraft("Health check down: Intranet", "d", Severity.HIGH, Severity.LOW, requester, asset, "alert-1")))
                .expectNext(incident).verifyComplete();
        StepVerifier.create(adapter.open(tenant, new IncidentDraft("t", "d", Severity.LOW, Severity.LOW, requester, null, "alert-1")))
                .expectNext(incident).verifyComplete();

        ArgumentCaptor<OpenIncidentApiRequest> body = ArgumentCaptor.forClass(OpenIncidentApiRequest.class);
        verify(client, times(2)).open(any(), any(), body.capture());
        assertEquals("HIGH", body.getAllValues().get(0).impact());
        assertEquals("LOW", body.getAllValues().get(0).urgency());
        assertEquals(requester, body.getAllValues().get(0).requesterId());
        assertEquals(Set.of(asset), body.getAllValues().get(0).affectedAssetIds());
        assertNull(body.getAllValues().get(1).affectedAssetIds());
    }

    @Test
    @DisplayName("a note is added as INTERNAL: a requester never reads it")
    void comments() {
        UUID incident = UUID.randomUUID();
        when(client.comment(eq(incident), eq(tenant.toString()), eq("system:alerting"), any())).thenReturn(Mono.empty());

        StepVerifier.create(adapter.comment(tenant, incident, "back up")).verifyComplete();

        verify(client).comment(incident, tenant.toString(), "system:alerting", new CommentApiRequest("back up", true));
    }

    @Test
    @DisplayName("the status of an incident is read as the service identity, and only the status comes back")
    void reads() {
        UUID incident = UUID.randomUUID();
        when(client.retrieve(incident, tenant.toString(), "system:alerting")).thenReturn(Mono.just(new IncidentServiceAdapter.IncidentStatusApiResponse("IN_PROGRESS")));

        StepVerifier.create(adapter.status(tenant, incident)).expectNext("IN_PROGRESS").verifyComplete();
    }

    @Test
    @DisplayName("a status that cannot be read names the status code (or nothing) and never repeats the answer")
    void readFailures() {
        UUID incident = UUID.randomUUID();
        HttpResponse<Object> missing = HttpResponse.status(HttpStatus.NOT_FOUND).body("secret detail of the answer");
        when(client.retrieve(eq(incident), any(), any())).thenReturn(Mono.error(new HttpClientResponseException("boom secret detail", missing)))
                .thenReturn(Mono.error(new IllegalStateException("connect refused secret detail")));

        StepVerifier.create(adapter.status(tenant, incident)).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertEquals("The incident service did not accept the incident to be read (HTTP 404).", error.getMessage());
        }).verify();
        StepVerifier.create(adapter.status(tenant, incident)).expectErrorSatisfies(error ->
                assertEquals("The incident service did not accept the incident to be read.", error.getMessage())).verify();
    }

    @Test
    @DisplayName("a refusal names the status and never repeats the answer; an unreachable service says so, for both calls")
    void failures() {
        HttpResponse<Object> refused = HttpResponse.status(HttpStatus.BAD_GATEWAY).body("secret detail of the answer");
        when(client.open(any(), any(), any())).thenReturn(Mono.error(new HttpClientResponseException("boom secret detail", refused)));
        when(client.comment(any(), any(), any(), any())).thenReturn(Mono.error(new IllegalStateException("connect refused secret detail")));

        StepVerifier.create(adapter.open(tenant, new IncidentDraft("t", "d", Severity.LOW, Severity.LOW, requester, null, "alert-1"))).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertEquals("The incident service did not accept the incident to be opened (HTTP 502).", error.getMessage());
        }).verify();
        StepVerifier.create(adapter.comment(tenant, UUID.randomUUID(), "x")).expectErrorSatisfies(error ->
                assertEquals("The incident service did not accept the incident to be commented on.", error.getMessage())).verify();
    }
}
