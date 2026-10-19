package com.thinklab.infrastructure.adapter.out.integration.registry;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.BackupPolicy;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.CapacityPolicy;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.LicenseSeats;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.PatchPolicy;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.CloudResourcePresence;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.PurchaseOrderStanding;
import com.thinklab.infrastructure.adapter.out.integration.registry.RegistryPromisesAdapter.RecoveryPlanReadiness;
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
    private final LicenseApiClient license = mock(LicenseApiClient.class);
    private final DrApiClient dr = mock(DrApiClient.class);
    private final CloudInventoryApiClient inventory = mock(CloudInventoryApiClient.class);
    private final ProcurementApiClient procurement = mock(ProcurementApiClient.class);
    private final RegistryPromisesAdapter adapter = new RegistryPromisesAdapter(backup, patch, capacity, vendor, license, dr, inventory, procurement);

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
    @DisplayName("a licence is broken when over its limit, kept when OK or only near it, and not judged without a count or when paused (ADR-039)")
    void licences() {
        UUID over = UUID.randomUUID();
        when(license.list(tenant.toString(), "system:alerting")).thenReturn(Mono.just(List.of(
                new LicenseSeats(over, "Office 365 E3", "ACTIVE", "OVER_LIMIT"), new LicenseSeats(UUID.randomUUID(), "Visio", "ACTIVE", "NEAR_LIMIT"),
                new LicenseSeats(UUID.randomUUID(), "CAD", "ACTIVE", "OK"), new LicenseSeats(UUID.randomUUID(), "New", "ACTIVE", "NO_DATA"),
                new LicenseSeats(UUID.randomUUID(), "Old", "PAUSED", "PAUSED"))));

        StepVerifier.create(adapter.list(tenant, Source.LICENSE).collectList()).assertNext(checks -> {
            assertEquals(List.of("DOWN", "UP", "UP", "UNKNOWN", "UNKNOWN"), checks.stream().map(c -> c.health()).toList());
            assertEquals("LICENSE_OVER_LIMIT", checks.get(0).error());
            assertNull(checks.get(1).error());
            assertEquals(over, checks.get(0).id());
            assertEquals("Office 365 E3", checks.get(0).name());
            assertNull(checks.get(0).assetId());
            assertEquals("PAUSED", checks.get(4).status());
            assertTrue(checks.stream().allMatch(c -> c.source() == Source.LICENSE));
        }).verifyComplete();
    }

    @Test
    @DisplayName("a recovery plan is broken when its latest exercise failed, an objective was missed or an exercise is overdue; kept when ready; not judged when never exercised or paused (ADR-041)")
    void recoveryPlans() {
        UUID failed = UUID.randomUUID();
        when(dr.list(tenant.toString(), "system:alerting")).thenReturn(Mono.just(List.of(
                new RecoveryPlanReadiness(failed, "Orders failover", asset, "ACTIVE", "LAST_EXERCISE_FAILED"), new RecoveryPlanReadiness(UUID.randomUUID(), "Mail", asset, "ACTIVE", "OBJECTIVE_MISSED"),
                new RecoveryPlanReadiness(UUID.randomUUID(), "Files", asset, "ACTIVE", "TEST_OVERDUE"), new RecoveryPlanReadiness(UUID.randomUUID(), "Web", asset, "ACTIVE", "READY"),
                new RecoveryPlanReadiness(UUID.randomUUID(), "New", asset, "ACTIVE", "NEVER_TESTED"), new RecoveryPlanReadiness(UUID.randomUUID(), "Old", asset, "PAUSED", "PAUSED"))));

        StepVerifier.create(adapter.list(tenant, Source.DR).collectList()).assertNext(checks -> {
            assertEquals(List.of("DOWN", "DOWN", "DOWN", "UP", "UNKNOWN", "UNKNOWN"), checks.stream().map(c -> c.health()).toList());
            assertEquals(List.of("DR_EXERCISE_FAILED", "DR_OBJECTIVE_MISSED", "DR_TEST_OVERDUE"), checks.subList(0, 3).stream().map(c -> c.error()).toList());
            assertNull(checks.get(3).error());
            assertNull(checks.get(4).error());
            assertEquals(failed, checks.get(0).id());
            assertEquals("Orders failover", checks.get(0).name());
            assertEquals(asset, checks.get(0).assetId());
            assertEquals("PAUSED", checks.get(5).status());
            assertTrue(checks.stream().allMatch(c -> c.source() == Source.DR));
        }).verifyComplete();
    }

    @Test
    @DisplayName("a cloud resource is broken when the tool has not seen it for too long, kept when seen lately or terminated on purpose; a terminated one never opens an alert (ADR-042)")
    void cloudResources() {
        UUID missing = UUID.randomUUID();
        CloudResourcePresence web1 = new CloudResourcePresence(missing, "web-1", "RUNNING", "MISSING");
        when(inventory.list(tenant.toString(), "system:alerting", 500, "MISSING")).thenReturn(Mono.just(List.of(web1)));
        when(inventory.list(tenant.toString(), "system:alerting", 500, null)).thenReturn(Mono.just(List.of(
                new CloudResourcePresence(UUID.randomUUID(), "web-2", "STOPPED", "PRESENT"), web1,
                new CloudResourcePresence(UUID.randomUUID(), "old", "TERMINATED", "GONE"), new CloudResourcePresence(UUID.randomUUID(), "odd", "RUNNING", "SOMETHING_NEW"))));

        StepVerifier.create(adapter.list(tenant, Source.CLOUD_INVENTORY).collectList()).assertNext(checks -> {
            assertEquals(4, checks.size());
            assertEquals(List.of("DOWN", "UP", "UP", "UNKNOWN"), checks.stream().map(c -> c.health()).toList());
            assertEquals("CLOUD_RESOURCE_MISSING", checks.get(0).error());
            assertNull(checks.get(1).error());
            assertNull(checks.get(3).error());
            assertEquals(missing, checks.get(0).id());
            assertEquals("web-1", checks.get(0).name());
            assertNull(checks.get(0).assetId());
            assertEquals(List.of("ACTIVE", "ACTIVE", "TERMINATED", "ACTIVE"), checks.stream().map(c -> c.status()).toList());
            assertTrue(checks.stream().allMatch(c -> c.source() == Source.CLOUD_INVENTORY));
        }).verifyComplete();
    }

    @Test
    @DisplayName("a purchase order is broken when late, kept when placed and not late or cancelled, and not judged in the other statuses; a cancelled one never opens an alert (ADR-043)")
    void purchaseOrders() {
        UUID late = UUID.randomUUID();
        PurchaseOrderStanding stuck = new PurchaseOrderStanding(late, "PO-1", "ORDERED", true);
        when(procurement.late(tenant.toString(), "system:alerting", 500)).thenReturn(Mono.just(List.of(stuck)));
        when(procurement.changed(tenant.toString(), "system:alerting", 30, 500)).thenReturn(Mono.just(List.of(
                new PurchaseOrderStanding(UUID.randomUUID(), "PO-2", "ORDERED", false), stuck, new PurchaseOrderStanding(UUID.randomUUID(), "PO-3", "CANCELLED", false),
                new PurchaseOrderStanding(UUID.randomUUID(), "PO-4", "DRAFT", false), new PurchaseOrderStanding(UUID.randomUUID(), "PO-5", "PENDING_APPROVAL", false))));

        StepVerifier.create(adapter.list(tenant, Source.PROCUREMENT).collectList()).assertNext(checks -> {
            assertEquals(5, checks.size());
            assertEquals(List.of("DOWN", "UP", "UP", "UNKNOWN", "UNKNOWN"), checks.stream().map(c -> c.health()).toList());
            assertEquals("PURCHASE_ORDER_LATE", checks.get(0).error());
            assertNull(checks.get(1).error());
            assertEquals(late, checks.get(0).id());
            assertEquals("PO-1", checks.get(0).name());
            assertNull(checks.get(0).assetId());
            assertEquals(List.of("ACTIVE", "ACTIVE", "CANCELLED", "ACTIVE", "ACTIVE"), checks.stream().map(c -> c.status()).toList());
            assertTrue(checks.stream().allMatch(c -> c.source() == Source.PROCUREMENT));
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
