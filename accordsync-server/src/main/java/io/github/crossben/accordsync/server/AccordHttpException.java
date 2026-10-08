package io.github.crossben.accordsync.server;

/** A request answered with a 4xx status and {@code {"error": message}}. */
public class AccordHttpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** The status code. */
    private final int status;

    /** Milliseconds to wait (429 only). */
    private final long retryAfterMs;

    /**
     * Creates it.
     *
     * @param status the status
     * @param message the error message
     */
    public AccordHttpException(int status, String message) {
        this(status, message, 0);
    }

    AccordHttpException(int status, String message, long retryAfterMs) {
        super(message);
        this.status = status;
        this.retryAfterMs = retryAfterMs;
    }

    /** @return the status code */
    public int status() {
        return status;
    }

    /** @return milliseconds to wait (429) */
    public long retryAfterMs() {
        return retryAfterMs;
    }

    static AccordHttpException badRequest(String m) {
        return new AccordHttpException(400, m);
    }

    static AccordHttpException unauthorized(String m) {
        return new AccordHttpException(401, m);
    }

    static AccordHttpException forbidden(String m) {
        return new AccordHttpException(403, m);
    }

    static AccordHttpException tooManyRequests(long waitMs) {
        return new AccordHttpException(429, "too many requests", waitMs);
    }
}
