package io.github.crossben.accordsync.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON parsing and printing with JavaScript's behaviour. {@link #canonical} equals the TypeScript
 * core's {@code canonicalJson}: {@code JSON.stringify} with object keys sorted, so equal values
 * always print the same string.
 */
public final class Json {
    private Json() {}

    /** Object key order of JavaScript: array indices numerically, then (for canonical JSON) code units. */
    static final Comparator<String> KEY_ORDER = (a, b) -> {
        long ia = arrayIndex(a);
        long ib = arrayIndex(b);
        if (ia >= 0 && ib >= 0) return Long.compare(ia, ib);
        if (ia >= 0) return -1;
        if (ib >= 0) return 1;
        return a.compareTo(b);
    };

    /**
     * Parses JSON text, rejecting anything {@code JSON.parse} rejects.
     *
     * @param text the JSON text
     * @return the value
     * @throws AccordException when the text is not JSON
     */
    public static JsonValue parse(String text) {
        return JsonParser.parse(text);
    }

    /**
     * Canonical JSON: keys that are array indices first in numeric order, then the others by UTF-16
     * code unit; numbers and strings exactly as {@code JSON.stringify} prints them.
     *
     * @param value the value
     * @return the canonical text
     */
    public static String canonical(JsonValue value) {
        StringBuilder out = new StringBuilder();
        write(out, value, true);
        return out.toString();
    }

    /**
     * {@code JSON.stringify} of the value: array-index keys first, then insertion order.
     *
     * @param value the value
     * @return the JSON text
     */
    public static String stringify(JsonValue value) {
        StringBuilder out = new StringBuilder();
        write(out, value, false);
        return out.toString();
    }

    /**
     * Converts plain Java values: null, {@link JsonValue}, String, Boolean, integral and floating
     * {@link Number}s, {@code Map<String, ?>}, {@code Collection<?>} and object arrays.
     *
     * @param value the Java value
     * @return the JSON value
     * @throws AccordException for anything else (including non-string map keys)
     */
    public static JsonValue of(Object value) {
        if (value == null) return JsonNull.INSTANCE;
        if (value instanceof JsonValue v) return v;
        if (value instanceof String s) return new JsonString(s);
        if (value instanceof Boolean b) return JsonBool.of(b);
        if (value instanceof Long || value instanceof Integer || value instanceof Short || value instanceof Byte) {
            return JsonNumber.of(((Number) value).longValue());
        }
        if (value instanceof Number n) return JsonNumber.of(n.doubleValue());
        if (value instanceof Map<?, ?> m) {
            Map<String, JsonValue> members = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!(e.getKey() instanceof String k)) throw new AccordException("JSON object keys must be strings");
                members.put(k, of(e.getValue()));
            }
            return new JsonObject(members);
        }
        if (value instanceof Collection<?> c) {
            List<JsonValue> items = new ArrayList<>();
            for (Object o : c) items.add(of(o));
            return new JsonArray(items);
        }
        if (value instanceof Object[] a) return of(List.of(a));
        throw new AccordException("not a JSON value: " + value.getClass().getName());
    }

    /** The array index a key denotes ("0".."4294967294", no leading zeros), or -1. */
    static long arrayIndex(String key) {
        int n = key.length();
        if (n == 0 || n > 10) return -1;
        if (n > 1 && key.charAt(0) == '0') return -1;
        long v = 0;
        for (int k = 0; k < n; k++) {
            char c = key.charAt(k);
            if (c < '0' || c > '9') return -1;
            v = v * 10 + (c - '0');
        }
        return v <= 4294967294L ? v : -1;
    }

    private static void write(StringBuilder out, JsonValue value, boolean sorted) {
        if (value instanceof JsonNull) out.append("null");
        else if (value instanceof JsonBool b) out.append(b.value());
        else if (value instanceof JsonNumber n) out.append(number(n));
        else if (value instanceof JsonString s) quote(out, s.value());
        else if (value instanceof JsonArray a) {
            out.append('[');
            boolean first = true;
            for (JsonValue v : a.items()) {
                if (!first) out.append(',');
                first = false;
                write(out, v, sorted);
            }
            out.append(']');
        } else {
            Map<String, JsonValue> members = ((JsonObject) value).members();
            List<String> keys = new ArrayList<>(members.keySet());
            if (sorted) keys.sort(KEY_ORDER);
            else keys.sort(Comparator.comparingLong(Json::indexOrMax));
            out.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) out.append(',');
                first = false;
                quote(out, k);
                out.append(':');
                write(out, members.get(k), sorted);
            }
            out.append('}');
        }
    }

    private static long indexOrMax(String key) {
        long i = arrayIndex(key);
        return i >= 0 ? i : Long.MAX_VALUE;
    }

    /** Quotes a string as {@code JSON.stringify} does (well-formed: lone surrogates escaped). */
    static void quote(StringBuilder out, String s) {
        out.append('"');
        int n = s.length();
        for (int k = 0; k < n; k++) {
            char c = s.charAt(k);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        unicodeEscape(out, c);
                    } else if (Character.isHighSurrogate(c)) {
                        if (k + 1 < n && Character.isLowSurrogate(s.charAt(k + 1))) {
                            out.append(c).append(s.charAt(++k));
                        } else {
                            unicodeEscape(out, c);
                        }
                    } else if (Character.isLowSurrogate(c)) {
                        unicodeEscape(out, c);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static void unicodeEscape(StringBuilder out, char c) {
        String hex = Integer.toHexString(c);
        out.append("\\u");
        for (int k = hex.length(); k < 4; k++) out.append('0');
        out.append(hex);
    }

    /**
     * A number exactly as JavaScript's {@code JSON.stringify} prints it ({@code null} when not finite).
     *
     * @param n the number
     * @return the text
     */
    public static String number(JsonNumber n) {
        if (n.raw() instanceof Long l) return Long.toString(l);
        return number(n.doubleValue());
    }

    /**
     * A double exactly as JavaScript's {@code JSON.stringify} prints it: ECMA-262 Number::toString
     * with the shortest round-trip digits; NaN and the infinities print {@code null}.
     *
     * @param d the number
     * @return the text
     */
    public static String number(double d) {
        if (!Double.isFinite(d)) return "null";
        if (d == 0) return "0";
        if (d < 0) return "-" + number(-d);
        BigDecimal exact = new BigDecimal(d);
        BigDecimal pick = null;
        for (int p = 1; p <= 17 && pick == null; p++) {
            BigDecimal lo = exact.round(new MathContext(p, RoundingMode.FLOOR));
            BigDecimal hi = exact.round(new MathContext(p, RoundingMode.CEILING));
            boolean okLo = lo.doubleValue() == d;
            boolean okHi = hi.doubleValue() == d;
            if (okLo && okHi) {
                int c = exact.subtract(lo).compareTo(hi.subtract(exact));
                if (c < 0) pick = lo;
                else if (c > 0) pick = hi;
                else pick = lo.unscaledValue().testBit(0) ? hi : lo;
            } else if (okLo) {
                pick = lo;
            } else if (okHi) {
                pick = hi;
            }
        }
        if (pick == null) throw new IllegalStateException("no round-trip digits for " + d);
        pick = pick.stripTrailingZeros();
        String digits = pick.unscaledValue().toString();
        int k = digits.length();
        int e = k - pick.scale(); // value = 0.digits × 10^e, ECMA-262's n
        StringBuilder out = new StringBuilder();
        if (k <= e && e <= 21) {
            out.append(digits);
            for (int z = 0; z < e - k; z++) out.append('0');
        } else if (0 < e && e <= 21) {
            out.append(digits, 0, e).append('.').append(digits, e, k);
        } else if (-6 < e && e <= 0) {
            out.append("0.");
            for (int z = 0; z < -e; z++) out.append('0');
            out.append(digits);
        } else {
            out.append(digits.charAt(0));
            if (k > 1) out.append('.').append(digits, 1, k);
            int x = e - 1;
            out.append('e').append(x < 0 ? '-' : '+').append(Math.abs(x));
        }
        return out.toString();
    }
}
