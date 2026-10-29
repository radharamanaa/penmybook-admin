package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One payment a refund or dispute could not fully take back from the wallet because the money
 * was already spent, as billing reports it ({@code payments.unrecovered_cents}).
 */
public record UnrecoveredPaymentEntry(
        UUID paymentId,
        String ownerType,
        UUID ownerId,
        String providerPaymentRef,
        String status,
        long grossAmountCents,
        long unrecoveredCents,
        Instant updatedAt
) {}
