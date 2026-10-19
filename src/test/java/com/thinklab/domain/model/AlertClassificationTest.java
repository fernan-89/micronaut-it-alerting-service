package com.thinklab.domain.model;

import com.thinklab.application.mapper.AlertMapper;
import com.thinklab.domain.model.AlertRule.AlertClass;
import com.thinklab.domain.model.AlertRule.Component;
import com.thinklab.domain.model.AlertRule.Options;
import com.thinklab.domain.model.AlertRule.Severity;
import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.domain.repository.AlertRepository;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertDocument.AlertPersistenceMapper;
import com.thinklab.infrastructure.adapter.out.persistence.entity.AlertRuleDocument.AlertRulePersistenceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AlertClassificationTest {

    private final UUID org = UUID.randomUUID();
    private final UUID requester = UUID.randomUUID();

    private AlertRule rule(Source source, Options options) {
        return AlertRule.createNew(UUID.randomUUID(), org, "Rule", source, null, Severity.HIGH, Severity.MEDIUM, requester, options, "op");
    }

    private Alert alert(AlertClass alertClass, Component component) {
        return Alert.createNew(UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(), "Disk", null, "timeout", "system:alerting", alertClass, component);
    }

    @Test
    @DisplayName("every source has a default class, and a rule that names none takes the one of its source (ADR-040)")
    void defaultClassOfTheSource() {
        assertEquals(AlertClass.AVAILABILITY, Source.HEALTH_CHECK.defaultClass());
        assertEquals(AlertClass.PROTECTION, Source.BACKUP.defaultClass());
        assertEquals(AlertClass.PROTECTION, Source.PATCH.defaultClass());
        assertEquals(AlertClass.CAPACITY, Source.CAPACITY.defaultClass());
        assertEquals(AlertClass.CONTRACT, Source.VENDOR.defaultClass());
        assertEquals(AlertClass.CONTRACT, Source.LICENSE.defaultClass());
        assertEquals(AlertClass.PROTECTION, Source.DR.defaultClass());
        assertEquals(AlertClass.AVAILABILITY, Source.CLOUD_INVENTORY.defaultClass());
        assertEquals(AlertClass.CAPACITY, rule(Source.CAPACITY, Options.NONE).effectiveClass());
        assertNull(rule(Source.CAPACITY, Options.NONE).getOptions().component());
    }

    @Test
    @DisplayName("a rule that names a class and a component keeps them, whatever its source says")
    void namedClassAndComponent() {
        AlertRule rule = rule(Source.HEALTH_CHECK, Options.of(null, null, null, null, AlertClass.CAPACITY, Component.STORAGE));
        assertEquals(AlertClass.CAPACITY, rule.effectiveClass());
        assertEquals(Component.STORAGE, rule.getOptions().component());
        assertEquals(30, rule.getOptions().reopenWithinMinutes());
        assertEquals(Options.of(null, null, null, 5), new Options(null, null, null, 5, null, null));
    }

    @Test
    @DisplayName("an alert keeps the class and component its rule had when it opened, and one opened before classification has none")
    void alertSnapshot() {
        Alert classified = alert(AlertClass.CAPACITY, Component.DATABASE);
        assertEquals(AlertClass.CAPACITY, classified.getAlertClass());
        assertEquals(Component.DATABASE, classified.getComponent());
        Alert legacy = Alert.createNew(UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(), "Disk", null, null, "system:alerting");
        assertNull(legacy.getAlertClass());
        assertNull(legacy.getComponent());
    }

    @Test
    @DisplayName("the responses show the effective class and the component, and null for an alert that was never classified")
    void responses() {
        var ruleResponse = AlertMapper.toResponse(rule(Source.PATCH, Options.NONE));
        assertEquals("PROTECTION", ruleResponse.alertClass());
        assertNull(ruleResponse.component());
        var named = AlertMapper.toResponse(rule(Source.PATCH, Options.of(null, null, null, null, AlertClass.AVAILABILITY, Component.NETWORK)));
        assertEquals("AVAILABILITY", named.alertClass());
        assertEquals("NETWORK", named.component());

        var alertResponse = AlertMapper.toResponse(alert(AlertClass.AVAILABILITY, Component.NETWORK));
        assertEquals("AVAILABILITY", alertResponse.alertClass());
        assertEquals("NETWORK", alertResponse.component());
        var legacy = AlertMapper.toResponse(Alert.createNew(UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(), "Disk", null, null, "system:alerting"));
        assertNull(legacy.alertClass());
        assertNull(legacy.component());
    }

    @Test
    @DisplayName("class and component survive the database, and a document written before classification reads back with none")
    void persistence() {
        AlertRule rule = rule(Source.LICENSE, Options.of(null, null, null, 10, AlertClass.CONTRACT, Component.APPLICATION));
        AlertRule readBack = AlertRulePersistenceMapper.toDomain(AlertRulePersistenceMapper.toDocument(rule));
        assertEquals(AlertClass.CONTRACT, readBack.getOptions().alertClass());
        assertEquals(Component.APPLICATION, readBack.getOptions().component());
        var plainDoc = AlertRulePersistenceMapper.toDocument(rule(Source.LICENSE, Options.NONE));
        assertNull(plainDoc.getAlertClass());
        assertNull(plainDoc.getComponent());
        AlertRule plain = AlertRulePersistenceMapper.toDomain(plainDoc);
        assertNull(plain.getOptions().alertClass());
        assertEquals(AlertClass.CONTRACT, plain.effectiveClass());

        Alert alert = alert(AlertClass.AVAILABILITY, Component.CLOUD);
        Alert alertBack = AlertPersistenceMapper.toDomain(AlertPersistenceMapper.toDocument(alert));
        assertEquals(AlertClass.AVAILABILITY, alertBack.getAlertClass());
        assertEquals(Component.CLOUD, alertBack.getComponent());
        var legacyDoc = AlertPersistenceMapper.toDocument(Alert.createNew(UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(), "Disk", null, null, "system:alerting"));
        assertNull(legacyDoc.getAlertClass());
        assertNull(AlertPersistenceMapper.toDomain(legacyDoc).getAlertClass());
    }

    @Test
    @DisplayName("the alert filter can be built with or without a classification")
    void filter() {
        var plain = new AlertRepository.Filter(null, null);
        assertNull(plain.alertClass());
        assertNull(plain.component());
        assertEquals(AlertClass.CAPACITY, new AlertRepository.Filter(null, null, AlertClass.CAPACITY, Component.STORAGE).alertClass());
    }
}
