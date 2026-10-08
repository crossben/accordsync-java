package io.github.crossben.accordsync.core;

/**
 * A JSON number with JavaScript semantics: a double. Whole numbers within ±(2^53 - 1) may be held
 * exactly as a {@code long}; that is a representation detail, and equality is numeric: {@code 1}
 * equals {@code 1.0}, {@code 0} equals {@code -0}.
 */
public final class JsonNumber implements JsonValue {
    /** 2^53 - 1, JavaScript's {@code Number.MAX_SAFE_INTEGER}. */
    public static final long MAX_SAFE_INTEGER = 9007199254740991L;

    private final Number value;

    private JsonNumber(Number value) {
        this.value = value;
    }

    /**
     * A whole number. Beyond ±(2^53 - 1) it becomes the nearest double, as in JavaScript.
     *
     * @param value the number
     * @return the JSON number
     */
    public static JsonNumber of(long value) {
        if (value > MAX_SAFE_INTEGER || value < -MAX_SAFE_INTEGER) return new JsonNumber((double) value);
        return new JsonNumber(value);
    }

    /**
     * A double (NaN and the infinities are allowed and print as {@code null}).
     *
     * @param value the number
     * @return the JSON number
     */
    public static JsonNumber of(double value) {
        return new JsonNumber(value);
    }

    /**
     * The number as a double.
     *
     * @return the value
     */
    public double doubleValue() {
        return value.doubleValue();
    }

    /**
     * Whether the number is a whole number within ±(2^53 - 1) ({@code Number.isSafeInteger}).
     *
     * @return true for a safe integer
     */
    public boolean isSafeInteger() {
        if (value instanceof Long) return true;
        double d = value.doubleValue();
        return d == Math.rint(d) && Math.abs(d) <= MAX_SAFE_INTEGER;
    }

    /**
     * The number as a long; only meaningful when {@link #isSafeInteger()}.
     *
     * @return the value
     */
    public long longValue() {
        return value.longValue();
    }

    /**
     * Whether the number is finite.
     *
     * @return false for NaN and the infinities
     */
    public boolean isFinite() {
        return Double.isFinite(value.doubleValue());
    }

    /** The boxed representation, for the encoder. */
    Number raw() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof JsonNumber n && n.doubleValue() == doubleValue();
    }

    @Override
    public int hashCode() {
        double d = doubleValue();
        return Double.hashCode(d == 0 ? 0.0 : d);
    }

    @Override
    public String toString() {
        return Json.number(this);
    }
}
