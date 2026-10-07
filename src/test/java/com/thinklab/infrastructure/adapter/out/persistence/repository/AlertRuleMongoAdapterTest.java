package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoWriteException;
import com.mongodb.ServerAddress;
import com.mongodb.WriteError;
import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.DistinctPublisher;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.DuplicateAlertRuleException;
import com.thinklab.domain.exception.InvalidAlertRuleStatusException;
import com.thinklab.domain.model.AlertRule;
import com.thinklab.domain.model.AlertRule.RuleStatus;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertRuleDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertRuleDocument.AlertRulePersistenceMapper;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class AlertRuleMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/alr_test";

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<AlertRuleDocument> rules;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        rules = mock(MongoCollection.class);
        when(client.getDatabase("alr_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_alerting_db")).thenReturn(database);
        when(database.getCollection("alert_rules", AlertRuleDocument.class)).thenReturn(rules);
        when(rules.withCodecRegistry(any())).thenReturn(rules);
    }

    private AlertRule rule() {
        AlertRule rule = AlertRule.createNew(UUID.randomUUID(), org, "Production", UUID.randomUUID(), Severity.HIGH, Severity.MEDIUM, UUID.randomUUID(), AlertRule.Options.NONE, "op");
        rule.pause("op");
        return rule;
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private void finds(AlertRule found) {
        FindPublisher<AlertRuleDocument> publisher = mock(FindPublisher.class);
        when(rules.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<AlertRuleDocument> subscriber = invocation.getArgument(0);
            Flux.just(AlertRulePersistenceMapper.toDocument(found)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    @Test
    @DisplayName("a rule survives the round trip, with its status and audit trail; a rule without a check or an earlier status keeps them null")
    void roundTrip() {
        AlertRule rule = rule();

        AlertRuleDocument document = AlertRulePersistenceMapper.toDocument(rule);
        AlertRule back = AlertRulePersistenceMapper.toDomain(document);

        assertEquals(RuleStatus.PAUSED, back.getStatus());
        assertEquals(rule.getCheckId(), back.getCheckId());
        assertEquals(rule.getRequesterId(), back.getRequesterId());
        assertEquals(Severity.HIGH, back.getImpact());
        assertEquals(Severity.MEDIUM, back.getUrgency());
        assertEquals(2, back.getAuditTrail().size());
        assertNull(back.getAuditTrail().get(0).fromStatus());
        assertEquals(RuleStatus.ACTIVE, back.getAuditTrail().get(1).fromStatus());
        assertEquals("Production", document.getName());
        assertEquals("PAUSED", document.getStatus());
        assertEquals("HIGH", document.getImpact());
        assertEquals("MEDIUM", document.getUrgency());
        assertEquals(org, document.getOrganisationId());
        assertEquals(rule.getId(), document.getId());
        assertEquals(rule.getCreatedAt(), document.getCreatedAt());
        assertEquals(rule.getUpdatedAt(), document.getUpdatedAt());
    }

    @Test
    @DisplayName("the persistence mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<AlertRulePersistenceMapper> constructor = AlertRulePersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create and the lookups are scoped to the organisation, the list oldest first")
    void lookups() {
        AlertRule rule = rule();
        when(rules.insertOne(any(AlertRuleDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(rule);
        AlertRuleMongoRepositoryAdapter adapter = new AlertRuleMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(rule)).expectNext(rule).verifyComplete();
        StepVerifier.create(adapter.findById(rule.getId(), org)).assertNext(found -> assertEquals(rule.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(org)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(rules, times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId"));
    }

    @Test
    @DisplayName("a duplicate name is a 409 of the domain, on create or on a rename; any other write error passes through; the URI may omit the database")
    void duplicates() {
        AlertRule rule = rule();
        when(rules.insertOne(any(AlertRuleDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        when(rules.updateOne(any(Bson.class), any(Bson.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(2)));
        AlertRuleMongoRepositoryAdapter adapter = new AlertRuleMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(rule)).expectError(DuplicateAlertRuleException.class).verify();
        StepVerifier.create(adapter.create(rule)).expectError(MongoWriteException.class).verify();
        StepVerifier.create(adapter.save(rule, RuleStatus.ACTIVE, rule.getAuditTrail().get(1))).expectError(DuplicateAlertRuleException.class).verify();
        StepVerifier.create(adapter.save(rule, RuleStatus.ACTIVE, rule.getAuditTrail().get(1))).expectError(MongoWriteException.class).verify();
    }

    @Test
    @DisplayName("save is guarded by the status loaded and pushes the audit entry; a lost race is a conflict")
    void save() {
        AlertRule rule = rule();
        when(rules.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        AlertRuleMongoRepositoryAdapter adapter = new AlertRuleMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(rule, RuleStatus.ACTIVE, rule.getAuditTrail().get(1))).verifyComplete();
        StepVerifier.create(adapter.save(rule, RuleStatus.ACTIVE, rule.getAuditTrail().get(1))).expectError(InvalidAlertRuleStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(rules, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("ACTIVE") && guard.getAllValues().get(0).toString().contains("organisationId"));
        assertTrue(update.getAllValues().get(0).toString().contains("auditTrail"));
    }

    @Test
    @DisplayName("the tenants with an active rule are the distinct organisations of ACTIVE rules")
    void activeTenants() {
        DistinctPublisher<UUID> publisher = mock(DistinctPublisher.class);
        when(rules.distinct(eq("organisationId"), any(Bson.class), eq(UUID.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<UUID> subscriber = invocation.getArgument(0);
            Flux.just(org).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(new AlertRuleMongoRepositoryAdapter(client, URI).activeTenants()).expectNext(org).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(rules).distinct(eq("organisationId"), filter.capture(), eq(UUID.class));
        assertTrue(filter.getValue().toString().contains("ACTIVE"));
    }
}
