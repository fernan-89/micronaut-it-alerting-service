package com.thinklab.infrastructure.adapter.out.notice;

import com.thinklab.application.config.AlertingProperties;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.port.NoticePort.Notice;
import com.thinklab.infrastructure.adapter.out.notice.WebhookNoticeAdapter.NoticeApiRequest;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** A notice goes to the address an environment variable holds: read when used, https only (a listed test double may be http), never repeated in a failure (ADR-036). */
class WebhookNoticeAdapterTest {

    private static final String HOOK = "THINKLAB_ALERT_HOOK_OPS";
    private static final Instant AT = Instant.parse("2026-10-07T12:00:00Z");

    private final HttpClient client = mock(HttpClient.class);
    private final AlertingProperties properties = new AlertingProperties();
    private Map<String, String> environment;
    private WebhookNoticeAdapter adapter;

    @BeforeEach
    void setUp() {
        environment = new java.util.HashMap<>();
        adapter = new WebhookNoticeAdapter(client, properties, environment::get);
    }

    private Notice notice(String event) {
        return new Notice(event, UUID.randomUUID(), UUID.randomUUID(), "Intranet", "timeout", "HIGH", "MEDIUM", AT);
    }

    private void answers(Object publisher) {
        doReturn(publisher).when(client).exchange(any(HttpRequest.class));
    }

    private void refused(String address) {
        clearInvocations(client);
        environment.put(HOOK, address);
        StepVerifier.create(adapter.send(HOOK, notice("OPENED"))).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertEquals("The notification webhook address is not allowed.", error.getMessage());
        }).verify();
        verify(client, never()).exchange(any(HttpRequest.class));
    }

    @Test
    @DisplayName("the notice is POSTed as JSON to the address the variable holds, with the text line and the facts, and nothing of the target")
    void posts() {
        environment.put(HOOK, "  https://hooks.example.com/services/abc  ");
        answers(Mono.just(HttpResponse.ok()));
        Notice notice = notice("OPENED");

        StepVerifier.create(adapter.send(HOOK, notice)).verifyComplete();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).exchange(request.capture());
        assertEquals("https://hooks.example.com/services/abc", request.getValue().getUri().toString());
        assertEquals("POST", request.getValue().getMethodName());
        NoticeApiRequest body = (NoticeApiRequest) request.getValue().getBody().orElseThrow();
        assertEquals(notice.text(), body.text());
        assertEquals("OPENED", body.event());
        assertEquals(notice.alertId(), body.alertId());
        assertEquals(notice.incidentId(), body.incidentId());
        assertEquals("Intranet", body.check());
        assertEquals("timeout", body.error());
        assertEquals("HIGH", body.impact());
        assertEquals("MEDIUM", body.urgency());
        assertEquals(AT.toString(), body.at());
    }

    @Test
    @DisplayName("a target that is not a THINKLAB_ALERT_HOOK_ name, a variable that is not set and one that is blank are 'not configured'; the environment is not even read for a foreign name")
    void notConfigured() {
        environment.put("PATH", "https://hooks.example.com/x");
        environment.put("THINKLAB_ALERT_HOOK_BLANK", "   ");
        for (String target : new String[]{null, "PATH", HOOK, "THINKLAB_ALERT_HOOK_BLANK"}) {
            StepVerifier.create(adapter.send(target, notice("OPENED"))).expectErrorSatisfies(error -> {
                assertTrue(error instanceof UpstreamUnavailableException);
                assertEquals("The notification webhook is not configured.", error.getMessage());
            }).verify();
        }
        verify(client, never()).exchange(any(HttpRequest.class));
    }

    @Test
    @DisplayName("the real environment is the default source: an unset variable is 'not configured'")
    void realEnvironment() {
        WebhookNoticeAdapter real = new WebhookNoticeAdapter(client, properties);

        StepVerifier.create(real.send("THINKLAB_ALERT_HOOK_NEVER_SET_IN_A_TEST", notice("OPENED")))
                .expectErrorMessage("The notification webhook is not configured.").verify();
    }

    @Test
    @DisplayName("plain http, other schemes, a missing host or scheme and a malformed address are refused before anything is sent")
    void addressesNotAllowed() {
        refused("http://hooks.example.com/x");
        refused("ftp://hooks.example.com/x");
        refused("hooks.example.com/x");
        refused("https:///no-host");
        refused("//hooks.example.com/x");
        refused("https://bad host/x");
    }

    @Test
    @DisplayName("plain http is allowed only to a host the operator listed (any letter case), and only that host")
    void insecureHosts() {
        properties.setInsecureHosts(List.of("Mock-Target"));
        answers(Mono.just(HttpResponse.ok()));
        environment.put(HOOK, "http://mock-target:9999/hook");

        StepVerifier.create(adapter.send(HOOK, notice("RESOLVED"))).verifyComplete();
        verify(client).exchange(any(HttpRequest.class));

        refused("http://elsewhere:9999/hook");
    }

    @Test
    @DisplayName("https is allowed whatever the letter case of the scheme")
    void upperCaseScheme() {
        answers(Mono.just(HttpResponse.ok()));
        environment.put(HOOK, "HTTPS://hooks.example.com/x");

        StepVerifier.create(adapter.send(HOOK, notice("ESCALATED"))).verifyComplete();
    }

    @Test
    @DisplayName("a refusal by the webhook names the status and never repeats what it answered")
    void webhookRefuses() {
        environment.put(HOOK, "https://hooks.example.com/x");
        HttpResponse<Object> forbidden = HttpResponse.status(HttpStatus.FORBIDDEN).body("secret detail of the answer");
        answers(Mono.error(new HttpClientResponseException("boom secret detail", forbidden)));

        StepVerifier.create(adapter.send(HOOK, notice("OPENED"))).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertEquals("The notification webhook refused the notice (HTTP 403).", error.getMessage());
            assertFalse(error.getMessage().contains("secret"));
        }).verify();
    }

    @Test
    @DisplayName("an unreachable webhook says so; our own failure passes through as it is")
    void unreachable() {
        environment.put(HOOK, "https://hooks.example.com/x");
        answers(Mono.error(new IllegalStateException("connect refused secret detail")));

        StepVerifier.create(adapter.send(HOOK, notice("OPENED"))).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertEquals("The notification webhook could not be reached.", error.getMessage());
        }).verify();

        UpstreamUnavailableException own = new UpstreamUnavailableException("own");
        answers(Mono.error(own));
        StepVerifier.create(adapter.send(HOOK, notice("OPENED"))).expectErrorMatches(error -> error == own).verify();
    }
}
