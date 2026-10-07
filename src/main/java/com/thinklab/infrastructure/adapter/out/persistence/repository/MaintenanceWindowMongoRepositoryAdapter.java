package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.InvalidMaintenanceWindowStatusException;
import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.model.MaintenanceWindow.WindowAuditEntry;
import com.thinklab.domain.model.MaintenanceWindow.WindowStatus;
import com.thinklab.domain.repository.MaintenanceWindowRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.MaintenanceWindowDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.MaintenanceWindowDocument.AuditEntryDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.MaintenanceWindowDocument.MaintenanceWindowPersistenceMapper;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the MaintenanceWindow aggregate, raw reactive-streams driver. Every change is one atomic update
 * guarded by the status loaded (ADR-033) that also appends the audit entry.
 */
@Singleton
public class MaintenanceWindowMongoRepositoryAdapter implements MaintenanceWindowRepository {

    static final String COLLECTION_NAME = "maintenance_windows";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public MaintenanceWindowMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : AlertRuleMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<MaintenanceWindowDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, MaintenanceWindowDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<MaintenanceWindow> create(MaintenanceWindow window) {
        return Mono.from(getCollection().insertOne(MaintenanceWindowPersistenceMapper.toDocument(window))).map(result -> window);
    }

    @Override
    public Mono<MaintenanceWindow> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(MaintenanceWindowPersistenceMapper::toDomain);
    }

    @Override
    public Flux<MaintenanceWindow> findAll(UUID organisationId) {
        return Flux.from(getCollection().find(Filters.eq(FIELD_ORGANISATION, organisationId)).sort(Sorts.descending("startsAt")))
                .map(MaintenanceWindowPersistenceMapper::toDomain);
    }

    @Override
    public Flux<MaintenanceWindow> findCurrent(UUID organisationId, Instant now) {
        return Flux.from(getCollection().find(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_STATUS, WindowStatus.ACTIVE.name()),
                        Filters.gt("endsAt", now))))
                .map(MaintenanceWindowPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(MaintenanceWindow window, WindowStatus expectedStatus, WindowAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, window.getId()), Filters.eq(FIELD_ORGANISATION, window.getOrganisationId()), Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set(FIELD_STATUS, window.getStatus().name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidMaintenanceWindowStatusException("MaintenanceWindow was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty());
    }
}
