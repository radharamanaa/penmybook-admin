package dev.bookforge.adminapi.dto;

import java.util.UUID;

/** Minimal view used to assert the effect of a suspend/unsuspend. */
public record AccountStatusView(
        UUID userId,
        String accountStatus,   // ACTIVE | SUSPENDED | SUSPENDED_FOR_REVIEW | SOFT_DELETED | …
        String suspensionKind,  // CONTENT | DISPUTE | REFUND while SUSPENDED_FOR_REVIEW; else null
        String paymentHoldKind  // DISPUTE | REFUND a payment hold recorded on the account (L3); else null
) {}
