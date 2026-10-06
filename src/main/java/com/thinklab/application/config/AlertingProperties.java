package com.thinklab.application.config;

import io.micronaut.context.annotation.ConfigurationProperties;

/** Settings of the alerting rounds (ADR-030): how many tenants are looked at in parallel. The tick itself is {@code thinklab.alerting.tick}. */
@ConfigurationProperties("thinklab.alerting")
public class AlertingProperties {

    private int concurrency = 5;

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }
}
