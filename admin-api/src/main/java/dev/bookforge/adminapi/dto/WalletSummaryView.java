package dev.bookforge.adminapi.dto;

/**
 * Parsed shape of billing {@code GET /internal/admin/wallet/summary} — one owner's exact
 * money picture off billing's append-only ledgers. {@code consumeRefundCents} (positive
 * REFUND rows) nets the consumptions gross; {@code paymentClawbackCents} (|negative REFUND
 * rows|) nets money-in.
 */
public record WalletSummaryView(
        boolean walletExists,
        long balanceCents,
        long moneyInCents,
        long consumeRefundCents,
        long paymentClawbackCents,
        long stripeCashGrossCents,
        long stripeCardRefundedCents
) {}
