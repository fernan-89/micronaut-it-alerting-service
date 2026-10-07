package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.client.result.InsertOneResult;
import com.mongodb.client.result.UpdateResult;
import com.mongodb.reactivestreams.client.FindPublisher;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import com.thinklab.domain.exception.InvalidMaintenanceWindowStatusException;
import com.thinklab.domain.model.MaintenanceWindow;
import com.thinklab.domain.model.MaintenanceWindow.WindowStatus;
import com.thinklab.infrastructure.adapter.out.persistence.entity.MaintenanceWindowDocument;
import com.thinklab.infrastructure.adapter.out.persistence.entity.MaintenanceWindowDocument.MaintenanceWindowPersistenceMapper;
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
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class MaintenanceWindowMongoAdapterTest {

    private static final String URI = "mongodb://localhost:27017/alr_test";
    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final UUID org = UUID.randomUUID();
    private MongoClient client;
    private MongoCollection<MaintenanceWindowDocument> windows;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        windows = mock(MongoCollection.class);
        when(client.getDatabase("alr_test")).thenReturn(database);
        when(client.getDatabase("thinklab_it_alerting_db")).thenReturn(database);
        when(database.getCollection("maintenance_windows", MaintenanceWindowDocument.class)).thenReturn(windows);
        when(windows.withCodecRegistry(any())).thenReturn(windows);
    }

    private MaintenanceWindow window(UUID check) {
        return MaintenanceWindow.createNew(UUID.randomUUID(), org, "Patching", check, NOW.minus(Duration.ofMinutes(5)), NOW.plus(Duration.ofHours(1)), NOW, "op");
    }

    private void finds(MaintenanceWindow... found) {
        FindPublisher<MaintenanceWindowDocument> publisher = mock(FindPublisher.class);
        when(windows.find(any(Bson.class))).thenReturn(publisher);
        when(publisher.first()).thenReturn(publisher);
        when(publisher.sort(any(Bson.class))).thenReturn(publisher);
        doAnswer(invocation -> {
            org.reactivestreams.Subscriber<MaintenanceWindowDocument> subscriber = invocation.getArgument(0);
            Flux.fromArray(found).map(MaintenanceWindowPersistenceMapper::toDocument).subscribe(subscriber);
            return null;
        }).when(publisher).subscribe(any());
    }

    @Test
    @DisplayName("a window survives the round trip, whole-tenant or for one check, with its audit trail (the planning has no previous status)")
    void roundTrip() {
        UUID check = UUID.randomUUID();
        MaintenanceWindow window = window(check);
        window.cancel("op");

        MaintenanceWindowDocument document = MaintenanceWindowPersistenceMapper.toDocument(window);
        MaintenanceWindow back = MaintenanceWindowPersistenceMapper.toDomain(document);

        assertEquals(window.getId(), back.getId());
        assertEquals(org, back.getOrganisationId());
        assertEquals("Patching", back.getName());
        assertEquals(check, back.getCheckId());
        assertEquals(window.getStartsAt(), back.getStartsAt());
        assertEquals(window.getEndsAt(), back.getEndsAt());
        assertEquals(WindowStatus.CANCELLED, back.getStatus());
        assertEquals(window.getCreatedAt(), back.getCreatedAt());
        assertEquals(window.getUpdatedAt(), back.getUpdatedAt());
        assertEquals(2, back.getAuditTrail().size());
        assertNull(back.getAuditTrail().get(0).fromStatus());
        assertEquals(WindowStatus.ACTIVE, back.getAuditTrail().get(1).fromStatus());
        assertEquals("CANCELLED", document.getStatus());
        assertEquals(check, document.getCheckId());
        assertEquals(window.getId(), document.getId());
        assertEquals("Patching", document.getName());
        assertEquals(2, document.getAuditTrail().size());

        assertNull(MaintenanceWindowPersistenceMapper.toDomain(MaintenanceWindowPersistenceMapper.toDocument(window(null))).getCheckId());
    }

    @Test
    @DisplayName("the persistence mapper is a utility class")
    void mapperIsUtility() throws ReflectiveOperationException {
        Constructor<MaintenanceWindowPersistenceMapper> constructor = MaintenanceWindowPersistenceMapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }

    @Test
    @DisplayName("create, read one and list are scoped to the organisation; the URI may omit the database")
    void lookups() {
        MaintenanceWindow window = window(null);
        when(windows.insertOne(any(MaintenanceWindowDocument.class))).thenReturn(Mono.just(InsertOneResult.acknowledged(new BsonObjectId(new ObjectId()))));
        finds(window);
        MaintenanceWindowMongoRepositoryAdapter adapter = new MaintenanceWindowMongoRepositoryAdapter(client, "mongodb://localhost:27017");

        StepVerifier.create(adapter.create(window)).expectNext(window).verifyComplete();
        StepVerifier.create(adapter.findById(window.getId(), org)).assertNext(found -> assertEquals(window.getId(), found.getId())).verifyComplete();
        StepVerifier.create(adapter.findAll(org)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(windows, times(2)).find(filter.capture());
        assertTrue(filter.getAllValues().get(0).toString().contains("organisationId") && filter.getAllValues().get(0).toString().contains("_id"));
        assertTrue(filter.getAllValues().get(1).toString().contains("organisationId"));
    }

    @Test
    @DisplayName("the current windows are the tenant's ACTIVE ones that have not ended")
    void current() {
        MaintenanceWindow window = window(null);
        finds(window);

        StepVerifier.create(new MaintenanceWindowMongoRepositoryAdapter(client, URI).findCurrent(org, NOW)).expectNextCount(1).verifyComplete();

        ArgumentCaptor<Bson> filter = ArgumentCaptor.forClass(Bson.class);
        verify(windows).find(filter.capture());
        String text = filter.getValue().toString();
        assertTrue(text.contains("ACTIVE") && text.contains("endsAt") && text.contains("organisationId"));
    }

    @Test
    @DisplayName("save is guarded by the status loaded and pushes the audit entry; a lost race is a conflict")
    void save() {
        MaintenanceWindow window = window(null);
        var entry = window.cancel("op");
        when(windows.updateOne(any(Bson.class), any(Bson.class)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(1, 1L, null)))
                .thenReturn(Mono.just(UpdateResult.acknowledged(0, 0L, null)));
        MaintenanceWindowMongoRepositoryAdapter adapter = new MaintenanceWindowMongoRepositoryAdapter(client, URI);

        StepVerifier.create(adapter.save(window, WindowStatus.ACTIVE, entry)).verifyComplete();
        StepVerifier.create(adapter.save(window, WindowStatus.ACTIVE, entry)).expectError(InvalidMaintenanceWindowStatusException.class).verify();

        ArgumentCaptor<Bson> guard = ArgumentCaptor.forClass(Bson.class);
        ArgumentCaptor<Bson> update = ArgumentCaptor.forClass(Bson.class);
        verify(windows, times(2)).updateOne(guard.capture(), update.capture());
        assertTrue(guard.getAllValues().get(0).toString().contains("ACTIVE") && guard.getAllValues().get(0).toString().contains("organisationId"));
        assertTrue(update.getAllValues().get(0).toString().contains("CANCELLED") && update.getAllValues().get(0).toString().contains("auditTrail"));
    }

    @Test
    @DisplayName("the adapter refuses a missing URI")
    void nullUri() {
        assertThrows(NullPointerException.class, () -> new MaintenanceWindowMongoRepositoryAdapter(client, null));
    }
}
