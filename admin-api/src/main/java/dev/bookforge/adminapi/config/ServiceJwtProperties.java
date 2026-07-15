package dev.bookforge.adminapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Service-account JWT settings used to authenticate every outbound {@code /internal/*} call.
 * HS256 shared secret in dev (same value across services); bound from {@code service-jwt.*}.
 */
@ConfigurationProperties(prefix = "service-jwt")
public record ServiceJwtProperties(
        String signingKey,
        String issuer,
        String audience
) {}
