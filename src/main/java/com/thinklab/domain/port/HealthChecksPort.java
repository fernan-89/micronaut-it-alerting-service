package com.thinklab.domain.port;

import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Outbound Port to the health monitor (ADR-031): what the checks of a tenant look like now. A failure to reach it is
 * {@link com.thinklab.domain.exception.UpstreamUnavailableException}.
 */
public interface HealthChecksPort {

    Flux<ObservedCheck> list(UUID organisationId);

    /** What alerting needs of a check: never the target, which can be an internal address. {@code error} is the monitor's fixed-vocabulary code. */
    record ObservedCheck(UUID id, String name, UUID assetId, String health, String status, String error) {
        public boolean isDown() {
            return "DOWN".equals(health);
        }

        public boolean isUp() {
            return "UP".equals(health);
        }
    }
}
