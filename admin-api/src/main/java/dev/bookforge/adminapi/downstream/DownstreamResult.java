package dev.bookforge.adminapi.downstream;

import java.util.UUID;

/**
 * Normalised result of a downstream write (entitlements {@code /internal/admin/*} or billing
 * {@code /internal/refund}). The callee performs the state change AND records its audit row in
 * the same transaction, so {@code auditEntryId} is non-null exactly when the action committed.
 *
 * <p>The refund-specific figures ({@code requestedCents}, {@code walletDebitedCents},
 * {@code idempotentReplay}) are populated only by the billing refund call (D6 / P11 / F1) so the
 * controller can surface BOTH the clamped ask and what was actually pulled back to the SPA
 * ("asked X, refunded Y" — D). They are {@code 0} / {@code false} for every other action.
 */
public record DownstreamResult(
        boolean success,
        UUID auditEntryId,       // non-null when the action + audit row committed
        String accountStatus,    // present on suspend/unsuspend; null otherwise
        long requestedCents,     // refund only — the clamped ask
        long walletDebitedCents, // refund only — what was actually debited back out of the wallet
        boolean idempotentReplay, // refund only — true when the paymentId was already refunded
        boolean outcomeUnknown   // refund only — billing did not answer in time; money may have moved
) {

    /** Convenience constructor for the non-refund writes (no refund figures). */
    public DownstreamResult(boolean success, UUID auditEntryId, String accountStatus) {
        this(success, auditEntryId, accountStatus, 0L, 0L, false, false);
    }

    /** Factory for the billing refund result, carrying the requested + actually-debited figures. */
    public static DownstreamResult refund(boolean success, UUID auditEntryId, long requestedCents,
                                          long walletDebitedCents, boolean idempotentReplay) {
        return new DownstreamResult(success, auditEntryId, null, requestedCents,
                walletDebitedCents, idempotentReplay, false);
    }

    /** The refund call timed out: billing may have refunded the card, so it is not a failure to retry. */
    public static DownstreamResult refundOutcomeUnknown() {
        return new DownstreamResult(false, null, null, 0L, 0L, false, true);
    }
}
