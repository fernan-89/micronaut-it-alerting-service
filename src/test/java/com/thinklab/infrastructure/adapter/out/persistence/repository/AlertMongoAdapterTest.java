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
import com.thinklab.domain.exception.DuplicateAlertException;
import com.thinklab.domain.exception.InvalidAlertStatusException;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.repository.AlertRepository.Filter;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertDocument.AlertPersistenceMapper;
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
class AlertMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/alr_test";

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<AlertDocument> alerts;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        alerts = mock(MongoCollection.class);
        when(client.getDatabase("alr_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_alerting_db")).thenReturn(database);
        when(database.getCollection("alerts", AlertDocument.class)).thenReturn(alerts);
        when(alerts.withCodecRegistry(any())).thenReturn(alerts);
    }

    private Alert alert() {
        Alert alert = Alert.createNew(UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(), "Intranet", UUID.randomUUID(), "timeout", "system:alerting");
        alert.linkIncident(UUID.randomUUID(), "system:alerting");
        return alert;
    }

    private static MongoWriteException writeError(int code) {
        return new MongoWriteException(new WriteError(code, "write error", new BsonDocument()), new ServerAddress());
    }

    private void finds(Alert found) {
        FindPublisher<AlertDocument> publisher = mock(FindPublisher.class);
        when(alerts.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<AlertDocument> subscriber = invocation.getArgument(0);
            Flux.just(AlertPersistenceMapper.toDocument(found)).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    @Test
    @DisplayName("an alert survives the round trip, with its incident, its error and its audit trail")
    void roundTrip() {
        Alert alert = alert();
        alert.resolve("system:alerting");

        AlertDocument document = AlertPersistenceMapper.toDocument(alert);
        Alert back = AlertPersistenceMapper.toDomain(document);

        assertEquals(AlertStatus.RESOLVED, back.getStatus());
        assertEquals(alert.getIncidentId(), back.getIncidentId());
        assertEquals("timeout", back.getLastError());
        assertEquals("Intranet", back.getCheckName());
        assertEquals(alert.getAssetId(), back.getAssetId());
        assertEquals(alert.getResolvedAt(), back.getResolvedAt());
        assertEquals(3, back.getAuditTrail().size());
        assertNull(back.getAuditTrail().get(0).fromStatus());
        assertEquals(AlertStatus.OPEN, back.getAuditTrail().get(2).fromStatus());
        assertEquals("RESOLVED", document.getStatus());
        assertEquals(org, document.getOrganisationId());
        assertEquals(alert.getRuleId(), document.getRuleId());
        assertEquals(alert.getCheckId(), document.getCheckId());
        assertEquals(alert.getOpenedAt(), document.getOpenedAt());
        assertEquals(alert.getUpdatedAt(), document.getUpdatedAt());
        assertNull(document.getProblem());
    }

    @Test
    @DisplayName("the persistence mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<AlertPersistenceMapper> constructor = AlertPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create and the lookups are scoped to the organisation; the filters of the list travel together")
    void lookups() {
        Alert alert = alert();
        when(alerts.insertOne(any(AlertDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(alert);
        AlertMongoRepositoryAdapter adapter = new AlertMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.create(alert)).expectNext(alert).verifyComplete();
        StepVerifier.create(adapter.findById(alert.getId(), org)).assertNext(found -> assertEquals(alert.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(org, new Filter(AlertStatus.OPEN, alert.getCheckId()))).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findAll(org, new Filter(null, null))).expectNextCount(1).verifyComplete();
        StepVerifier.create(adapter.findOpen(org)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(alerts, times(4)).find(filter.capture());
        String full = filter.getAllValues().get(1).toString();
        assertTrue(full.contains("status") && full.contains("checkId") && full.contains("OPEN"));
        String bare = filter.getAllValues().get(2).toString();
        assertTrue(bare.contains("organisationId") && !bare.contains("checkId") && !bare.contains("status"));
        assertTrue(filter.getAllValues().get(3).toString().contains("OPEN"));
    }

    @Test
    @DisplayName("a second OPEN alert for the check is a domain conflict (the partial unique index); any other write error passes through; the URI may omit the database")
    void duplicates() {
        Alert alert = alert();
        when(alerts.insertOne(any(AlertDocument.class))).thenReturn(Mono.error(writeError(11000))).thenReturn(Mono.error(writeError(1)));
        AlertMongoRepositoryAdapter adapter = new AlertMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(alert)).expectError(DuplicateAlertException.class).verify();
        StepVerifier.create(adapter.create(alert)).expectError(MongoWriteException.class).verify();
    }

    @Test
    @DisplayName("save is guarded by the status loaded and pushes the audit entry; a lost race is a conflict")
    void save() {
        Alert alert = alert();
        when(alerts.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        AlertMongoRepositoryAdapter adapter = new AlertMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(alert, AlertStatus.OPEN, alert.getAuditTrail().get(1))).verifyComplete();
        StepVerifier.create(adapter.save(alert, AlertStatus.OPEN, alert.getAuditTrail().get(1))).expectError(InvalidAlertStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(alerts, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("OPEN") && guard.getAllValues().get(0).toString().contains("organisationId"));
        assertTrue(update.getAllValues().get(0).toString().contains("auditTrail") && update.getAllValues().get(0).toString().contains("incidentId"));
    }

    @Test
    @DisplayName("the tenants with an open alert are the distinct organisations of OPEN alerts")
    void openTenants() {
        DistinctPublisher<UUID> publisher = mock(DistinctPublisher.class);
        when(alerts.distinct(eq("organisationId"), any(Bson.class), eq(UUID.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<UUID> subscriber = invocation.getArgument(0);
            Flux.just(org).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());

        StepVerifier.create(new AlertMongoRepositoryAdapter(client, URI).openTenants()).expectNext(org).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(alerts).distinct(eq("organisationId"), filter.capture(), eq(UUID.class));
        assertTrue(filter.getValue().toString().contains("OPEN"));
    }
}
