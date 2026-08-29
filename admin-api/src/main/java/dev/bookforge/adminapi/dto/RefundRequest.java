package dev.bookforge.adminapi.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code POST /v1/admin/users/{userId}/refund}.
 *
 * <p>Per AD8 (master plan §3) refund granularity is chapter-level only. The refund is
 * idempotent on {@code paymentId} at the billing service (G14 / AD8) — a duplicate refund
 * against the same payment returns the original receipt with {@code idempotentReplay=true}.
 *
 * @param paymentId       the payment row to refund; idempotency key for the downstream billing call.
 * @param amountCents     refund amount in cents; must be positive and {@code <=} the original payment.
 * @param reason          free-form audit reason persisted to {@code admin_api.audit_log.new_value}.
 * @param refundRequestId optional approved customer request this payout is paying out. When
 *                        present it is forwarded to billing and bound into the step-up
 *                        {@code paramsHash} so a token without it cannot be replayed onto a
 *                        request payout. Required by billing if the owner has an open APPROVED
 *                        request.
 */
public record RefundRequest(
        @NotNull(message = "paymentId is required")
        UUID paymentId,
        @Min(value = 1, message = "amountCents must be a positive number of cents")
        long amountCents,
        @NotBlank(message = "reason is required")
        String reason,
        UUID refundRequestId
) {}
