package io.github.crossben.accordsync.core;

import java.util.List;
import java.util.Objects;

/**
 * Writes a value. For {@code lww} the highest clock wins. For {@code conflict}, {@code deps} lists
 * the values the writer could see; the assign supersedes exactly those (ADR-0003).
 *
 * @param opId the op id
 * @param record the record id
 * @param field the field
 * @param hlc the clock
 * @param value the value (JSON null is a value)
 * @param deps op ids of the values superseded
 */
public record AssignOp(String opId, String record, String field, Hlc hlc, JsonValue value, List<String> deps)
        implements Op {
    /**
     * Creates the op.
     *
     * @param opId the op id
     * @param record the record id
     * @param field the field
     * @param hlc the clock
     * @param value the value
     * @param deps the deps
     */
    public AssignOp {
        Objects.requireNonNull(opId, "opId");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(hlc, "hlc");
        Objects.requireNonNull(value, "value");
        deps = List.copyOf(deps);
    }

    @Override
    public String kind() {
        return "assign";
    }
}
