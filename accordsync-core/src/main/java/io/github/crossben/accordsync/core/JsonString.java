package io.github.crossben.accordsync.core;

import java.util.Objects;

/**
 * A JSON string. Java strings are UTF-16 like JavaScript's, so lone surrogates are kept as they are.
 *
 * @param value the string
 */
public record JsonString(String value) implements JsonValue {
    /**
     * Creates the string value.
     *
     * @param value the string, not null
     */
    public JsonString {
        Objects.requireNonNull(value, "value");
    }
}
