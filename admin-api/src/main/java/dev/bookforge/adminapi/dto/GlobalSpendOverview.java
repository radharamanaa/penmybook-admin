package dev.bookforge.adminapi.dto;

import java.util.List;

/**
 * The admin dashboard's platform-wide money tiles, composed from entitlements' global spend
 * aggregate + billing's global wallet summary. Same exact-cents semantics as
 * {@link OwnerSpendOverview}, summed across every owner; held balance split by lane.
 */
public record GlobalSpendOverview(
        long totalBalanceCents,
        long userBalanceCents,
        long orgBalanceCents,
        long grossSpentCents,
        long consumeRefundCents,
        long netSpentCents,
        long moneyInCents,
        long paymentClawbackCents,
        long stripeCashGrossCents,
        long stripeCardRefundedCents,
        long walletChargeCount,
        List<SpendActionEntry> byAction
) {}
