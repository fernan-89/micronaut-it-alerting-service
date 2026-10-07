package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.ConnectionString;
import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.context.event.StartupEvent;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Objects;

/**
 * Creates the indexes at startup. The UNIQUE ones are the atomic backstops (ADR-033): a rule name per organisation, and, partial on
 * {@code status = OPEN}, one alert per check (the rule of ADR-030 that holds with several instances). The others serve the real queries:
 * the tenants with an active rule, the tenants with an open alert, and the alert list. Fail-open: {@code createIndex} is idempotent, a
 * failure is logged and the application still starts. Turn it off with {@code thinklab.mongo.create-indexes=false}.
 */
@Singleton
@Requires(property = "thinklab.mongo.create-indexes", notEquals = "false")
public class AlertingIndexInitializer implements ApplicationEventListener<StartupEvent> {

    static final String RULE_NAME_INDEX = "organisationId_1_name_1";
    static final String RULE_STATUS_INDEX = "status_1";
    static final String OPEN_ALERT_INDEX = "organisationId_1_checkId_1_open";
    static final String ALERT_LIST_INDEX = "organisationId_1_status_1_openedAt_-1";
    static final String ALERT_STATUS_INDEX = "status_1";
    static final String ALERT_RESOLVED_INDEX = "organisationId_1_status_1_resolvedAt_-1";
    static final String WINDOW_CURRENT_INDEX = "organisationId_1_status_1_endsAt_1";

    private static final Logger log = LoggerFactory.getLogger(AlertingIndexInitializer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MongoClient mongoClient;
    private final String database;
    private final Duration timeout;

    @Inject
    public AlertingIndexInitializer(MongoClient mongoClient, @Property(name = "mongodb.uri") String mongoUri) {
        this(mongoClient, mongoUri, TIMEOUT);
    }

    AlertingIndexInitializer(MongoClient mongoClient, String mongoUri, Duration timeout) {
        this.mongoClient = Objects.requireNonNull(mongoClient, "Infrastructure constraint violated: MongoClient cannot be null.");
        String configured = new ConnectionString(Objects.requireNonNull(mongoUri, "mongodb.uri cannot be null.")).getDatabase();
        this.database = configured != null ? configured : AlertRuleMongoRepositoryAdapter.DEFAULT_DATABASE;
        this.timeout = timeout;
    }

    @Override
    public void onApplicationEvent(StartupEvent event) {
        Objects.requireNonNull(event, "Application constraint violated: StartupEvent cannot be null.");
        String rules = AlertRuleMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(rules, RULE_NAME_INDEX, new Document("organisationId", 1).append("name", 1), new IndexOptions().unique(true));
        ensureIndex(rules, RULE_STATUS_INDEX, new Document("status", 1), new IndexOptions());
        String alerts = AlertMongoRepositoryAdapter.COLLECTION_NAME;
        ensureIndex(alerts, OPEN_ALERT_INDEX, new Document("organisationId", 1).append("checkId", 1),
                new IndexOptions().unique(true).partialFilterExpression(new Document("status", "OPEN")));
        ensureIndex(alerts, ALERT_LIST_INDEX, new Document("organisationId", 1).append("status", 1).append("openedAt", -1), new IndexOptions());
        ensureIndex(alerts, ALERT_STATUS_INDEX, new Document("status", 1), new IndexOptions());
        ensureIndex(alerts, ALERT_RESOLVED_INDEX, new Document("organisationId", 1).append("status", 1).append("resolvedAt", -1), new IndexOptions());
        ensureIndex(MaintenanceWindowMongoRepositoryAdapter.COLLECTION_NAME, WINDOW_CURRENT_INDEX, new Document("organisationId", 1).append("status", 1).append("endsAt", 1), new IndexOptions());
    }

    private void ensureIndex(String collection, String indexName, Document keys, IndexOptions options) {
        try {
            Mono.from(mongoClient.getDatabase(database).getCollection(collection).createIndex(keys, options.name(indexName))).block(timeout);
            log.info("[MONGO_INDEXES] Ensured index [{}] on [{}.{}]", indexName, database, collection);
        } catch (MongoTimeoutException e) {
            log.error("[MONGO_INDEXES] MongoDB unreachable; index [{}] was not created. Reason: {}", indexName, e.getMessage());
        } catch (RuntimeException e) {
            log.error("[MONGO_INDEXES] Could not create index [{}] on [{}.{}]: {}", indexName, database, collection, e.getMessage());
        }
    }
}
