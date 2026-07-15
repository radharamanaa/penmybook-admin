package dev.bookforge.adminapi.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Inbound admin-login JWT settings (decision log D4/D16, docs/admin-control-plane/).
 *
 * <p>The admin control plane's {@code book_admin} service mints the operator token with its
 * OWN secret ({@code ADMIN_JWT_SECRET}) — deliberately distinct from the mesh
 * {@code SERVICE_JWT_SIGNING_KEY} so an admin login token can never validate as a service
 * token (or vice versa). admin-api validates the same claim set book_admin mints:
 * {@code sub=<admin uuid>}, {@code role=admin}, {@code aud=bookforge-admin},
 * {@code iss=book_admin}, {@code exp}.
 */
@ConfigurationProperties(prefix = "admin-jwt")
public record AdminJwtProperties(
        String secret,
        String issuer,
        String audience
) {}
