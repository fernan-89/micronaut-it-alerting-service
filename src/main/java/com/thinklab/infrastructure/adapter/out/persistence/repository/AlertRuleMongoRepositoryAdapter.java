package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.thinklab.domain.exception.DuplicateAlertRuleException;
import com.thinklab.domain.exception.InvalidAlertRuleStatusException;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleAuditEntry;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import com.thinklab.domain.repository.AlertRuleRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertRuleDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertRuleDocument.AlertRulePersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertRuleDocument.AuditEntryDocument;
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
import java.util.Objects;
import java.util.UUID;

/**
 * MongoDB Reactive Repository Adapter for the AlertRule aggregate, raw reactive-streams driver. Every change is one atomic update guarded by
 * the status loaded (ADR-033) that also appends the audit entry; a name unique per organisation is the atomic backstop of a duplicate.
 */
@Singleton
public class AlertRuleMongoRepositoryAdapter implements AlertRuleRepository {

    private static final Logger log = LoggerFactory.getLogger(AlertRuleMongoRepositoryAdapter.class);
    static final String DEFAULT_DATABASE = "thinklab_it_alerting_db";
    static final String COLLECTION_NAME = "alert_rules";
    static final int DUPLICATE_KEY = 11000;
    private static final String FIELD_ID = "_id";
    private static final String FIELD_ORGANISATION = "organisationId";
    private static final String FIELD_STATUS = "status";

    private static final CodecRegistry POJO_CODEC_REGISTRY = CodecRegistries.fromRegistries(
            MongoClientSettings.getDefaultCodecRegistry(),
            CodecRegistries.fromProviders(PojoCodecProvider.builder().automatic(true).build())
    );

    private final MongoClient mongoClient;
    private final String database;

    public AlertRuleMongoRepositoryAdapter(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this.mongoClient = mongoClient;
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : DEFAULT_DATABASE;
    }

    private MongoCollection<AlertRuleDocument> getCollection() {
        return mongoClient.getDatabase(database).getCollection(COLLECTION_NAME, AlertRuleDocument.class).withCodecRegistry(POJO_CODEC_REGISTRY);
    }

    @Override
    public Mono<AlertRule> create(AlertRule rule) {
        log.debug("[PERSISTENCE] Monolithic create for AlertRule Aggregate: {}", rule.getId());

        return Mono.from(getCollection().insertOne(AlertRulePersistenceMapper.toDocument(rule)))
                .map(result -> rule)
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == DUPLICATE_KEY
                        ? new DuplicateAlertRuleException("An alert rule named '" + rule.getName() + "' already exists in this organisation.") : error);
    }

    @Override
    public Mono<AlertRule> findById(UUID id, UUID organisationId) {
        return Mono.from(getCollection().find(Filters.and(Filters.eq(FIELD_ID, id), Filters.eq(FIELD_ORGANISATION, organisationId))).first())
                .map(AlertRulePersistenceMapper::toDomain);
    }

    @Override
    public Flux<AlertRule> findAll(UUID organisationId) {
        return Flux.from(getCollection().find(Filters.eq(FIELD_ORGANISATION, organisationId)).sort(Sorts.ascending("createdAt"))).map(AlertRulePersistenceMapper::toDomain);
    }

    @Override
    public Mono<Void> save(AlertRule rule, RuleStatus expectedStatus, RuleAuditEntry auditEntry) {
        Bson guard = Filters.and(Filters.eq(FIELD_ID, rule.getId()), Filters.eq(FIELD_ORGANISATION, rule.getOrganisationId()), Filters.eq(FIELD_STATUS, expectedStatus.name()));
        Bson update = Updates.combine(
                Updates.set("name", rule.getName()),
                Updates.set("checkId", rule.getCheckId()),
                Updates.set("impact", rule.getImpact().name()),
                Updates.set("urgency", rule.getUrgency().name()),
                Updates.set("requesterId", rule.getRequesterId()),
                Updates.set("notifyTarget", rule.getOptions().notifyTarget()),
                Updates.set("escalateTarget", rule.getOptions().escalateTarget()),
                Updates.set("escalateAfterMinutes", rule.getOptions().escalateAfterMinutes()),
                Updates.set("reopenWithinMinutes", rule.getOptions().reopenWithinMinutes()),
                Updates.set(FIELD_STATUS, rule.getStatus().name()),
                Updates.set("updatedAt", Instant.now()),
                Updates.push("auditTrail", AuditEntryDocument.fromDomain(auditEntry))
        );
        return Mono.from(getCollection().updateOne(guard, update))
                .flatMap(result -> result.getMatchedCount() == 0
                        ? Mono.error(new InvalidAlertRuleStatusException("AlertRule was changed by someone else while this change was being recorded; read it again and retry."))
                        : Mono.<Void>empty())
                .onErrorMap(MongoWriteException.class, error -> error.getError().getCode() == DUPLICATE_KEY
                        ? new DuplicateAlertRuleException("An alert rule named '" + rule.getName() + "' already exists in this organisation.") : error);
    }

    @Override
    public Flux<UUID> activeTenants() {
        return Flux.from(getCollection().distinct(FIELD_ORGANISATION, Filters.eq(FIELD_STATUS, RuleStatus.ACTIVE.name()), UUID.class));
    }
}
