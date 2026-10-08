package io.github.crossben.accordsync.client;

/** A storage backend failed (the cause says why). */
public final class StorageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed
     * @param cause the cause
     */
    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
