package io.github.crossben.accordsync.core;

/** A remote clock is further ahead of local time than the allowed skew. */
public final class ClockSkewException extends AccordException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the reason
     */
    public ClockSkewException(String message) {
        super(message);
    }
}
