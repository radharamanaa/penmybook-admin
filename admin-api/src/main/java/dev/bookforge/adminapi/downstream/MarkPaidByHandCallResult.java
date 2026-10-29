package dev.bookforge.adminapi.downstream;

import dev.bookforge.adminapi.dto.RefundRequestQueueEntry;

/**
 * Billing {@code POST /internal/admin/refund-requests/{id}/mark-paid-by-hand} outcome. On a refusal
 * billing answered (4xx) {@code refusedStatus} is that status and {@code refusedMessage} its
 * detail, shown to staff verbatim; a transport failure has neither.
 */
public record MarkPaidByHandCallResult(
        boolean success,
        RefundRequestQueueEntry request,
        int refusedStatus,
        String refusedCode,
        String refusedMessage) {

    public static MarkPaidByHandCallResult ok(RefundRequestQueueEntry request) {
        return new MarkPaidByHandCallResult(true, request, 0, null, null);
    }

    public static MarkPaidByHandCallResult refused(int status, String code, String message) {
        return new MarkPaidByHandCallResult(false, null, status, code, message);
    }

    public static MarkPaidByHandCallResult failed() {
        return new MarkPaidByHandCallResult(false, null, 0, null, null);
    }
}
