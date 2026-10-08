package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.RecordSnapshot;
import java.util.Objects;

/** One item of a pull page. */
public sealed interface PullItem {
    /**
     * An op, in the wire format.
     *
     * @param op the op
     */
    record OpItem(JsonObject op) implements PullItem {
        /**
         * Creates the item.
         *
         * @param op the op
         */
        public OpItem {
            Objects.requireNonNull(op, "op");
        }
    }

    /**
     * A compacted record (ADR-0008).
     *
     * @param snapshot the snapshot
     */
    record SnapshotItem(RecordSnapshot snapshot) implements PullItem {
        /**
         * Creates the item.
         *
         * @param snapshot the snapshot
         */
        public SnapshotItem {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    /**
     * The record left this device's read scope (ADR-0004).
     *
     * @param record the record id
     */
    record ExitItem(String record) implements PullItem {
        /**
         * Creates the item.
         *
         * @param record the record
         */
        public ExitItem {
            Objects.requireNonNull(record, "record");
        }
    }

    /**
     * Reads {@code {"type":"op","op"}}, {@code {"type":"snapshot","snapshot"}} or
     * {@code {"type":"exit","record"}}.
     *
     * @param json the item
     * @return the item
     * @throws AccordException when malformed
     */
    static PullItem fromJson(JsonValue json) {
        if (json instanceof JsonObject o && o.get("type") instanceof JsonString t) {
            switch (t.value()) {
                case "op" -> {
                    if (o.get("op") instanceof JsonObject op) return new OpItem(op);
                }
                case "snapshot" -> {
                    return new SnapshotItem(RecordSnapshot.fromJson(o.get("snapshot")));
                }
                case "exit" -> {
                    if (o.get("record") instanceof JsonString r) return new ExitItem(r.value());
                }
                default -> throw new AccordException("unknown pull item type \"" + t.value() + "\"");
            }
        }
        throw new AccordException("malformed pull item");
    }
}
