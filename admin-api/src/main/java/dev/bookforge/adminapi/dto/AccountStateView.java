package dev.bookforge.adminapi.dto;

import java.util.UUID;

/**
 * What staff need on screen before lifting a hold: the account status (entitlements) and the
 * wallet's freeze, its reason and any money a refund or dispute could not take back (billing).
 */
public record AccountStateView(
        UUID userId,
        String accountStatus,
        /** Why a SUSPENDED_FOR_REVIEW account is held: CONTENT, DISPUTE or REFUND (null otherwise). */
        String suspensionKind,
        boolean walletExists,
        long balanceCents,
        boolean walletFrozen,
        String walletFrozenReason,
        long unrecoveredCents,
        /** True when billing did not answer: the wallet fields are unknown, not "not frozen". */
        boolean walletDegraded,
        /**
         * DISPUTE or REFUND when a payment hold reached the account, even one already suspended for
         * a content review or by staff (L3); null otherwise.
         */
        String paymentHoldKind,
        /** The curator's content-strike count (L5); null when the curator did not answer. */
        Integer contentStrikes
) {}
