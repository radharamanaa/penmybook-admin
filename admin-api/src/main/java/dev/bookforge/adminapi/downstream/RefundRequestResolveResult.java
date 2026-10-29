package dev.bookforge.adminapi.downstream;

/**
 * Billing's outcome for {@code POST /internal/refund-requests/{id}/resolve}. Distinct from
 * {@link DownstreamResult} so the shared write envelope used by grant/suspend is not widened.
 */
public record RefundRequestResolveResult(
        boolean success,
        String status,
        Long approvedAmountCents,
        long refundedCents
) {
    public static RefundRequestResolveResult failed() {
        return new RefundRequestResolveResult(false, null, null, 0L);
    }
}
