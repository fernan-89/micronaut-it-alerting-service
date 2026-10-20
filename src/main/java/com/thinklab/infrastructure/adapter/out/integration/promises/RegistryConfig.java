package com.thinklab.infrastructure.adapter.out.integration.promises;

import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;

/**
 * Where a registry that answers the promises contract (platform ADR-001) is: the name of the rule source it serves ({@code BACKUP}, {@code DR}...), the address
 * of the service and the BIAN path of its domain. The contract route is {@code <url><path>/promises/retrieve}. A new source is an entry of
 * {@code thinklab.alerting.registries.<SOURCE>} (and a line in the source list of the domain), never code that knows the registry's fields.
 */
@EachProperty("thinklab.alerting.registries")
public class RegistryConfig {

    private final String name;
    private String url;
    private String path;

    public RegistryConfig(@Parameter String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
