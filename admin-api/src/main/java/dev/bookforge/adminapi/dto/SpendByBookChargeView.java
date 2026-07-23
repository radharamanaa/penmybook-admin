package dev.bookforge.adminapi.dto;

/**
 * One book's GROSS wallet charges from entitlements
 * {@code POST /internal/admin/spend/by-books} (pre-refund).
 */
public record SpendByBookChargeView(
        String bookId,
        long chargedCents,
        long chargeCount
) {}
