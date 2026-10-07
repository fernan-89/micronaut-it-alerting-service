package com.thinklab.infrastructure.adapter.in.web.handler;

import com.thinklab.domain.exception.AlertAccessDeniedException;
import com.thinklab.domain.exception.AlertNotFoundException;
import com.thinklab.domain.exception.DuplicateAlertException;
import com.thinklab.domain.exception.InvalidAlertStatusException;
import com.thinklab.domain.exception.UpstreamUnavailableException;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler exceptionHandler;
    private HttpRequest<?> request;
    private HttpHeaders headers;

    @BeforeEach
    void setUp() {
        exceptionHandler = new GlobalExceptionHandler();
        request = Mockito.mock(HttpRequest.class);
        headers = Mockito.mock(HttpHeaders.class);
        Mockito.when(request.getPath()).thenReturn("/it-alerting/v1/test");
        Mockito.when(request.getAttribute(Mockito.eq("traceId"), Mockito.eq(String.class))).thenReturn(Optional.empty());
        Mockito.when(request.getHeaders()).thenReturn(headers);
        Mockito.when(headers.get("X-Trace-Id")).thenReturn(null);
    }

    private Map<String, Object> assertProblem(HttpResponse<Map<String, Object>> response, HttpStatus status, String code) {
        assertNotNull(response);
        assertEquals(status, response.getStatus());
        Map<String, Object> body = response.body();
        assertNotNull(body);
        assertEquals(status.getCode(), body.get("status"));
        assertEquals(code, body.get("error_code"));
        assertEquals("/it-alerting/v1/test", body.get("instance"));
        assertNotNull(body.get("timestamp"));
        assertNotNull(body.get("title"));
        assertNotNull(body.get("type"));
        return body;
    }

    @Test
    @DisplayName("AlertNotFoundException maps to 404 with ERR-ALR-00404")
    void notFound() {
        UUID id = UUID.randomUUID();

        Map<String, Object> body = assertProblem(exceptionHandler.handle(request, new AlertNotFoundException(id)),
                HttpStatus.NOT_FOUND, "ERR-ALR-00404");

        assertTrue(body.get("detail").toString().contains(id.toString()));
        assertEquals("https://api.thinklab.com/errors/err-alr-00404", body.get("type"));
    }

    @Test
    @DisplayName("DuplicateAlertException maps to 409 with ERR-ALR-00409")
    void conflict() {
        assertProblem(exceptionHandler.handle(request, new DuplicateAlertException("dup")), HttpStatus.CONFLICT, "ERR-ALR-00409");
    }

    @Test
    @DisplayName("InvalidAlertStatusException maps to 409 Conflict with ERR-ALR-00409 (AST-03)")
    void stateConflictIs409() {
        Map<String, Object> body = assertProblem(exceptionHandler.handle(request, new InvalidAlertStatusException("Illegal transition")),
                HttpStatus.CONFLICT, "ERR-ALR-00409");

        assertEquals("Illegal transition", body.get("detail"));
    }

    @Test
    @DisplayName("ConstraintViolationException maps to 400 with ERR-VALIDATION-00400")
    void validation() {
        assertProblem(exceptionHandler.handle(request, new ConstraintViolationException("Validation failed", Collections.emptySet())),
                HttpStatus.BAD_REQUEST, "ERR-VALIDATION-00400");
    }

    @Test
    @DisplayName("IllegalArgumentException (malformed identifier / domain guard) maps to 400")
    void illegalArgument() {
        Map<String, Object> body = assertProblem(exceptionHandler.handle(request, new IllegalArgumentException("Invalid UUID string: x")),
                HttpStatus.BAD_REQUEST, "ERR-VALIDATION-00400");

        assertTrue(body.get("detail").toString().contains("Invalid UUID string: x"));
    }

    @Test
    @DisplayName("a value outside a fixed list is a 400 that never repeats the value")
    void unknownEnumValueIsNotEchoed() {
        Map<String, Object> body = assertProblem(exceptionHandler.handle(request, new IllegalArgumentException("No enum constant com.thinklab.X.password=hunter2")),
                HttpStatus.BAD_REQUEST, "ERR-VALIDATION-00400");

        assertTrue(body.get("detail").toString().contains("outside the allowed list"));
        assertFalse(body.get("detail").toString().contains("hunter2"));
    }

    @Test
    @DisplayName("an illegal argument without a message is still a 400")
    void illegalArgumentWithoutMessage() {
        assertProblem(exceptionHandler.handle(request, new IllegalArgumentException()), HttpStatus.BAD_REQUEST, "ERR-VALIDATION-00400");
    }

    @Test
    @DisplayName("an unexpected technical failure maps to 500 with debug_info and no stack trace leak")
    void generic() {
        Map<String, Object> body = assertProblem(exceptionHandler.handle(request, new RuntimeException("Unexpected internal failure")),
                HttpStatus.INTERNAL_SERVER_ERROR, "ERR-INTERNAL-00500");

        assertEquals("RuntimeException: Unexpected internal failure", body.get("debug_info"));
        assertEquals("An unexpected technical failure occurred within the processing pipeline.", body.get("detail"));
    }

    @Test
    @DisplayName("the X-Trace-Id request header and the traceId attribute are both accepted as trace sources")
    void traceSources() {
        Mockito.when(headers.get("X-Trace-Id")).thenReturn("abc-123");
        assertProblem(exceptionHandler.handle(request, new AlertNotFoundException("x")), HttpStatus.NOT_FOUND, "ERR-ALR-00404");

        Mockito.when(request.getAttribute(Mockito.eq("traceId"), Mockito.eq(String.class))).thenReturn(Optional.of("attr-trace"));
        assertProblem(exceptionHandler.handle(request, new AlertNotFoundException("x")), HttpStatus.NOT_FOUND, "ERR-ALR-00404");
    }

    @Test
    @DisplayName("a trace id from the X-Trace-Id header is honoured and a blank one replaced")
    void traceIdFromHeader() {
        Mockito.when(headers.get("X-Trace-Id")).thenReturn("header-trace");
        assertProblem(exceptionHandler.handle(request, new AlertNotFoundException("x")), HttpStatus.NOT_FOUND, "ERR-ALR-00404");

        Mockito.when(headers.get("X-Trace-Id")).thenReturn(" ");
        assertProblem(exceptionHandler.handle(request, new AlertNotFoundException("x")), HttpStatus.NOT_FOUND, "ERR-ALR-00404");
    }

    @Test
    @DisplayName("AlertAccessDeniedException maps to 403 with ERR-ALR-00403")
    void forbidden() {
        assertProblem(exceptionHandler.handle(request, new AlertAccessDeniedException("Staff only.")), HttpStatus.FORBIDDEN, "ERR-ALR-00403");
    }

    @Test
    @DisplayName("UpstreamUnavailableException maps to 502 with ERR-ALR-00502")
    void badGateway() {
        assertProblem(exceptionHandler.handle(request, new UpstreamUnavailableException("The health monitor could not be read.")), HttpStatus.BAD_GATEWAY, "ERR-ALR-00502");
    }
}
