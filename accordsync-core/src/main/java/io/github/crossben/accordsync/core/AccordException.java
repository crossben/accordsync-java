package io.github.crossben.accordsync.core;

/** Raised when input does not fit Accord's rules: malformed JSON, ops, clocks, ids or schemas. */
public class AccordException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message the reason
     */
    public AccordException(String message) {
        super(message);
    }
}
