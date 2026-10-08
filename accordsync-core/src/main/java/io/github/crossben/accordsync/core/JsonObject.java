package io.github.crossben.accordsync.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A JSON object: string keys (a key {@code "10"} stays a string) in insertion order. Equality
 * ignores order, as for JavaScript objects.
 *
 * @param members the members, copied into an unmodifiable insertion-ordered map
 */
public record JsonObject(Map<String, JsonValue> members) implements JsonValue {
    /** The empty object. */
    public static final JsonObject EMPTY = new JsonObject(Map.of());

    /**
     * Creates the object.
     *
     * @param members the members; no null keys or values
     */
    public JsonObject {
        LinkedHashMap<String, JsonValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> e : members.entrySet()) {
            copy.put(Objects.requireNonNull(e.getKey(), "key"), Objects.requireNonNull(e.getValue(), "value"));
        }
        members = Collections.unmodifiableMap(copy);
    }

    /**
     * The value under {@code key}.
     *
     * @param key the key
     * @return the value, or null when absent
     */
    public JsonValue get(String key) {
        return members.get(key);
    }

    /**
     * Whether the object has {@code key}.
     *
     * @param key the key
     * @return true when present
     */
    public boolean has(String key) {
        return members.containsKey(key);
    }
}
