package com.thinklab.infrastructure.adapter.out.persistence.repository;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.reactivestreams.client.MongoClient;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.micronaut.context.event.StartupEvent;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class AlertingIndexInitializerTest {

    private final MongoClient client = mock(MongoClient.class);

    @Test
    @DisplayName("startup creates the unique rule name, and the partial unique index that allows one OPEN alert per check")
    void indexes() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> rules = mock(MongoCollection.class);
        MongoCollection<Document> alerts = mock(MongoCollection.class);
        when(client.getDatabase("tenant_alr")).thenReturn(database);
        when(database.getCollection("alert_rules")).thenReturn(rules);
        when(database.getCollection("alerts")).thenReturn(alerts);
        MongoCollection<Document> windows = mock(MongoCollection.class);
        when(database.getCollection("maintenance_windows")).thenReturn(windows);
        when(windows.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));
        when(rules.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));
        when(alerts.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.just("ok"));

        new AlertingIndexInitializer(client, "mongodb://mongo:27017/tenant_alr").onApplicationEvent(mock(StartupEvent.class));

        ArgumentCaptor<IndexOptions> ruleOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(rules, times(2)).createIndex(any(), ruleOptions.capture());
        assertTrue(ruleOptions.getAllValues().get(0).isUnique());
        assertEquals("organisationId_1_name_1", ruleOptions.getAllValues().get(0).getName());
        ArgumentCaptor<IndexOptions> alertOptions = ArgumentCaptor.forClass(IndexOptions.class);
        verify(alerts, times(4)).createIndex(any(), alertOptions.capture());
        IndexOptions open = alertOptions.getAllValues().get(0);
        assertTrue(open.isUnique());
        assertEquals("organisationId_1_checkId_1_open", open.getName());
        assertEquals(new Document("status", "OPEN"), open.getPartialFilterExpression());
        ArgumentCaptor<IndexOptions> windowOptions = ArgumentCaptor.forClass(IndexOptions.class);
        ArgumentCaptor<Bson> windowKeys = ArgumentCaptor.forClass(Bson.class);
        verify(windows).createIndex(windowKeys.capture(), windowOptions.capture());
        assertEquals("organisationId_1_status_1_endsAt_1", windowOptions.getValue().getName());
        assertEquals(new Document("organisationId", 1).append("status", 1).append("endsAt", 1), windowKeys.getValue());
    }

    @Test
    @DisplayName("fail-open: an unreachable server or a rejected index is logged, never propagated; the URI may omit the database; arguments are null-checked")
    void failOpen() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        when(client.getDatabase("thinklab_it_alerting_db")).thenReturn(database);
        when(database.getCollection(any(String.class))).thenReturn(collection);
        when(collection.createIndex(any(), any(IndexOptions.class))).thenReturn(Mono.error(new MongoTimeoutException("no server")))
                .thenReturn(Mono.error(new IllegalStateException("rejected"))).thenReturn(Mono.just("ok"));
        var initializer = new AlertingIndexInitializer(client, "mongodb://mongo:27017");

        initializer.onApplicationEvent(mock(StartupEvent.class));

        verify(collection, times(7)).createIndex(any(), any(IndexOptions.class));
        assertThrows(NullPointerException.class, () -> initializer.onApplicationEvent(null));
        assertThrows(NullPointerException.class, () -> new AlertingIndexInitializer(null, "mongodb://mongo:27017"));
        assertThrows(NullPointerException.class, () -> new AlertingIndexInitializer(client, null));
    }
}
