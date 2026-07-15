package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Request body for {@code POST /v1/admin/users/{userId}/wallet/top-up}.
 *
 * @param amountCents amount to credit in integer cents; must be positive.
 * @param reason      free-form audit reason supplied by the staff user (e.g. "support credit");
 *                    persisted into {@code admin_api.audit_log.new_value} for forensic review.
 */
public record WalletTopUpRequest(
        @Min(value = 1, message = "amountCents must be a positive number of cents")
        long amountCents,
        @NotBlank(message = "reason is required")
        String reason
) {}
