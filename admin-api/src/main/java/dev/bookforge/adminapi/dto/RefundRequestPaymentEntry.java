package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One payment an approved refund request can be paid out against (billing's
 * {@code StaffClawablePayment}). {@code inWindow=false} is past the 120-day card-refund wall: shown so
 * staff see it exists, never used for a payout.
 */
public record RefundRequestPaymentEntry(
        UUID paymentId,
        Instant createdAt,
        long remainingCents,
        boolean inWindow
) {}
