package dev.bookforge.adminapi.dto;

/** One action's share of an owner's gross wallet spend (entitlements consumptions ledger). */
public record SpendActionEntry(
        String action,
        long spentCents,
        long chargeCount
) {}
