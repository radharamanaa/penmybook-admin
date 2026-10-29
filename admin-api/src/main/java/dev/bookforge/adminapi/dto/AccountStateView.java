package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * What staff need on screen before lifting a hold: the account status (entitlements) and the
 * wallet's freeze, its reason and any money a refund or dispute could not take back (billing).
 */
public record AccountStateView(
        UUID userId,
        String accountStatus,
        boolean walletExists,
        long balanceCents,
        boolean walletFrozen,
        String walletFrozenReason,
        long unrecoveredCents
) {}
