package com.thinklab.domain.port;

import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Outbound Port to tell people: one JSON notice posted to a webhook. The target is the NAME of an environment variable that holds the address
 * (ADR-036): the address is read when it is used and never leaves the adapter. A failure is
 * {@link com.thinklab.domain.exception.UpstreamUnavailableException} with a fixed message, never what the webhook answered.
 */
public interface NoticePort {

    Mono<Void> send(String target, Notice notice);

    /** What a notice says: only ids, the check name, the monitor's fixed-vocabulary error and the severities, never a target or a person. */
    record Notice(String event, UUID alertId, UUID incidentId, String checkName, String error, String impact, String urgency, Instant at) {

        public String text() {
            String what = switch (event) {
                case "OPENED" -> "is down";
                case "REOPENED" -> "is down again";
                case "RESOLVED" -> "is answering again";
                default -> "is still down and its incident has not been acknowledged";
            };
            return "[ALERT " + event + "] " + checkName + " " + what + (error != null && !"RESOLVED".equals(event) ? " (" + error + ")" : "")
                    + (incidentId != null ? ". Incident " + incidentId + "." : ".");
        }
    }
}
