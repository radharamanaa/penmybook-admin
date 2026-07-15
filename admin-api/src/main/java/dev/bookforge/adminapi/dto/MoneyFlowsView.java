package dev.bookforge.adminapi.dto;

/**
 * Parsed shape of billing {@code GET /internal/admin/wallet/flows} — money loaded vs spent
 * per rolling window (7/30/365 days + all time), both figures from the single
 * {@code wallet_transactions} ledger so they can never window-skew against each other.
 * Relayed verbatim to the SPA's "money flows" section.
 */
public record MoneyFlowsView(
        long weekLoadedCents,
        long weekSpentCents,
        long monthLoadedCents,
        long monthSpentCents,
        long yearLoadedCents,
        long yearSpentCents,
        long allTimeLoadedCents,
        long allTimeSpentCents
) {}
