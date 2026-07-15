package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for {@code POST /v1/admin/orgs/{orgId}/pricing}. Reflects the persisted
 * {@code pricing_rules} row after the upsert. {@code previousValueCents} is {@code null} on
 * first upsert (insert), populated on subsequent calls (update) — admin-api uses this to
 * record old + new values in the audit_log row.
 */
public record OrgPricingRuleView(
        UUID orgId,
        String action,
        long valueCents,
        Long previousValueCents,
        Instant updatedAt
) {}
