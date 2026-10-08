package io.github.crossben.accordsync.client;

/** The server answered with a non-2xx status. */
public final class HttpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    /**
     * Creates the exception.
     *
     * @param status the HTTP status
     * @param message what happened, with the response body
     */
    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    /** @return the HTTP status */
    public int status() {
        return status;
    }
}
