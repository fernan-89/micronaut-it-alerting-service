package com.thinklab.infrastructure.adapter.out.integration.registry;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import com.thinklab.domain.port.PromisesPort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Adapter to the backup, patch, capacity, vendor and licence registries (ADR-037, ADR-038, ADR-039): the policies of a tenant, read through each registry's public route as a service
 * identity. The registries judge a promise when it is read, so alerting only turns that verdict into DOWN, UP or not judged, with an error from a
 * fixed vocabulary (never a value from the registry). A failure says which registry and which status and never repeats its answer.
 */
@Singleton
public class RegistryPromisesAdapter implements PromisesPort {

    private static final Logger log = LoggerFactory.getLogger(RegistryPromisesAdapter.class);
    static final String EXECUTOR = "system:alerting";
    static final String BROKEN = "DOWN";
    static final String KEPT = "UP";
    static final String NOT_JUDGED = "UNKNOWN";
    /** The most the inventory answers in one read (its own cap): of the missing ones, and of the most recently seen. */
    static final int INVENTORY_LIMIT = 500;
    /** The days within which a purchase order must have changed to be read for a verdict other than late: what was received or cancelled since. */
    static final int ORDERS_CHANGED_WITHIN_DAYS = 30;

    private final BackupApiClient backup;
    private final PatchApiClient patch;
    private final CapacityApiClient capacity;
    private final VendorApiClient vendor;
    private final LicenseApiClient license;
    private final DrApiClient dr;
    private final CloudInventoryApiClient inventory;
    private final ProcurementApiClient procurement;

    public RegistryPromisesAdapter(BackupApiClient backup, PatchApiClient patch, CapacityApiClient capacity, VendorApiClient vendor,
                                   LicenseApiClient license, DrApiClient dr, CloudInventoryApiClient inventory, ProcurementApiClient procurement) {
        this.backup = backup;
        this.patch = patch;
        this.capacity = capacity;
        this.vendor = vendor;
        this.license = license;
        this.dr = dr;
        this.inventory = inventory;
        this.procurement = procurement;
    }

    @Override
    public Flux<ObservedCheck> list(UUID organisationId, Source source) {
        String tenant = organisationId.toString();
        return switch (source) {
            case BACKUP -> read(source, backup.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromBackup);
            case PATCH -> read(source, patch.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromPatch);
            case CAPACITY -> read(source, capacity.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromCapacity);
            case VENDOR -> read(source, vendor.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromAgreement);
            case LICENSE -> read(source, license.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromLicense);
            case DR -> read(source, dr.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromRecoveryPlan);
            case CLOUD_INVENTORY -> read(source, resourcesOf(tenant), RegistryPromisesAdapter::fromCloudResource);
            case PROCUREMENT -> read(source, ordersOf(tenant), RegistryPromisesAdapter::fromPurchaseOrder);
            case HEALTH_CHECK -> throw new IllegalArgumentException("The health checks are read from the health monitor, not from a registry.");
        };
    }

    /**
     * The resources that are missing (asked of the inventory itself, since they are the least recently seen and would fall off a list of the most recent
     * ones) and then the most recently seen ones, which hold what has come back or been terminated since. A resource in both is read once.
     */
    private Mono<List<CloudResourcePresence>> resourcesOf(String tenant) {
        return inventory.list(tenant, EXECUTOR, INVENTORY_LIMIT, "MISSING")
                .zipWith(inventory.list(tenant, EXECUTOR, INVENTORY_LIMIT, null), (missing, recent) -> Stream.concat(missing.stream(), recent.stream())
                        .collect(Collectors.toMap(CloudResourcePresence::id, Function.identity(), (first, second) -> first, LinkedHashMap::new)).values().stream().toList());
    }

    /**
     * The purchase orders that are late (asked of the procurement registry itself, so they are never cut off a list of the most recent ones) and then the orders
     * that changed within the last {@value #ORDERS_CHANGED_WITHIN_DAYS} days, which hold what was received or cancelled since an alert opened. One in both is read once.
     */
    private Mono<List<PurchaseOrderStanding>> ordersOf(String tenant) {
        return procurement.late(tenant, EXECUTOR, INVENTORY_LIMIT)
                .zipWith(procurement.changed(tenant, EXECUTOR, ORDERS_CHANGED_WITHIN_DAYS, INVENTORY_LIMIT), (late, changed) -> Stream.concat(late.stream(), changed.stream())
                        .collect(Collectors.toMap(PurchaseOrderStanding::id, Function.identity(), (first, second) -> first, LinkedHashMap::new)).values().stream().toList());
    }

    private <T> Flux<ObservedCheck> read(Source source, Mono<List<T>> call, Function<T, ObservedCheck> told) {
        return call.flatMapMany(Flux::fromIterable).map(told)
                .onErrorMap(error -> {
                    String what = error instanceof HttpClientResponseException http ? " (HTTP " + http.getStatus().getCode() + ")" : "";
                    log.warn("[INTEGRATION] The {} registry could not be read{}: {}", source, what, error.getClass().getSimpleName());
                    return new UpstreamUnavailableException("The " + source.noun() + " registry could not be read" + what + ".");
                });
    }

    static ObservedCheck fromBackup(BackupPolicy policy) {
        boolean broken = "RPO_BREACHED".equals(policy.protection());
        boolean restoreOverdue = "OVERDUE".equals(policy.restoreTest());
        String health = broken || restoreOverdue ? BROKEN : "OK".equals(policy.protection()) ? KEPT : NOT_JUDGED;
        String error = broken ? "BACKUP_RPO_BREACHED" : restoreOverdue ? "BACKUP_RESTORE_TEST_OVERDUE" : null;
        return new ObservedCheck(policy.id(), policy.name(), policy.assetId(), health, policy.status(), error, Source.BACKUP);
    }

    static ObservedCheck fromPatch(PatchPolicy policy) {
        boolean broken = "OVERDUE".equals(policy.compliance());
        String health = broken ? BROKEN : "COMPLIANT".equals(policy.compliance()) ? KEPT : NOT_JUDGED;
        return new ObservedCheck(policy.id(), policy.name(), policy.assetId(), health, policy.status(), broken ? "PATCH_OVERDUE" : null, Source.PATCH);
    }

    static ObservedCheck fromCapacity(CapacityPolicy policy) {
        boolean broken = "CRITICAL".equals(policy.level());
        boolean kept = "OK".equals(policy.level()) || "WARNING".equals(policy.level());
        return new ObservedCheck(policy.id(), policy.name(), policy.assetId(), broken ? BROKEN : kept ? KEPT : NOT_JUDGED, policy.status(),
                broken ? "CAPACITY_CRITICAL" : null, Source.CAPACITY);
    }

    /**
     * An agreement of the vendor registry (ADR-038): one that is about to end or has ended is a promise broken (somebody must renew it or replace
     * it), one in force is kept, and one that has not started or is paused is not judged. Its name is the contract's own reference.
     */
    static ObservedCheck fromAgreement(VendorAgreement agreement) {
        boolean expiring = "EXPIRING".equals(agreement.standing());
        boolean expired = "EXPIRED".equals(agreement.standing());
        String health = expiring || expired ? BROKEN : "IN_FORCE".equals(agreement.standing()) ? KEPT : NOT_JUDGED;
        String error = expired ? "AGREEMENT_EXPIRED" : expiring ? "AGREEMENT_EXPIRING" : null;
        return new ObservedCheck(agreement.id(), agreement.reference(), agreement.assetId(), health, agreement.status(), error, Source.VENDOR);
    }

    /**
     * A licence of the licence registry (ADR-039): more seats in use than bought is a promise broken, OK or near the limit is kept (near is a
     * warning on the screen, not an incident), and no count yet or paused is not judged. Its name is the product.
     */
    static ObservedCheck fromLicense(LicenseSeats seats) {
        boolean over = "OVER_LIMIT".equals(seats.level());
        boolean kept = "OK".equals(seats.level()) || "NEAR_LIMIT".equals(seats.level());
        return new ObservedCheck(seats.id(), seats.product(), null, over ? BROKEN : kept ? KEPT : NOT_JUDGED, seats.status(), over ? "LICENSE_OVER_LIMIT" : null,
                Source.LICENSE);
    }

    @Serdeable
    @Introspected
    public record BackupPolicy(UUID id, String name, UUID assetId, String status, String protection, @Nullable String restoreTest) {}

    @Serdeable
    @Introspected
    public record PatchPolicy(UUID id, String name, UUID assetId, String status, String compliance) {}

    @Serdeable
    @Introspected
    public record CapacityPolicy(UUID id, String name, UUID assetId, String status, String level) {}

    @Serdeable
    @Introspected
    public record VendorAgreement(UUID id, String reference, @Nullable UUID assetId, String status, String standing) {}

    /**
     * A recovery plan of the DR registry (ADR-041): one whose latest exercise was partial or failed, whose last measured pass missed the recovery time or
     * point, or whose next exercise is overdue is a promise broken; a ready one is kept; one never exercised yet (still inside its first interval) or paused is
     * not judged. A recovery plan covers one asset.
     */
    static ObservedCheck fromRecoveryPlan(RecoveryPlanReadiness plan) {
        String error = switch (plan.readiness()) {
            case "LAST_EXERCISE_FAILED" -> "DR_EXERCISE_FAILED";
            case "OBJECTIVE_MISSED" -> "DR_OBJECTIVE_MISSED";
            case "TEST_OVERDUE" -> "DR_TEST_OVERDUE";
            default -> null;
        };
        return new ObservedCheck(plan.id(), plan.name(), plan.assetId(), error != null ? BROKEN : "READY".equals(plan.readiness()) ? KEPT : NOT_JUDGED, plan.status(), error,
                Source.DR);
    }

    /**
     * A resource of the cloud inventory (ADR-042): one the discovery tool has not seen for too long is a promise broken (nothing may vanish unnoticed),
     * one seen lately is kept, and one the tool reported terminated is kept too: it was removed on purpose, so an alert about it when it went missing is
     * resolved. A terminated resource is not ACTIVE, so it never opens one. Only a fixed code is kept; the resource has no asset in this registry.
     */
    static ObservedCheck fromCloudResource(CloudResourcePresence resource) {
        boolean missing = "MISSING".equals(resource.presence());
        boolean kept = "PRESENT".equals(resource.presence()) || "GONE".equals(resource.presence());
        String status = "TERMINATED".equals(resource.state()) ? "TERMINATED" : "ACTIVE";
        return new ObservedCheck(resource.id(), resource.name(), null, missing ? BROKEN : kept ? KEPT : NOT_JUDGED, status, missing ? "CLOUD_RESOURCE_MISSING" : null,
                Source.CLOUD_INVENTORY);
    }

    /**
     * A purchase order of the procurement registry (ADR-043): one that is placed, not completely received and past its expected day is a promise broken (the
     * vendor owes goods); one placed and not late is kept, and so is one that was cancelled (nothing is owed any more: an alert about it is resolved, and, being
     * cancelled, it never opens one). A draft, one waiting for approval, approved or rejected is not judged. Only a fixed code is kept; the order has no asset.
     */
    static ObservedCheck fromPurchaseOrder(PurchaseOrderStanding order) {
        boolean late = order.late();
        boolean kept = "CANCELLED".equals(order.status()) || ("ORDERED".equals(order.status()) && !late);
        String status = "CANCELLED".equals(order.status()) ? "CANCELLED" : "ACTIVE";
        return new ObservedCheck(order.id(), order.reference(), null, late ? BROKEN : kept ? KEPT : NOT_JUDGED, status, late ? "PURCHASE_ORDER_LATE" : null, Source.PROCUREMENT);
    }

    @Serdeable
    @Introspected
    public record PurchaseOrderStanding(UUID id, String reference, String status, boolean late) {}

    @Serdeable
    @Introspected
    public record CloudResourcePresence(UUID id, String name, String state, String presence) {}

    @Serdeable
    @Introspected
    public record RecoveryPlanReadiness(UUID id, String name, UUID assetId, String status, String readiness) {}

    @Serdeable
    @Introspected
    public record LicenseSeats(UUID id, String product, String status, String level) {}
}

@Client(id = "backup-registry-service", path = "/it-backup-registry/v1")
interface BackupApiClient {

    @Get("/retrieve")
    Mono<List<RegistryPromisesAdapter.BackupPolicy>> list(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);
}

@Client(id = "patch-registry-service", path = "/it-patch-registry/v1")
interface PatchApiClient {

    @Get("/retrieve")
    Mono<List<RegistryPromisesAdapter.PatchPolicy>> list(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);
}

@Client(id = "capacity-registry-service", path = "/it-capacity-registry/v1")
interface CapacityApiClient {

    @Get("/retrieve")
    Mono<List<RegistryPromisesAdapter.CapacityPolicy>> list(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);
}

@Client(id = "vendor-registry-service", path = "/it-vendor-registry/v1")
interface VendorApiClient {

    @Get("/agreement/retrieve")
    Mono<List<RegistryPromisesAdapter.VendorAgreement>> list(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);
}

@Client(id = "license-registry-service", path = "/it-license-registry/v1")
interface LicenseApiClient {

    @Get("/retrieve")
    Mono<List<RegistryPromisesAdapter.LicenseSeats>> list(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);
}

@Client(id = "dr-registry-service", path = "/it-dr-registry/v1")
interface DrApiClient {

    @Get("/retrieve")
    Mono<List<RegistryPromisesAdapter.RecoveryPlanReadiness>> list(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor);
}

@Client(id = "cloud-inventory-service", path = "/it-cloud-inventory/v1")
interface CloudInventoryApiClient {

    @Get("/retrieve")
    Mono<List<RegistryPromisesAdapter.CloudResourcePresence>> list(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor,
                                                                   @QueryValue("limit") int limit, @QueryValue("presence") @Nullable String presence);
}

@Client(id = "procurement-service", path = "/it-procurement/v1")
interface ProcurementApiClient {

    @Get("/retrieve?late=true")
    Mono<List<RegistryPromisesAdapter.PurchaseOrderStanding>> late(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor, @QueryValue("limit") int limit);

    @Get("/retrieve")
    Mono<List<RegistryPromisesAdapter.PurchaseOrderStanding>> changed(@Header("X-Tenant-Id") String tenant, @Header("X-Executor") String executor,
                                                                      @QueryValue("changedWithinDays") int days, @QueryValue("limit") int limit);
}
