package com.thinklab.domain.port;

import com.thinklab.domain.port.NoticePort.Notice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The line a person reads: the event, the check, the monitor's fixed-vocabulary error (never on a recovery) and the incident when there is one. */
class NoticeTextTest {

    private static final UUID ALERT = UUID.randomUUID();
    private static final UUID INCIDENT = UUID.randomUUID();

    private Notice notice(String event, UUID incident, String error) {
        return new Notice(event, ALERT, incident, "Intranet", error, "HIGH", "LOW", Instant.parse("2026-10-07T12:00:00Z"));
    }

    @Test
    @DisplayName("each event says what happened, with the error and the incident")
    void events() {
        assertEquals("[ALERT OPENED] Intranet is down (timeout). Incident " + INCIDENT + ".", notice("OPENED", INCIDENT, "timeout").text());
        assertEquals("[ALERT REOPENED] Intranet is down again (timeout). Incident " + INCIDENT + ".", notice("REOPENED", INCIDENT, "timeout").text());
        assertEquals("[ALERT ESCALATED] Intranet is still down and its incident has not been acknowledged (timeout). Incident " + INCIDENT + ".",
                notice("ESCALATED", INCIDENT, "timeout").text());
    }

    @Test
    @DisplayName("a recovery never repeats the error; no error and no incident leave those parts out")
    void optionalParts() {
        assertEquals("[ALERT RESOLVED] Intranet is answering again. Incident " + INCIDENT + ".", notice("RESOLVED", INCIDENT, "timeout").text());
        assertEquals("[ALERT OPENED] Intranet is down.", notice("OPENED", null, null).text());
    }
}
