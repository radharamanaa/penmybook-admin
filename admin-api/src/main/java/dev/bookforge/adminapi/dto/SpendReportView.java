package dev.bookforge.adminapi.dto;

import java.util.List;

/**
 * Parsed shape of entitlements {@code GET /internal/admin/spend} and {@code /spend/global}.
 * GROSS spend (positive-amount WALLET consumptions only) — refund netting is applied by
 * {@code SpendOverviewService} against billing's wallet summary. Totals are derived
 * upstream from the same GROUP BY as {@code byAction}, so headline ≡ Σ rows.
 */
public record SpendReportView(
        long grossSpentCents,
        long walletChargeCount,
        List<SpendActionEntry> byAction
) {}
