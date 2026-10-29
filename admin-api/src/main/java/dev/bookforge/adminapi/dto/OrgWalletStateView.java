package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * An org wallet's freeze, its reason and any money a refund or dispute could not take back. A
 * chargeback on an org top-up freezes the org wallet and suspends every member; members are
 * reactivated one by one from the Users page.
 */
public record OrgWalletStateView(
        UUID orgId,
        boolean walletExists,
        long balanceCents,
        boolean walletFrozen,
        String walletFrozenReason,
        long unrecoveredCents
) {}
