package dev.bookforge.adminapi.downstream;

/**
 * A downstream {@code /internal/*} call returned HTTP 409 Conflict — a legitimate business-state
 * rejection (e.g. billing refusing a refund because one is already awaiting card credit), NOT a
 * transport/outage failure. Subclass of {@link DownstreamCallException} so existing generic catches
 * still handle it, while callers that care (the refund path) can distinguish it and surface a
 * "not-a-failure, already in progress" outcome instead of a bare "call failed".
 */
public class DownstreamConflictException extends DownstreamCallException {
    public DownstreamConflictException(String message) {
        super(message, null, 409, null, null);
    }

    public DownstreamConflictException(String message, String detail, String code) {
        super(message, null, 409, detail, code);
    }
}
