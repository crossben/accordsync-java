package io.github.crossben.accordsync.client;

/**
 * Durable storage for a device. {@link #commit} must be atomic: after a crash, either all of a
 * transaction is visible or none of it. Implementations must be safe to call from several threads.
 */
public interface StorageAdapter extends AutoCloseable {
    /**
     * Everything stored.
     *
     * @return the stored state (copies, never live references)
     */
    StorageSnapshot load();

    /**
     * Applies one transaction atomically.
     *
     * @param tx the transaction
     */
    void commit(StorageTx tx);

    /** Releases resources. */
    @Override
    void close();
}
