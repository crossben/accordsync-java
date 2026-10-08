package io.github.crossben.accordsync.core;

/**
 * A JSON boolean.
 *
 * @param value the boolean
 */
public record JsonBool(boolean value) implements JsonValue {
    /** {@code true}. */
    public static final JsonBool TRUE = new JsonBool(true);
    /** {@code false}. */
    public static final JsonBool FALSE = new JsonBool(false);

    /**
     * The JSON boolean for {@code value}.
     *
     * @param value the boolean
     * @return {@link #TRUE} or {@link #FALSE}
     */
    public static JsonBool of(boolean value) {
        return value ? TRUE : FALSE;
    }
}
