package dev.bookforge.adminapi.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the staff refund-request queue, as billing reports it.
 *
 * <p>Carries BOTH refundable figures deliberately. {@code snapshotRefundableCents} is what the
 * user was quoted when they submitted; {@code liveRefundableCents} is what is actually there now.
 * The wallet stays spendable while a request waits, so an approval executes against the live
 * figure — showing only the snapshot would have an admin approve an amount billing then silently
 * reduces, and the user would be told a number nobody honoured.
 */
public record RefundRequestQueueEntry(
        UUID id,
        String ownerType,
        UUID ownerId,
        String status,
        Long requestedAmountCents,
        String reason,
        long snapshotRefundableCents,
        long liveRefundableCents,
        Long approvedAmountCents,
        Long refundedCents,
        Long residualCents,
        String resolutionNote,
        Instant createdAt,
        Instant updatedAt,
        String ownerName,
        String ownerEmail,
        // The payments an approved request is paid out against, and the one billing suggests first.
        // admin-api used to drop both, so "Pay out" could not name a payment (refund-flow test P1).
        List<RefundRequestPaymentEntry> payments,
        UUID suggestedPaymentId
) {}
