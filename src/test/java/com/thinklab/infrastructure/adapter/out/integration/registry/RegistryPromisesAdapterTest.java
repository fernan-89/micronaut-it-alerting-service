package com.thinklab.infrastructure.adapter.out.integration.registry;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.BackupPolicy;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.CapacityPolicy;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.PatchPolicy;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.VendorAgreement;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RegistryPromisesAdapterTest {

    private final UUID tenant = UUID.randomUUID();
    private final UUID asset = UUID.randomUUID();
    private final BackupApiClient backup = mock(BackupApiClient.class);
    private final PatchApiClient patch = mock(PatchApiClient.class);
    private final CapacityApiClient capacity = mock(CapacityApiClient.class);
    private final VendorApiClient vendor = mock(VendorApiClient.class);
    private final RegistryPromisesAdapter adapter = new RegistryPromisesAdapter(backup, patch, capacity, vendor);

    private BackupPolicy backupPolicy(String status, String protection, String restoreTest) {
        return new BackupPolicy(UUID.randomUUID(), "Nightly", asset, status, protection, restoreTest);
    }

    @Test
    @DisplayName("a backup is broken when its RPO is breached or its restore test is overdue, kept when protected, and not judged before its first backup or when paused")
    void backups() {
        when(backup.list(tenant.toString(), "system:alerting")).thenReturn(Mono.just(List.of(
                backupPolicy("ACTIVE", "RPO_BREACHED", "OVERDUE"), backupPolicy("ACTIVE", "OK", "OVERDUE"), backupPolicy("ACTIVE", "OK", "OK"),
                backupPolicy("ACTIVE", "OK", null), backupPolicy("ACTIVE", "NEVER", "NOT_REQUIRED"), backupPolicy("PAUSED", "PAUSED", null))));

        StepVerifier.create(adapter.list(tenant, Source.BACKUP).collectList()).assertNext(checks -> {
            assertEquals(List.of("DOWN", "DOWN", "UP", "UP", "UNKNOWN", "UNKNOWN"), checks.stream().map(c -> c.health()).toList());
            assertEquals("BACKUP_RPO_BREACHED", checks.get(0).error());
            assertEquals("BACKUP_RESTORE_TEST_OVERDUE", checks.get(1).error());
            assertNull(checks.get(2).error());
            assertEquals("PAUSED", checks.get(5).status());
            assertTrue(checks.stream().allMatch(c -> c.source() == Source.BACKUP && c.assetId().equals(asset) && c.name().equals("Nightly")));
        }).verifyComplete();
    }

    @Test
    @DisplayName("a patch policy is broken when it is overdue, kept when compliant, and not judged when paused")
    void patches() {
        when(patch.list(tenant.toString(), "system:alerting")).thenReturn(Mono.just(List.of(
                new PatchPolicy(UUID.randomUUID(), "Servers", asset, "ACTIVE", "OVERDUE"), new PatchPolicy(UUID.randomUUID(), "Servers", asset, "ACTIVE", "COMPLIANT"),
                new PatchPolicy(UUID.randomUUID(), "Servers", asset, "PAUSED", "PAUSED"))));

        StepVerifier.create(adapter.list(tenant, Source.PATCH).collectList()).assertNext(checks -> {
            assertEquals(List.of("DOWN", "UP", "UNKNOWN"), checks.stream().map(c -> c.health()).toList());
            assertEquals("PATCH_OVERDUE", checks.get(0).error());
            assertNull(checks.get(1).error());
            assertEquals(Source.PATCH, checks.get(0).source());
        }).verifyComplete();
    }

    @Test
    @DisplayName("a capacity policy is broken when critical, kept when OK or only warning, and not judged without data or when paused")
    void capacities() {
        when(capacity.list(tenant.toString(), "system:alerting")).thenReturn(Mono.just(List.of(
                new CapacityPolicy(UUID.randomUUID(), "Disk", asset, "ACTIVE", "CRITICAL"), new CapacityPolicy(UUID.randomUUID(), "Disk", asset, "ACTIVE", "WARNING"),
                new CapacityPolicy(UUID.randomUUID(), "Disk", asset, "ACTIVE", "OK"), new CapacityPolicy(UUID.randomUUID(), "Disk", asset, "ACTIVE", "NO_DATA"),
                new CapacityPolicy(UUID.randomUUID(), "Disk", asset, "PAUSED", "PAUSED"))));

        StepVerifier.create(adapter.list(tenant, Source.CAPACITY).collectList()).assertNext(checks -> {
            assertEquals(List.of("DOWN", "UP", "UP", "UNKNOWN", "UNKNOWN"), checks.stream().map(c -> c.health()).toList());
            assertEquals("CAPACITY_CRITICAL", checks.get(0).error());
            assertEquals(Source.CAPACITY, checks.get(0).source());
        }).verifyComplete();
    }

    @Test
    @DisplayName("an agreement is broken when it is about to end or has ended, kept when in force, and not judged before it starts or when paused (ADR-038)")
    void agreements() {
        UUID expiring = UUID.randomUUID();
        when(vendor.list(tenant.toString(), "system:alerting")).thenReturn(Mono.just(List.of(
                new VendorAgreement(expiring, "WTY-1", asset, "ACTIVE", "EXPIRING"), new VendorAgreement(UUID.randomUUID(), "CT-1", null, "ACTIVE", "EXPIRED"),
                new VendorAgreement(UUID.randomUUID(), "CT-2", null, "ACTIVE", "IN_FORCE"), new VendorAgreement(UUID.randomUUID(), "LIC-1", null, "ACTIVE", "NOT_STARTED"),
                new VendorAgreement(UUID.randomUUID(), "SUP-1", null, "PAUSED", "PAUSED"))));

        StepVerifier.create(adapter.list(tenant, Source.VENDOR).collectList()).assertNext(checks -> {
            assertEquals(List.of("DOWN", "DOWN", "UP", "UNKNOWN", "UNKNOWN"), checks.stream().map(c -> c.health()).toList());
            assertEquals("AGREEMENT_EXPIRING", checks.get(0).error());
            assertEquals("AGREEMENT_EXPIRED", checks.get(1).error());
            assertNull(checks.get(2).error());
            assertEquals(expiring, checks.get(0).id());
            assertEquals("WTY-1", checks.get(0).name());
            assertEquals(asset, checks.get(0).assetId());
            assertEquals("PAUSED", checks.get(4).status());
            assertTrue(checks.stream().allMatch(c -> c.source() == Source.VENDOR));
        }).verifyComplete();
    }

    @Test
    @DisplayName("the health checks are not read from a registry")
    void healthChecksAreNotARegistry() {
        assertThrows(IllegalArgumentException.class, () -> adapter.list(tenant, Source.HEALTH_CHECK));
    }

    @Test
    @DisplayName("a refusal names the registry and the status and never repeats the answer; an unreachable registry says so")
    void failures() {
        HttpResponse<Object> unavailable = HttpResponse.status(HttpStatus.SERVICE_UNAVAILABLE).body("secret detail of the answer");
        when(patch.list(tenant.toString(), "system:alerting"))
                .thenReturn(Mono.error(new HttpClientResponseException("boom secret detail", unavailable)))
                .thenReturn(Mono.error(new IllegalStateException("connect refused secret detail")));

        StepVerifier.create(adapter.list(tenant, Source.PATCH)).expectErrorSatisfies(error -> {
            assertTrue(error instanceof UpstreamUnavailableException);
            assertEquals("The patch policy registry could not be read (HTTP 503).", error.getMessage());
        }).verify();
        StepVerifier.create(adapter.list(tenant, Source.PATCH))
                .expectErrorSatisfies(error -> assertEquals("The patch policy registry could not be read.", error.getMessage())).verify();
    }
}
