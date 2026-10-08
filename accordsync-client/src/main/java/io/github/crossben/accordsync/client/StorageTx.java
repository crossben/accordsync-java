package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.RecordSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One atomic change. Applied in this order: clear, delete snapshots, put snapshots, delete ops, put
 * ops, outbox additions, outbox removals, meta.
 *
 * @param clearOps removes every op and snapshot
 * @param deleteSnapshots records whose snapshot is removed
 * @param putSnapshots snapshots stored (replacing the record's previous one)
 * @param deleteOps op ids removed
 * @param putOps ops stored, in the wire format
 * @param outboxAdd op ids added to the outbox
 * @param outboxDelete op ids removed from the outbox
 * @param meta the meta to store, or empty to keep the stored one
 */
public record StorageTx(boolean clearOps, List<String> deleteSnapshots, List<RecordSnapshot> putSnapshots,
        List<String> deleteOps, List<JsonObject> putOps, List<String> outboxAdd, List<String> outboxDelete,
        Optional<StoredMeta> meta) {
    /**
     * Creates the transaction; the lists are copied.
     *
     * @param clearOps clear
     * @param deleteSnapshots snapshots to delete
     * @param putSnapshots snapshots to put
     * @param deleteOps ops to delete
     * @param putOps ops to put
     * @param outboxAdd outbox additions
     * @param outboxDelete outbox removals
     * @param meta the meta
     */
    public StorageTx {
        deleteSnapshots = List.copyOf(deleteSnapshots);
        putSnapshots = List.copyOf(putSnapshots);
        deleteOps = List.copyOf(deleteOps);
        putOps = List.copyOf(putOps);
        outboxAdd = List.copyOf(outboxAdd);
        outboxDelete = List.copyOf(outboxDelete);
        meta = meta == null ? Optional.empty() : meta;
    }

    /** @return a builder for an empty transaction */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The same transaction with {@code meta}.
     *
     * @param m the meta
     * @return the new transaction
     */
    public StorageTx withMeta(StoredMeta m) {
        return new StorageTx(clearOps, deleteSnapshots, putSnapshots, deleteOps, putOps, outboxAdd, outboxDelete,
                Optional.of(m));
    }

    /** Builds a {@link StorageTx}. */
    public static final class Builder {
        private boolean clearOps;
        private final List<String> deleteSnapshots = new ArrayList<>();
        private final List<RecordSnapshot> putSnapshots = new ArrayList<>();
        private final List<String> deleteOps = new ArrayList<>();
        private final List<JsonObject> putOps = new ArrayList<>();
        private final List<String> outboxAdd = new ArrayList<>();
        private final List<String> outboxDelete = new ArrayList<>();
        private StoredMeta meta;

        private Builder() {}

        /** @return this builder, clearing every op and snapshot */
        public Builder clearOps() {
            clearOps = true;
            return this;
        }

        /**
         * @param records records whose snapshot is removed
         * @return this builder
         */
        public Builder deleteSnapshots(List<String> records) {
            deleteSnapshots.addAll(records);
            return this;
        }

        /**
         * @param snaps snapshots to store
         * @return this builder
         */
        public Builder putSnapshots(List<RecordSnapshot> snaps) {
            putSnapshots.addAll(snaps);
            return this;
        }

        /**
         * @param ids op ids to remove
         * @return this builder
         */
        public Builder deleteOps(List<String> ids) {
            deleteOps.addAll(ids);
            return this;
        }

        /**
         * @param ops wire ops to store
         * @return this builder
         */
        public Builder putOps(List<JsonObject> ops) {
            putOps.addAll(ops);
            return this;
        }

        /**
         * @param ids op ids to add to the outbox
         * @return this builder
         */
        public Builder outboxAdd(List<String> ids) {
            outboxAdd.addAll(ids);
            return this;
        }

        /**
         * @param ids op ids to remove from the outbox
         * @return this builder
         */
        public Builder outboxDelete(List<String> ids) {
            outboxDelete.addAll(ids);
            return this;
        }

        /**
         * @param m the meta to store
         * @return this builder
         */
        public Builder meta(StoredMeta m) {
            meta = m;
            return this;
        }

        /** @return the transaction */
        public StorageTx build() {
            return new StorageTx(clearOps, deleteSnapshots, putSnapshots, deleteOps, putOps, outboxAdd, outboxDelete,
                    Optional.ofNullable(meta));
        }
    }
}
