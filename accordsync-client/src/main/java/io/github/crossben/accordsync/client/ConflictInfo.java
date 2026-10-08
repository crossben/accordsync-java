package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.JsonValue;
import java.util.List;

/**
 * A {@code conflict} field holding more than one value.
 *
 * @param record the record id
 * @param field the field
 * @param values the values, sorted by op id
 */
public record ConflictInfo(String record, String field, List<Value> values) {
    /**
     * Creates the info; the list is copied.
     *
     * @param record the record
     * @param field the field
     * @param values the values
     */
    public ConflictInfo {
        values = List.copyOf(values);
    }

    /**
     * One of the values, and the op that wrote it.
     *
     * @param value the value
     * @param opId the op id
     */
    public record Value(JsonValue value, String opId) {}
}
