package dev.bookforge.adminapi.dto;

/**
 * One book's GROSS wallet charges from entitlements
 * {@code POST /internal/admin/spend/by-books} (pre-refund), including
 * generation / edit / extend / publish buckets.
 */
public record SpendByBookChargeView(
        String bookId,
        long chargedCents,
        long chargeCount,
        long generationCents,
        long editCents,
        long extendCents,
        long publishCents,
        long otherCents
) {}
