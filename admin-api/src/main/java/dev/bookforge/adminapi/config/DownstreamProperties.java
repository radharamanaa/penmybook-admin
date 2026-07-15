package dev.bookforge.adminapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Per-callee base URLs for the internal mesh, bound from {@code downstream.*} in application.yml
 * (which themselves resolve from {@code .env}). No hardcoding — admin-api is a facade and must be
 * repointable per environment.
 */
@ConfigurationProperties(prefix = "downstream")
public record DownstreamProperties(
        String entitlementsUrl,
        String billingUrl,
        String orgUrl
) {}
