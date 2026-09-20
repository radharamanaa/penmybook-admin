package dev.bookforge.adminapi.downstream;

import java.util.UUID;

/**
 * Billing {@code POST /internal/admin/payments/{id}/force-resolve-reversed} outcome.
 */
public record ForceResolveReversedCallResult(boolean success, UUID paymentId, long restoredCents) {

    public static ForceResolveReversedCallResult failed() {
        return new ForceResolveReversedCallResult(false, null, 0L);
    }

    public static ForceResolveReversedCallResult ok(UUID paymentId, long restoredCents) {
        return new ForceResolveReversedCallResult(true, paymentId, restoredCents);
    }
}
