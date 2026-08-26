package dev.bookforge.adminapi.downstream;

/**
 * Billing's outcome for {@code POST /internal/refund-residuals/settle}. HTTP 200 is success
 * even when {@code settled=false} (already-resolved replay).
 */
public record RefundResidualSettleResult(boolean success) {
    public static RefundResidualSettleResult failed() {
        return new RefundResidualSettleResult(false);
    }
}
