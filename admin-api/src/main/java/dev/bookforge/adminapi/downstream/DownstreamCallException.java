package dev.bookforge.adminapi.downstream;

/**
 * Raised when an outbound {@code /internal/*} call cannot complete (transport/serialisation), or
 * when the callee answered non-2xx. For a non-2xx answer {@code status} is the HTTP status and
 * {@code detail} / {@code code} are the callee's RFC 7807 {@code detail} and {@code code} (null
 * when absent); {@code status} is 0 for a transport failure.
 */
public class DownstreamCallException extends RuntimeException {

    private final int status;
    private final String detail;
    private final String code;

    public DownstreamCallException(String message, Throwable cause) {
        this(message, cause, 0, null, null);
    }

    public DownstreamCallException(String message, Throwable cause, int status, String detail,
                                   String code) {
        super(message, cause);
        this.status = status;
        this.detail = detail;
        this.code = code;
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
