package io.github.crossben.accordsync.server;

import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;

/**
 * A record as scope functions see it: its id and its current field values (as the app reads them).
 *
 * @param id the record id, e.g. {@code dossier:91}
 * @param fields the current fields
 */
public record ScopedRecord(String id, JsonObject fields) {
    /**
     * A field's value.
     *
     * @param name the field
     * @return the value, or null when never written
     */
    public JsonValue field(String name) {
        return fields.get(name);
    }

    /**
     * A field's value when it is a string.
     *
     * @param name the field
     * @return the string, or null when missing or not a string
     */
    public String string(String name) {
        return fields.get(name) instanceof JsonString s ? s.value() : null;
    }
}
