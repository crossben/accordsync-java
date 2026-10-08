package io.github.crossben.accordsync.server;

import java.math.BigInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JavaScript's {@code Number(string)}, for query parameters and op numbers read exactly as the
 * TypeScript server reads them: {@code ""} is 0, {@code "0x10"} is 16, {@code "1e3"} is 1000,
 * surrounding whitespace is ignored.
 */
public final class JsNumber {
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");
    private static final Pattern RADIX = Pattern.compile("0([xXoObB])([0-9a-fA-F]+)");
    private static final long MAX_SAFE = 9007199254740991L;

    private JsNumber() {}

    /**
     * {@code Number(s)}.
     *
     * @param s the text
     * @return the number; NaN when JavaScript gives NaN
     */
    public static double parse(String s) {
        String t = trim(s);
        if (t.isEmpty()) return 0;
        if (t.equals("Infinity") || t.equals("+Infinity")) return Double.POSITIVE_INFINITY;
        if (t.equals("-Infinity")) return Double.NEGATIVE_INFINITY;
        Matcher m = RADIX.matcher(t);
        if (m.matches()) {
            int base = switch (Character.toLowerCase(m.group(1).charAt(0))) {
                case 'x' -> 16;
                case 'o' -> 8;
                default -> 2;
            };
            try {
                return new BigInteger(m.group(2), base).doubleValue();
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        if (DECIMAL.matcher(t).matches()) return Double.parseDouble(t);
        return Double.NaN;
    }

    /**
     * {@code Number(s)} when {@code Number.isSafeInteger} of it, else null.
     *
     * @param s the text
     * @return the integer, or null
     */
    public static Long safeInteger(String s) {
        double n = parse(s);
        if (Double.isNaN(n) || Double.isInfinite(n) || n != Math.rint(n) || Math.abs(n) > MAX_SAFE) return null;
        return (long) n;
    }

    private static boolean isJsSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == 0x0B || c == '\f' || c == '\r' || c == 0xA0 || c == 0x1680
                || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 || c == 0x2029 || c == 0x202F || c == 0x205F
                || c == 0x3000 || c == 0xFEFF;
    }

    private static String trim(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && isJsSpace(s.charAt(a))) a++;
        while (b > a && isJsSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }
}
