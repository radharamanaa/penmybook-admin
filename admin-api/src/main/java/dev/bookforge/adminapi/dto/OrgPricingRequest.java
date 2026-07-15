package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /v1/admin/orgs/{orgId}/pricing}. Upserts a per-org pricing
 * override into {@code entitlements.pricing_rules} keyed by {@code (action, org_id)}.
 *
 * <p>R26 (master plan §6): pricing is a contractual decision and MUST be platform-mediated —
 * the endpoint is gated by {@code hasRole('PLATFORM_ADMIN')} so an org owner cannot
 * self-discount. Every call writes an {@code admin_api.audit_log} row capturing old + new value.
 *
 * @param action     the entitlement action being repriced; uses the locked enum from X1
 *                   ({@code CHAPTER_GENERATE}, {@code COVER_GENERATE}, {@code COVER_REGEN},
 *                   {@code CHAPTER_EDIT}). Never {@code GENERATE_CHAPTER}.
 * @param valueCents the per-action cost in integer cents to apply for this org. Must be {@code >= 0}.
 *                   {@code 0} is a valid value (free for that org).
 */
public record OrgPricingRequest(
        @NotBlank(message = "action is required")
        String action,
        @Min(value = 0, message = "valueCents must be >= 0")
        long valueCents
) {}
