package com.thinklab.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClockConfigTest {

    @Test
    @DisplayName("the clock is the system clock in UTC")
    void clock() {
        Clock clock = new ClockConfig().clock();

        assertEquals("Z", clock.getZone().getId());
        assertTrue(Math.abs(clock.millis() - System.currentTimeMillis()) < 5000);
    }
}
