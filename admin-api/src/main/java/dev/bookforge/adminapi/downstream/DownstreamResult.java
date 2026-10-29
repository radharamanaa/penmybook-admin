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
        RefundFailure refundFailure, // refund only — why a refund did not succeed (NONE otherwise)
        String refusalDetail,    // refund only — billing's words for REFUSED; its reason code for BILLING_CONFLICT
        int refusalStatus        // refund only — billing's HTTP status for a REFUSED answer, else 0
) {

    /** Why a billing refund call did not succeed. Each one tells staff something different. */
    public enum RefundFailure {
        /** Success, or a non-refund write. A refund failure is always one of the kinds below. */
        NONE,
        /**
         * No readable answer after the request left admin-api (read timeout, reset, EOF, garbled
         * or unexpected body, an unexpected fault): the card refund may have happened.
         */
        OUTCOME_UNKNOWN,
        /** Billing answered 5xx. It may have reached Stripe before failing. */
        BILLING_ERROR,
        /**
         * Billing answered 409 {@code refund_outcome_unknown}: it called Stripe and does not know
         * the result — the only 409 that can follow a Stripe call. The card refund may have
         * happened. {@code refusalDetail} carries billing's coded {@code reason}, if any (never its
         * free text).
         */
        BILLING_CONFLICT,
        /**
         * Billing holds no refund under this operation's key and refused the approval (403
         * {@code step_up_invalid}). That one answer covers a bad signature, a params mismatch, an
         * expired token and an already-spent one alike. Only on a same-token retry after an unknown
         * outcome is "spent by the earlier attempt" the likely cause — and that attempt may still be
         * running and refund the card.
         */
        STEP_UP_REFUSED,
        /** The request never left admin-api (connection failed, or the breaker is open): nothing moved. */
        NOT_SENT,
        /** Billing answered 200 {@code success=false}: Stripe failed or cancelled the refund. */
        DECLINED,
        /** Billing's coded 502 {@code stripe_refused_refund}: Stripe refused this POST outright. */
        STRIPE_REFUSED,
        /**
         * Billing refused before any money moved: any 4xx except a 403 {@code step_up_invalid} and a
         * 409 {@code refund_outcome_unknown}. {@code refusalDetail} is its message, if any.
         */
        REFUSED
    }

    /** Convenience constructor for the non-refund writes (no refund figures). */
    public DownstreamResult(boolean success, UUID auditEntryId, String accountStatus) {
        this(success, auditEntryId, accountStatus, 0L, 0L, false, RefundFailure.NONE, null, 0);
    }

    /** Factory for the billing refund result, carrying the requested + actually-debited figures. */
    public static DownstreamResult refund(boolean success, UUID auditEntryId, long requestedCents,
                                          long walletDebitedCents, boolean idempotentReplay) {
        return new DownstreamResult(success, auditEntryId, null, requestedCents,
                walletDebitedCents, idempotentReplay, RefundFailure.NONE, null, 0);
    }

    /** See {@link RefundFailure#OUTCOME_UNKNOWN}. */
    public static DownstreamResult refundOutcomeUnknown() {
        return refundFailed(RefundFailure.OUTCOME_UNKNOWN, null, 0);
    }

    /** See {@link RefundFailure#BILLING_ERROR}. */
    public static DownstreamResult refundBillingError() {
        return refundFailed(RefundFailure.BILLING_ERROR, null, 0);
    }

    /** See {@link RefundFailure#BILLING_CONFLICT}. */
    public static DownstreamResult refundBillingConflict(String billingReason) {
        return refundFailed(RefundFailure.BILLING_CONFLICT, billingReason, 0);
    }

    /** See {@link RefundFailure#STRIPE_REFUSED}. */
    public static DownstreamResult refundStripeRefused() {
        return refundFailed(RefundFailure.STRIPE_REFUSED, null, 0);
    }

    /** See {@link RefundFailure#STEP_UP_REFUSED}. */
    public static DownstreamResult refundStepUpRefused() {
        return refundFailed(RefundFailure.STEP_UP_REFUSED, null, 0);
    }

    /** See {@link RefundFailure#NOT_SENT}. */
    public static DownstreamResult refundNotSent() {
        return refundFailed(RefundFailure.NOT_SENT, null, 0);
    }

    /** See {@link RefundFailure#DECLINED}. */
    public static DownstreamResult refundDeclined() {
        return refundFailed(RefundFailure.DECLINED, null, 0);
    }

    /** See {@link RefundFailure#REFUSED}. */
    public static DownstreamResult refundRefused(int status, String detail) {
        return refundFailed(RefundFailure.REFUSED, detail, status);
    }

    private static DownstreamResult refundFailed(RefundFailure failure, String detail, int status) {
        return new DownstreamResult(false, null, null, 0L, 0L, false, failure, detail, status);
    }
}
