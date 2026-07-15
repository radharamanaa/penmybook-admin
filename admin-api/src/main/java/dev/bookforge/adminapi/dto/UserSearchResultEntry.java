package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One row in the admin user-search result list ({@code GET /v1/admin/users/search}).
 * {@code accountStatus} (e.g. ACTIVE / WAITLISTED) drives the operator's approve-by-email flow
 * (admin control plane D11). {@code isPlatformAdmin} is best-effort — entitlements has no
 * platform-admin concept yet, so it is currently always false (Phase-4 scaffolding).
 */
public record UserSearchResultEntry(
        UUID userId,
        String email,
        String accountStatus,
        boolean isPlatformAdmin,
        Instant createdAt
) {}
