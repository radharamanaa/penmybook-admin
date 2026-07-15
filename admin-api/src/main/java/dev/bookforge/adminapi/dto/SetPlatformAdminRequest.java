package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code PATCH /v1/admin/users/{userId}}. Flips the {@code is_platform_admin}
 * boolean on the entitlements {@code users} row.
 *
 * @param isPlatformAdmin {@code true} to grant the platform-admin role; {@code false} to revoke.
 *                        Required so a missing field cannot accidentally interpret as a revoke.
 */
public record SetPlatformAdminRequest(
        @NotNull(message = "isPlatformAdmin is required")
        Boolean isPlatformAdmin
) {}
