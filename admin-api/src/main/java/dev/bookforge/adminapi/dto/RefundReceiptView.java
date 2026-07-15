package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * Response body for {@code POST /v1/admin/users/{userId}/refund}. Mirrors the relevant fields of
 * the billing {@code POST /internal/refund} 200 body (D6 / P11 / F1).
 *
 * <p>The admin sees BOTH figures so the SPA can render "asked X, refunded Y" (D): {@code
 * requestedCents} is the clamped ASK (the still-refundable envelope ∩ what was requested) and
 * {@code walletDebitedCents} is what was ACTUALLY pulled back out of the wallet and mirrored onto
 * the card. They diverge when the wallet was partly spent.
 *
 * <p>{@code idempotentReplay} is {@code true} when the same refund operation has already run —
 * billing returns the original receipt unchanged (AD8 / G14). {@code auditEntryId} is the billing
 * audit-row id, non-null when the refund + audit committed.
 *
 * @param paymentId          the payment that was refunded.
 * @param requestedCents     the clamped refund ask, in cents.
 * @param walletDebitedCents what was actually debited back out of the wallet, in cents.
 * @param auditEntryId       the billing audit-row id; null if the action rolled back.
 * @param idempotentReplay   true when the refund operation was a duplicate.
 */
public record RefundReceiptView(
        UUID paymentId,
        long requestedCents,
        long walletDebitedCents,
        UUID auditEntryId,
        boolean idempotentReplay
) {}
