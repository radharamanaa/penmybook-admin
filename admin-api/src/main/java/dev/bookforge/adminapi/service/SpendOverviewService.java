package dev.bookforge.adminapi.service;

import dev.bookforge.adminapi.downstream.DownstreamClient;
import dev.bookforge.adminapi.dto.GlobalSpendOverview;
import dev.bookforge.adminapi.dto.GlobalWalletSummaryView;
import dev.bookforge.adminapi.dto.MoneyFlowsView;
import dev.bookforge.adminapi.dto.OwnerSpendOverview;
import dev.bookforge.adminapi.dto.PaymentHealthView;
import dev.bookforge.adminapi.dto.SpendByBooksResponse;
import dev.bookforge.adminapi.dto.SpendReportView;
import dev.bookforge.adminapi.dto.WalletSummaryView;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Composes the spend dashboard's per-owner cards and global tiles from the two downstream
 * ledger reads: entitlements (gross spend by action) and billing (balance, money-in, refund
 * splits). Lanes-only model — a USER card is the personal lane, an ORG card is the shared
 * org wallet as a whole; per-member detail is deliberately not composed.
 *
 * <p>The one derived figure is {@link #netSpentCents(long, long)}: consumptions is a gross
 * ledger (a consume-refund writes no compensating row there — it credits the wallet in
 * billing under a positive REFUND transaction), so netting the two ledgers here is what
 * makes "total spent" honest after failed-action refunds. Money flows are relayed verbatim
 * from billing (single-ledger, single window definition — nothing to derive).
 */
@Service
public class SpendOverviewService {

    private static final String OWNER_TYPE_USER = "USER";
    private static final String OWNER_TYPE_ORG = "ORG";

    private final DownstreamClient downstream;

    public SpendOverviewService(DownstreamClient downstream) {
        this.downstream = downstream;
    }

    public OwnerSpendOverview userOverview(UUID userId) {
        return ownerOverview("user", OWNER_TYPE_USER, userId);
    }

    public OwnerSpendOverview orgOverview(UUID orgId) {
        return ownerOverview("org", OWNER_TYPE_ORG, orgId);
    }

    public GlobalSpendOverview globalOverview() {
        SpendReportView spend = downstream.globalSpend();
        GlobalWalletSummaryView wallet = downstream.walletSummaryGlobal();
        return new GlobalSpendOverview(
                wallet.totalBalanceCents(),
                wallet.userBalanceCents(),
                wallet.orgBalanceCents(),
                spend.grossSpentCents(),
                wallet.consumeRefundCents(),
                netSpentCents(spend.grossSpentCents(), wallet.consumeRefundCents()),
                wallet.moneyInCents(),
                wallet.paymentClawbackCents(),
                wallet.stripeCashGrossCents(),
                wallet.stripeCardRefundedCents(),
                spend.walletChargeCount(),
                spend.byAction());
    }

    public MoneyFlowsView moneyFlows() {
        return downstream.walletFlows();
    }

    public PaymentHealthView paymentsHealth() {
        return downstream.paymentsHealth();
    }

    /**
     * Per-book GROSS charges (pre-refund). Downstream fail-softs to an empty list with
     * {@code degraded=true}; books with no wallet rows are simply absent (caller treats
     * missing as $0).
     */
    public SpendByBooksResponse chargesByBooks(List<String> bookIds) {
        return downstream.chargesByBooks(bookIds);
    }

    private OwnerSpendOverview ownerOverview(String ownerKind, String ownerType, UUID ownerId) {
        SpendReportView spend = downstream.ownerSpend(ownerType, ownerId);
        WalletSummaryView wallet = downstream.walletSummary(ownerType, ownerId);
        return new OwnerSpendOverview(
                ownerKind,
                ownerId,
                wallet.walletExists(),
                wallet.balanceCents(),
                spend.grossSpentCents(),
                wallet.consumeRefundCents(),
                netSpentCents(spend.grossSpentCents(), wallet.consumeRefundCents()),
                wallet.moneyInCents(),
                wallet.paymentClawbackCents(),
                wallet.stripeCashGrossCents(),
                wallet.stripeCardRefundedCents(),
                spend.walletChargeCount(),
                spend.byAction());
    }

    /**
     * The ONE place net-spend is derived: gross (entitlements consumptions) minus
     * consume-refunds (billing wallet ledger), clamped at zero — the two ledgers are read
     * non-atomically, so a refund landing between the reads can transiently exceed the
     * stale gross; a negative "spent" figure must never render on a money dashboard.
     */
    private static long netSpentCents(long grossSpentCents, long consumeRefundCents) {
        return Math.max(0L, grossSpentCents - consumeRefundCents);
    }
}
