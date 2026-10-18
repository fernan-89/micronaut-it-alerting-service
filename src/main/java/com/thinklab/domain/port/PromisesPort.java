package com.thinklab.domain.port;

import com.thinklab.domain.model.AlertRule.Source;
import com.thinklab.domain.port.HealthChecksPort.ObservedCheck;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Outbound Port to the registries that judge a promise when it is read (ADR-037): the policies of one registry, each told as an
 * {@link ObservedCheck} (DOWN = the promise is broken, UP = kept, anything else = not judged). A failure to reach a registry is
 * {@link com.thinklab.domain.exception.UpstreamUnavailableException}.
 */
public interface PromisesPort {

    /** The policies of the registry that {@code source} names, never {@code HEALTH_CHECK}. */
    Flux<ObservedCheck> list(UUID organisationId, Source source);
}
