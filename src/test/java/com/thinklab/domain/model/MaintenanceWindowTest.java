package com.thinklab.domain.model;

import com.thinklab.domain.exception.InvalidMaintenanceWindowStatusException;
import com.thinklab.domain.model.MaintenanceWindow.WindowAuditEntry;
import com.thinklab.domain.model.MaintenanceWindow.WindowStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaintenanceWindowTest {

    private final UUID org = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-07T10:00:00Z");
    private final Instant start = now.plus(Duration.ofHours(1));
    private final Instant end = now.plus(Duration.ofHours(3));

    private MaintenanceWindow window(UUID checkId) {
        return MaintenanceWindow.createNew(UUID.randomUUID(), org, "Core switch upgrade", checkId, start, end, now, "op");
    }

    private static void rejects(Runnable action) {
        assertThrows(IllegalArgumentException.class, action::run);
    }

    @Test
    @DisplayName("a new window is ACTIVE with an INITIATED entry that says what it covers and when")
    void create() {
        MaintenanceWindow all = window(null);
        MaintenanceWindow one = window(UUID.randomUUID());

        assertEquals(WindowStatus.ACTIVE, all.getStatus());
        assertEquals("INITIATED", all.getAuditTrail().get(0).action());
        assertNull(all.getAuditTrail().get(0).fromStatus());
        assertTrue(all.getAuditTrail().get(0).detail().contains("every check"));
        assertTrue(one.getAuditTrail().get(0).detail().contains("one check"));
        assertEquals(start, all.getStartsAt());
        assertEquals(end, all.getEndsAt());
        assertEquals("Core switch upgrade", all.getName());
        assertEquals(org, all.getOrganisationId());
        assertEquals(all.getCreatedAt(), all.getUpdatedAt());
        assertNull(all.getCheckId());
    }

    @Test
    @DisplayName("creation guards: ids, name, start and end, the longest window, ending in the past, executor")
    void createGuards() {
        UUID id = UUID.randomUUID();
        rejects(() -> MaintenanceWindow.createNew(null, org, "n", null, start, end, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, null, "n", null, start, end, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, null, null, start, end, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, " ", null, start, end, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, "x".repeat(81), null, start, end, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, "n", null, null, end, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, "n", null, start, null, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, "n", null, end, start, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, "n", null, start, start, now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, "n", null, start, start.plus(Duration.ofDays(31)), now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, "n", null, now.minus(Duration.ofHours(3)), now.minus(Duration.ofHours(1)), now, "op"));
        rejects(() -> MaintenanceWindow.createNew(id, org, "n", null, start, end, now, null));
        rejects(() -> MaintenanceWindow.createNew(id, org, "n", null, start, end, now, " "));
        MaintenanceWindow.createNew(id, org, "n", null, now.minus(Duration.ofHours(1)), now.plus(Duration.ofHours(1)), now, "op");
        MaintenanceWindow.createNew(id, org, "n", null, start, start.plus(Duration.ofDays(30)), now, "op");
    }

    @Test
    @DisplayName("a window silences its check (or every check) from its start, included, to its end, excluded, and only while ACTIVE")
    void silences() {
        UUID check = UUID.randomUUID();
        MaintenanceWindow all = window(null);
        MaintenanceWindow one = window(check);

        assertFalse(all.silences(check, start.minusSeconds(1)));
        assertTrue(all.silences(check, start));
        assertTrue(all.silences(UUID.randomUUID(), start.plusSeconds(1)));
        assertFalse(all.silences(check, end));
        assertTrue(one.silences(check, start));
        assertFalse(one.silences(UUID.randomUUID(), start));
        all.cancel("op");
        assertFalse(all.silences(check, start));
    }

    @Test
    @DisplayName("cancelling is terminal: ACTIVE -> CANCELLED with its entry; a second cancel is refused")
    void cancel() {
        MaintenanceWindow window = window(null);

        WindowAuditEntry entry = window.cancel("op-2");

        assertEquals(WindowStatus.CANCELLED, window.getStatus());
        assertEquals("CANCELLED", entry.action());
        assertEquals(WindowStatus.ACTIVE, entry.fromStatus());
        assertEquals(2, window.getAuditTrail().size());
        assertThrows(InvalidMaintenanceWindowStatusException.class, () -> window.cancel("op"));
        assertThrows(IllegalArgumentException.class, () -> window().cancel(null));
        assertThrows(IllegalArgumentException.class, () -> window().cancel(" "));
    }

    private MaintenanceWindow window() {
        return window(null);
    }

    @Test
    @DisplayName("reconstitute keeps what was stored, defaults what is missing, and refuses a missing identity")
    void reconstitute() {
        UUID id = UUID.randomUUID();
        MaintenanceWindow full = MaintenanceWindow.reconstitute(id, org, "n", id, start, end, WindowStatus.CANCELLED, now, now,
                List.of(new WindowAuditEntry(now, "INITIATED", "op", null, WindowStatus.ACTIVE, "d")));
        assertEquals(WindowStatus.CANCELLED, full.getStatus());
        assertEquals(1, full.getAuditTrail().size());

        MaintenanceWindow bare = MaintenanceWindow.reconstitute(id, org, "n", null, start, end, null, null, null, null);
        assertEquals(WindowStatus.ACTIVE, bare.getStatus());
        assertTrue(bare.getAuditTrail().isEmpty());
        assertEquals(bare.getCreatedAt(), bare.getUpdatedAt());

        rejects(() -> MaintenanceWindow.reconstitute(null, org, "n", null, start, end, null, null, null, null));
        rejects(() -> MaintenanceWindow.reconstitute(id, null, "n", null, start, end, null, null, null, null));
        rejects(() -> MaintenanceWindow.reconstitute(id, org, null, null, start, end, null, null, null, null));
        rejects(() -> MaintenanceWindow.reconstitute(id, org, "n", null, null, end, null, null, null, null));
        rejects(() -> MaintenanceWindow.reconstitute(id, org, "n", null, start, null, null, null, null, null));
    }
}
