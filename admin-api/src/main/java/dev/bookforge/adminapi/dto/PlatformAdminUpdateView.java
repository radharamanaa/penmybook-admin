package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * Response body for {@code PATCH /v1/admin/users/{userId}}. Carries the post-update value of
 * {@code is_platform_admin} so the SPA can reconcile without a follow-up read.
 */
public record PlatformAdminUpdateView(
        UUID userId,
        boolean isPlatformAdmin
) {}
