package dev.bookforge.adminapi.downstream;

/**
 * Raised when an outbound {@code /internal/*} call cannot complete (transport/serialisation), or
 * when the callee answered non-2xx. For a non-2xx answer {@code status} is the HTTP status and
 * {@code detail} / {@code code} / {@code reason} are the callee's RFC 7807 {@code detail} and its
 * {@code code} / {@code reason} properties (null when absent); {@code status} is 0 for a transport failure.
 */
public class DownstreamCallException extends RuntimeException {

    private final int status;
    private final String detail;
    private final String code;
    private final String reason;

    public DownstreamCallException(String message, Throwable cause) {
        this(message, cause, 0, null, null);
    }

    public DownstreamCallException(String message, Throwable cause, int status, String detail,
                                   String code) {
        this(message, cause, status, detail, code, null);
    }

    public DownstreamCallException(String message, Throwable cause, int status, String detail,
                                   String code, String reason) {
        super(message, cause);
        this.status = status;
        this.detail = detail;
        this.code = code;
        this.reason = reason;
    }

    /** The callee's problem {@code reason} property (a finer code), or null when absent. */
    public String reason() {
        return reason;
    }

    public int status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public String code() {
        return code;
    }

    /**
     * True when the request reached the callee and we gave up waiting for its answer — it may still
     * have done the work. A connect timeout is not one: the request never left admin-api.
     */
    public boolean timedOut() {
        return getCause() instanceof java.net.http.HttpTimeoutException
                && !(getCause() instanceof java.net.http.HttpConnectTimeoutException);
    }
}
