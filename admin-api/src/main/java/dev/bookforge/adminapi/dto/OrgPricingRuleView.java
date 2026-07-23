package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response body for pricing upserts. Reflects the persisted {@code pricing_rules} row.
 * {@code previousValueCents} is {@code null} on first upsert; {@code category} is set when
 * the override targeted a per-genre chapter price.
 */
public record OrgPricingRuleView(
        UUID orgId,
        String action,
        String category,
        long valueCents,
        Long previousValueCents,
        Instant updatedAt
) {}
