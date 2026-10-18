package com.thinklab.infrastructure.adapter.out.integration.registry;

import com.thinklab.domain.exception.UpstreamUnavailableException;
import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import com.thinklab.domain.port.PromisesPort;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Header;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Adapter to the backup, patch and capacity registries (ADR-037): the policies of a tenant, read through each registry's public route as a service
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

    private final BackupApiClient backup;
    private final PatchApiClient patch;
    private final CapacityApiClient capacity;

    public RegistryPromisesAdapter(BackupApiClient backup, PatchApiClient patch, CapacityApiClient capacity) {
        this.backup = backup;
        this.patch = patch;
        this.capacity = capacity;
    }

    @Override
    public Flux<ObservedCheck> list(UUID organisationId, Source source) {
        String tenant = organisationId.toString();
        return switch (source) {
            case BACKUP -> read(source, backup.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromBackup);
            case PATCH -> read(source, patch.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromPatch);
            case CAPACITY -> read(source, capacity.list(tenant, EXECUTOR), RegistryPromisesAdapter::fromCapacity);
            case HEALTH_CHECK -> throw new IllegalArgumentException("The health checks are read from the health monitor, not from a registry.");
        };
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

    @Serdeable
    @Introspected
    public record BackupPolicy(UUID id, String name, UUID assetId, String status, String protection, @Nullable String restoreTest) {}

    @Serdeable
    @Introspected
    public record PatchPolicy(UUID id, String name, UUID assetId, String status, String compliance) {}

    @Serdeable
    @Introspected
    public record CapacityPolicy(UUID id, String name, UUID assetId, String status, String level) {}
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
