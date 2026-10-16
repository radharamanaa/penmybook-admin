package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/** One unresolved residual row from billing. */
public record RefundResidualEntry(
        UUID id,
        String ownerType,
        UUID ownerId,
        UUID paymentId,
        String providerPaymentRef,
        long amountCents,
        String reason,
        Instant createdAt
) {}
