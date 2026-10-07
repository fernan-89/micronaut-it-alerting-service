package com.thinklab.application.usecase;

import com.thinklab.application.dto.request.InitiateMaintenanceWindowRequest;
import com.thinklab.domain.exception.AlertAccessDeniedException;
import com.thinklab.domain.exception.InvalidMaintenanceWindowStatusException;
import com.thinklab.domain.exception.MaintenanceWindowNotFoundException;
import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.model.MaintenanceWindow.WindowStatus;
import com.thinklab.domain.port.HashServicePort;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The maintenance windows (ADR-035): plan, read, list, read the trail and cancel; all for staff, all scoped to the tenant. */
@ExtendWith(MockitoExtension.class)
class MaintenanceWindowUseCasesTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock private MaintenanceWindowRepository repository;
    @Mock private HashServicePort hashService;

    private final UUID org = UUID.randomUUID();

    private MaintenanceWindow window() {
        return MaintenanceWindow.createNew(UUID.randomUUID(), org, "Patching", null, NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofHours(1)), NOW, "op");
    }

    @Test
    @DisplayName("plan takes a sovereign id, saves the window as ACTIVE and answers it; a requester is refused before an id is taken; a window in the past is refused")
    void plan() {
        UUID id = UUID.randomUUID();
        UUID check = UUID.randomUUID();
        when(hashService.generateSovereignId("maintenance-window-creation")).thenReturn(Mono.just(id));
        when(repository.create(any())).thenAnswer(call -> Mono.just(call.getArgument(0)));
        InitiateMaintenanceWindowUseCase useCase = new InitiateMaintenanceWindowUseCase(hashService, repository, CLOCK);

        var request = new InitiateMaintenanceWindowRequest("Patching", check, NOW, NOW.plus(Duration.ofHours(2)));
        StepVerifier.create(useCase.execute(org, request, "op", "AGENT")).assertNext(response -> {
            assertEquals(id, response.id());
            assertEquals("ACTIVE", response.status());
            assertEquals(check, response.checkId());
        }).verifyComplete();

        var wholeTenant = new InitiateMaintenanceWindowRequest("Everything", null, NOW, NOW.plus(Duration.ofHours(2)));
        StepVerifier.create(useCase.execute(org, wholeTenant, "op", "ADMIN")).assertNext(response -> assertNull(response.checkId())).verifyComplete();

        var past = new InitiateMaintenanceWindowRequest("Over", null, NOW.minus(Duration.ofHours(3)), NOW.minus(Duration.ofHours(1)));
        StepVerifier.create(useCase.execute(org, past, "op", "AGENT")).expectError(IllegalArgumentException.class).verify();

        StepVerifier.create(useCase.execute(org, request, "op", "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("a window is read by id inside the tenant: an unknown or foreign id is a 404, a requester is refused before any read")
    void read() {
        MaintenanceWindow window = window();
        when(repository.findById(any(), eq(org))).thenReturn(Mono.empty());
        when(repository.findById(window.getId(), org)).thenReturn(Mono.just(window));
        RetrieveMaintenanceWindowUseCase useCase = new RetrieveMaintenanceWindowUseCase(repository);

        StepVerifier.create(useCase.execute(window.getId(), org, "AGENT")).assertNext(response -> {
            assertEquals(window.getId(), response.id());
            assertEquals("Patching", response.name());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(UUID.randomUUID(), org, "AGENT")).expectError(MaintenanceWindowNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(window.getId(), org, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("the list is the tenant's windows; a requester is refused and nothing is read")
    void list() {
        MaintenanceWindow first = window();
        MaintenanceWindow second = window();
        when(repository.findAll(org)).thenReturn(Flux.just(first, second));
        RetrieveMaintenanceWindowsUseCase useCase = new RetrieveMaintenanceWindowsUseCase(repository);

        StepVerifier.create(useCase.execute(org, "AGENT")).assertNext(response -> assertEquals(first.getId(), response.id()))
                .assertNext(response -> assertEquals(second.getId(), response.id())).verifyComplete();

        StepVerifier.create(useCase.execute(org, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
        verify(repository).findAll(org);
    }

    @Test
    @DisplayName("the trail of a window is for staff, tenant-scoped, and starts with the planning")
    void auditLog() {
        MaintenanceWindow window = window();
        when(repository.findById(any(), eq(org))).thenReturn(Mono.empty());
        when(repository.findById(window.getId(), org)).thenReturn(Mono.just(window));
        RetrieveMaintenanceWindowAuditLogUseCase useCase = new RetrieveMaintenanceWindowAuditLogUseCase(repository);

        StepVerifier.create(useCase.execute(window.getId(), org, "AGENT")).assertNext(trail -> {
            assertEquals(1, trail.size());
            assertEquals("INITIATED", trail.get(0).action());
        }).verifyComplete();
        StepVerifier.create(useCase.execute(UUID.randomUUID(), org, "AGENT")).expectError(MaintenanceWindowNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(window.getId(), org, "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("cancel ends the silence with a guarded save on the status loaded; a cancelled window cannot be cancelled again, an unknown one is a 404, a requester is refused")
    void cancel() {
        MaintenanceWindow window = window();
        when(repository.findById(any(), eq(org))).thenReturn(Mono.empty());
        when(repository.findById(window.getId(), org)).thenReturn(Mono.just(window));
        when(repository.save(any(), any(), any())).thenReturn(Mono.empty());
        CancelMaintenanceWindowUseCase useCase = new CancelMaintenanceWindowUseCase(repository);

        StepVerifier.create(useCase.execute(window.getId(), org, "op", "AGENT")).verifyComplete();

        assertEquals(WindowStatus.CANCELLED, window.getStatus());
        verify(repository).save(eq(window), eq(WindowStatus.ACTIVE), any());

        StepVerifier.create(useCase.execute(window.getId(), org, "op", "AGENT")).expectError(InvalidMaintenanceWindowStatusException.class).verify();
        StepVerifier.create(useCase.execute(UUID.randomUUID(), org, "op", "AGENT")).expectError(MaintenanceWindowNotFoundException.class).verify();
        StepVerifier.create(useCase.execute(window.getId(), org, "op", "REQUESTER")).expectError(AlertAccessDeniedException.class).verify();
    }

    @Test
    @DisplayName("a requester never reaches the repository")
    void requesterNeverReadsAnything() {
        new RetrieveMaintenanceWindowUseCase(repository).execute(UUID.randomUUID(), org, "REQUESTER").onErrorResume(error -> Mono.empty()).block();
        new CancelMaintenanceWindowUseCase(repository).execute(UUID.randomUUID(), org, "op", "REQUESTER").onErrorResume(error -> Mono.empty()).block();

        verifyNoInteractions(repository);
        verify(repository, never()).save(any(), any(), any());
    }
}
