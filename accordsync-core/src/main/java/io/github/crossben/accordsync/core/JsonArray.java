package io.github.crossben.accordsync.core;

import java.util.List;

/**
 * A JSON array.
 *
 * @param items the elements, copied into an unmodifiable list
 */
public record JsonArray(List<JsonValue> items) implements JsonValue {
    /** The empty array. */
    public static final JsonArray EMPTY = new JsonArray(List.of());

    /**
     * Creates the array.
     *
     * @param items the elements, none null
     */
    public JsonArray {
        items = List.copyOf(items);
    }

    /**
     * An array of the given values.
     *
     * @param items the elements
     * @return the array
     */
    public static JsonArray of(JsonValue... items) {
        return new JsonArray(List.of(items));
    }
}
