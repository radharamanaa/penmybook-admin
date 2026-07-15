package dev.bookforge.adminapi.dto;

/** Parsed shape of billing {@code GET /internal/admin/wallet/summary/global}. */
public record GlobalWalletSummaryView(
        long totalBalanceCents,
        long userBalanceCents,
        long orgBalanceCents,
        long moneyInCents,
        long consumeRefundCents,
        long paymentClawbackCents,
        long stripeCashGrossCents,
        long stripeCardRefundedCents
) {}
