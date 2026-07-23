package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /v1/admin/orgs/{orgId}/pricing} and
 * {@code POST /v1/admin/pricing}. Upserts a pricing rule into
 * {@code entitlements.pricing_rules} keyed by {@code (code, org_id)}.
 *
 * <p>{@code category} is required when {@code action == CHAPTER_GENERATE} so the
 * override targets a {@code per_subtopic_<genre>} row; ignored for other actions.
 *
 * @param action     the entitlement action being repriced
 * @param valueCents the per-unit cost in integer cents ({@code >= 0}; {@code 0} = free)
 * @param category   book genre for CHAPTER_GENERATE ({@code technical}, {@code science_fiction}, …)
 */
public record OrgPricingRequest(
        @NotBlank(message = "action is required")
        String action,
        @Min(value = 0, message = "valueCents must be >= 0")
        long valueCents,
        String category
) {}
