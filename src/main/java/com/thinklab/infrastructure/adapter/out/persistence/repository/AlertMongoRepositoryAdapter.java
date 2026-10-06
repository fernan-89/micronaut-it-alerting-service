package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateAlertException;
import com.thinklab.domain.exception.InvalidAlertStatusException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertAuditEntry;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertDocument.AlertPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertDocument.AuditEntryDocument;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the Alert aggregate, raw reactive-streams driver. Every change is one atomic update guarded by
 * the status loaded (ADR-033) that also appends the audit entry; the partial unique index on {@code (organisationId, checkId)} for OPEN
 * alerts is what makes "one alert per outage" true with several instances (ADR-030).
 */
@Singleton
public class AlertMongoRepositoryAdapter implements AlertRepository {

    private static final Logger log = LoggerFactory.getLogger(AlertMongoRepositoryAdapter.class);
    static final String COLLECTION_NAME = "alerts";
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public AlertMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : AlertRuleMongoRepositoryAdapter.DEFAULT_DATABASE;
    }

    private MongoCollection<AlertDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, AlertDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<Alert> create(Alert alert) {
        log.debug("[PERSISTENCE] Monolithic create for Alert Aggregate: {}", alert.getId());

        return Mono.from(getCollection().insertOne(AlertPersistenceMapper.toDocument(alert)))
                .map(result -> alert)
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == AlertRuleMongoRepositoryAdapter.DUPLICATE_KEY
                        ? new DuplicateAlertException("An alert is already open for that check.") : error);
    }

    @Override
    public Mono<Alert> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(AlertPersistenceMapper::toDomain);
    }

    @Override
    public Flux<Alert> findAll(UUID organisationId, Filter filter) {
        List<Bson> filters = new ArrayList<>();
        filters.add(Filters.eq(FIELD_ORGANISATION, organisationId));
        if (filter.status() != null) {
            filters.add(Filters.eq(FIELD_STATUS, filter.status().name()));
        }
        if (filter.checkId() != null) {
            filters.add(Filters.eq("checkId", filter.checkId()));
        }
        return Flux.from(getCollection().find(Filters.and(filters)).sort(Sorts.descending("openedAt"))).map(AlertPersistenceMapper::toDomain);
    }

    @Override
    public Flux<Alert> findOpen(UUID organisationId) {
        return Flux.from(getCollection().find(Filters.and(Filters.eq(FIELD_ORGANISATION, organisationId), Filters.eq(FIELD_STATUS, AlertStatus.OPEN.name()))))
                .map(AlertPersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(Alert alert, AlertStatus expectedStatus, AlertAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, alert.getId()), Filters.eq(FIELD_ORGANISATION, alert.getOrganisationId()), Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set(FIELD_STATUS, alert.getStatus().name()),
                Updates.set("resolvedAt", alert.getResolvedAt()),
                Updates.set("incidentId", alert.getIncidentId()),
                Updates.set("problem", alert.getProblem()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidAlertStatusException("Alert was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty());
    }

    @Override
    public Flux<UUID> openTenants() {
        return Flux.from(getCollection().distinct(FIELD_ORGANISATION, Filters.eq(FIELD_STATUS, AlertStatus.OPEN.name()), UUID.class));
    }
}
