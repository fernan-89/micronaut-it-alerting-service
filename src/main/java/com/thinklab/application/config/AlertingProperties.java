package com.thinklab.application.config;

import io.micronaut.context.annotation.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings of alerting: how many tenants a round looks at in parallel (ADR-030), and {@code insecureHosts}, the hosts a notice webhook may
 * be reached at over plain http (ADR-036): empty in a real deployment (https only), set to the host of the webhook double in a test
 * environment. The tick itself is {@code thinklab.alerting.tick}.
 */
@ConfigurationProperties("thinklab.alerting")
public class AlertingProperties {

    private int concurrency = 5;
    private List<String> insecureHosts = new ArrayList<>();

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public List<String> getInsecureHosts() {
        return insecureHosts;
    }

    public void setInsecureHosts(List<String> insecureHosts) {
        this.insecureHosts = insecureHosts;
    }
}
