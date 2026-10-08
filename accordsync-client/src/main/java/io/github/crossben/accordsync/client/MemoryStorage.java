package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.RecordSnapshot;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Keeps everything in memory: for tests, and for apps that accept losing unsynced writes on
 * restart. Values are immutable JSON, so {@link #load} never hands out live mutable state.
 */
public final class MemoryStorage implements StorageAdapter {
    private final Map<String, JsonObject> ops = new LinkedHashMap<>();
    private final Map<String, RecordSnapshot> snapshots = new LinkedHashMap<>();
    private final Set<String> outbox = new LinkedHashSet<>();
    private StoredMeta meta;

    /** Empty storage. */
    public MemoryStorage() {}

    @Override
    public synchronized StorageSnapshot load() {
        return new StorageSnapshot(Optional.ofNullable(meta), new ArrayList<>(snapshots.values()),
                new ArrayList<>(ops.values()), new ArrayList<>(outbox));
    }

    @Override
    public synchronized void commit(StorageTx tx) {
        List<String> ids = new ArrayList<>(); // validated before any change
        for (JsonObject o : tx.putOps()) ids.add(JdbcStorage.opId(o));
        if (tx.clearOps()) {
            ops.clear();
            snapshots.clear();
        }
        for (String r : tx.deleteSnapshots()) snapshots.remove(r);
        for (RecordSnapshot s : tx.putSnapshots()) snapshots.put(s.record(), s);
        for (String id : tx.deleteOps()) ops.remove(id);
        for (int k = 0; k < ids.size(); k++) ops.put(ids.get(k), tx.putOps().get(k));
        outbox.addAll(tx.outboxAdd());
        tx.outboxDelete().forEach(outbox::remove);
        tx.meta().ifPresent(m -> meta = m);
    }

    @Override
    public void close() {}
}
