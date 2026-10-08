package io.github.crossben.accordsync.core;

import java.util.Objects;

/**
 * Adds {@code by} (a positive or negative integer within ±(2^53 - 1)) to a counter.
 *
 * @param opId the op id
 * @param record the record id
 * @param field the field
 * @param hlc the clock
 * @param by the increment
 */
public record IncOp(String opId, String record, String field, Hlc hlc, long by) implements Op {
    /**
     * Creates the op.
     *
     * @param opId the op id
     * @param record the record id
     * @param field the field
     * @param hlc the clock
     * @param by the increment
     */
    public IncOp {
        Objects.requireNonNull(opId, "opId");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(hlc, "hlc");
    }

    @Override
    public String kind() {
        return "inc";
    }
}
