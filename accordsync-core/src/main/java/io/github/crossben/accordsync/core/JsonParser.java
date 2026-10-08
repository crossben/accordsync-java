package io.github.crossben.accordsync.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A strict JSON parser (RFC 8259, as {@code JSON.parse} accepts it) into the core's value model. */
final class JsonParser {
    private static final int MAX_DEPTH = 1000;

    private final String s;
    private int i;

    private JsonParser(String s) {
        this.s = s;
    }

    static JsonValue parse(String text) {
        JsonParser p = new JsonParser(text);
        p.ws();
        JsonValue v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw p.error("unexpected data after JSON");
        return v;
    }

    private AccordException error(String what) {
        return new AccordException("invalid JSON: " + what + " at position " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private JsonValue value(int depth) {
        if (depth > MAX_DEPTH) throw error("nesting too deep");
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return new JsonString(string());
            case 't':
                literal("true");
                return JsonBool.TRUE;
            case 'f':
                literal("false");
                return JsonBool.FALSE;
            case 'n':
                literal("null");
                return JsonNull.INSTANCE;
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character");
        }
    }

    private void literal(String word) {
        if (!s.startsWith(word, i)) throw error("unexpected token");
        i += word.length();
    }

    private JsonObject object(int depth) {
        i++;
        Map<String, JsonValue> members = new LinkedHashMap<>();
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return new JsonObject(members);
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw error("expected a string key");
            String key = string();
            ws();
            expect(':');
            ws();
            members.put(key, value(depth + 1));
            ws();
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return new JsonObject(members);
            if (c != ',') throw error("expected , or }");
        }
    }

    private JsonArray array(int depth) {
        i++;
        List<JsonValue> items = new ArrayList<>();
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return new JsonArray(items);
        }
        while (true) {
            ws();
            items.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return new JsonArray(items);
            if (c != ',') throw error("expected , or ]");
        }
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) throw error("expected " + c);
        i++;
    }

    private String string() {
        i++;
        StringBuilder out = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw error("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return out.toString();
            if (c < 0x20) throw error("control character in string");
            if (c != '\\') {
                out.append(c);
                continue;
            }
            if (i >= s.length()) throw error("unterminated string");
            char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw error("bad unicode escape");
                    int code = 0;
                    for (int k = 0; k < 4; k++) {
                        int h = hex(s.charAt(i++));
                        if (h < 0) throw error("bad unicode escape");
                        code = code * 16 + h;
                    }
                    out.append((char) code);
                }
                default -> throw error("bad escape");
            }
        }
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    private JsonNumber number() {
        int start = i;
        if (s.charAt(i) == '-') i++;
        if (i >= s.length()) throw error("bad number");
        if (s.charAt(i) == '0') i++;
        else if (s.charAt(i) >= '1' && s.charAt(i) <= '9') digits();
        else throw error("bad number");
        boolean whole = true;
        if (i < s.length() && s.charAt(i) == '.') {
            i++;
            whole = false;
            if (digits() == 0) throw error("bad number");
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            i++;
            whole = false;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            if (digits() == 0) throw error("bad number");
        }
        String lexeme = s.substring(start, i);
        if (whole && lexeme.length() <= 17 && !lexeme.equals("-0")) {
            long v = Long.parseLong(lexeme);
            if (Math.abs(v) <= JsonNumber.MAX_SAFE_INTEGER) return JsonNumber.of(v);
        }
        return JsonNumber.of(Double.parseDouble(lexeme));
    }

    private int digits() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        return i - start;
    }
}
