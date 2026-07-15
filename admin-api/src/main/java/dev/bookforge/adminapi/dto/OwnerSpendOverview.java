package dev.bookforge.adminapi.dto;

import java.util.List;
import java.util.UUID;

/**
 * The admin dashboard's per-owner money card, composed from entitlements (gross spend by
 * action) + billing (balance, money-in, refund splits). All figures are EXACT cents from
 * append-only ledgers — nothing estimated.
 *
 * <p>{@code netSpentCents = max(0, grossSpentCents − consumeRefundCents)}: what the owner
 * actually paid for actions after failed-action refunds were returned (clamped — a
 * transiently negative value can only arise from the two ledgers being read
 * non-atomically). Lanes-only model: a USER card is the personal lane, an ORG card is the
 * org as a whole — per-member detail is deliberately not surfaced.
 */
public record OwnerSpendOverview(
        String ownerKind,
        UUID ownerId,
        boolean walletExists,
        long balanceCents,
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
