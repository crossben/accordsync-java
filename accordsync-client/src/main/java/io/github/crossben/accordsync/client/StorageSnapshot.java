package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.RecordSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * Everything a device has stored.
 *
 * @param meta the device's meta, or empty for a fresh store
 * @param snapshots compacted records (ADR-0008), loaded before ops
 * @param ops every op the device holds (its own and received), in the wire format
 * @param outbox ids of local ops not yet acknowledged by the server
 */
public record StorageSnapshot(Optional<StoredMeta> meta, List<RecordSnapshot> snapshots, List<JsonObject> ops,
        List<String> outbox) {
    /**
     * Creates the snapshot; the lists are copied.
     *
     * @param meta the meta
     * @param snapshots the snapshots
     * @param ops the ops
     * @param outbox the outbox
     */
    public StorageSnapshot {
        snapshots = List.copyOf(snapshots);
        ops = List.copyOf(ops);
        outbox = List.copyOf(outbox);
    }
}
