package io.github.crossben.accordsync.server;

import java.util.Collection;
import java.util.List;

/**
 * The scope keys a user may read and write.
 *
 * @param read keys the user reads
 * @param write keys the user writes
 */
public record Access(List<String> read, List<String> write) {
    /**
     * Creates it.
     *
     * @param read the read keys
     * @param write the write keys
     */
    public Access {
        read = List.copyOf(read);
        write = List.copyOf(write);
    }

    /**
     * Read and write access to the same keys.
     *
     * @param keys the keys
     * @return the access
     */
    public static Access readWrite(String... keys) {
        return new Access(List.of(keys), List.of(keys));
    }

    /**
     * Separate read and write keys.
     *
     * @param read the read keys
     * @param write the write keys
     * @return the access
     */
    public static Access of(Collection<String> read, Collection<String> write) {
        return new Access(List.copyOf(read), List.copyOf(write));
    }
}
