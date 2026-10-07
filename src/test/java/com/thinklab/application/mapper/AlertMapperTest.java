package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.AlertResponse;
import com.thinklab.application.dto.response.AuditEntryResponse;
import com.thinklab.application.dto.response.NoticeResponse;
import com.thinklab.domain.model.Alert;
import com.thinklab.domain.model.Alert.AlertStatus;
import com.thinklab.domain.model.Alert.Notice;
import com.thinklab.domain.model.MaintenanceWindow;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AlertMapperTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    @Test
    @DisplayName("an alert answers its reopenings and its notices, sorted by key, with what came of each")
    void alertWithNotices() {
        Alert alert = Alert.reconstitute(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "Intranet", null, AlertStatus.OPEN, NOW.minusSeconds(600), null, null,
                "timeout", null, NOW, 1, NOW.minusSeconds(60),
                Map.of("RESOLVED_0", new Notice(1, NOW, NOW, null), "ESCALATED_1", new Notice(3, NOW, null, "The notification webhook could not be reached.")), List.of());

        AlertResponse response = AlertMapper.toResponse(alert);

        assertEquals(1, response.reopenCount());
        assertEquals(alert.getReopenedAt(), response.reopenedAt());
        List<NoticeResponse> notices = response.notices();
        assertEquals(List.of("ESCALATED_1", "RESOLVED_0"), notices.stream().map(NoticeResponse::notice).toList());
        assertEquals(3, notices.get(0).attempts());
        assertNull(notices.get(0).sentAt());
        assertEquals("The notification webhook could not be reached.", notices.get(0).lastError());
        assertEquals(NOW, notices.get(1).sentAt());
    }

    @Test
    @DisplayName("the audit entries of a window keep the previous status when there is one")
    void windowAuditEntries() {
        MaintenanceWindow window = MaintenanceWindow.createNew(UUID.randomUUID(), UUID.randomUUID(), "Patching", null, NOW, NOW.plus(Duration.ofHours(1)), NOW, "op");
        window.cancel("op");

        List<AuditEntryResponse> trail = window.getAuditTrail().stream().map(AlertMapper::toResponse).toList();

        assertNull(trail.get(0).fromStatus());
        assertEquals("ACTIVE", trail.get(1).fromStatus());
        assertEquals("CANCELLED", trail.get(1).toStatus());
    }
}
