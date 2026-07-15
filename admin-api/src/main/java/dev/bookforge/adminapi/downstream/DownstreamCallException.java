package dev.bookforge.adminapi.downstream;

/** Raised when an outbound {@code /internal/*} call cannot complete (transport/serialisation). */
public class DownstreamCallException extends RuntimeException {
    public DownstreamCallException(String message, Throwable cause) {
        super(message, cause);
    }
}
