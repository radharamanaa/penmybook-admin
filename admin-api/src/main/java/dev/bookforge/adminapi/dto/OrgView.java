package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * Org context read-through from org service {@code GET /internal/orgs/{orgId}}. Used by the admin
 * portal's aggregated user/org view. {@code domainLockDomain} is nullable (no domain lock).
 * See contracts/internal-api.md (org §GET /internal/orgs/{orgId}).
 */
public record OrgView(
        UUID orgId,
        String name,
        UUID walletId,
        int maxAdmins,
        String domainLockDomain,
        UUID createdBy
) {}
