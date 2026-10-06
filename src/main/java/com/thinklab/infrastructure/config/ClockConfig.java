package com.thinklab.infrastructure.config;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

import java.time.Clock;

/** The clock the service reads time from, so tests can freeze it. */
@Factory
public class ClockConfig {

    @Singleton
    Clock clock() {
        return Clock.systemUTC();
    }
}
