package io.github.crossben.accordsync.core;

import java.util.List;
import java.util.Objects;

/**
 * Adds an element to a set. The op id is the element's tag; {@code deps} lists the element's tags
 * the writer could see, which the add replaces (empty for a first add).
 *
 * @param opId the op id
 * @param record the record id
 * @param field the field
 * @param hlc the clock
 * @param element a {@link JsonString} or finite {@link JsonNumber}
 * @param deps tags replaced
 */
public record AddOp(String opId, String record, String field, Hlc hlc, JsonValue element, List<String> deps)
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
    public AddOp {
        Objects.requireNonNull(opId, "opId");
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(hlc, "hlc");
        Objects.requireNonNull(element, "element");
        deps = List.copyOf(deps);
    }

    @Override
    public String kind() {
        return "add";
    }
}
