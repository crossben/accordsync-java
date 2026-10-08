package io.github.crossben.accordsync.core;

import java.util.List;
import java.util.Objects;

/**
 * Removes the add-tags in {@code deps} (the ones the writer had seen); concurrent adds survive.
 *
 * @param opId the op id
 * @param record the record id
 * @param field the field
 * @param hlc the clock
 * @param element a {@link JsonString} or finite {@link JsonNumber}
 * @param deps tags removed
 */
public record RemoveOp(String opId, String record, String field, Hlc hlc, JsonValue element, List<String> deps)
        implements Op {
    /**
     * Creates the op.
     *
     * @param opId the op id
     * @param record the record id
     * @param field the field
     * @param hlc the clock
     * @param element the element
     * @param deps the deps
     */
    public RemoveOp {
        Objects.requireNonNull(opId, "opId");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(hlc, "hlc");
        Objects.requireNonNull(element, "element");
        deps = List.copyOf(deps);
    }

    @Override
    public String kind() {
        return "remove";
    }
}
