package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a device must remember between launches.
 *
 * @param deviceId the device id
 * @param cursor the pull cursor: server feed position already applied
 * @param hlc the last clock, encoded
 * @param seq the last op sequence number, so op ids are never reused after a restart
 */
public record StoredMeta(String deviceId, long cursor, String hlc, long seq) {
    /**
     * Creates the meta.
     *
     * @param deviceId the device id
     * @param cursor the cursor
     * @param hlc the clock
     * @param seq the sequence number
     */
    public StoredMeta {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(hlc, "hlc");
    }

    /**
     * The TypeScript shape ({@code deviceId}, {@code cursor}, {@code hlc}, {@code seq}), as stored
     * in {@code accord_meta}.
     *
     * @return the JSON object
     */
    public JsonObject toJson() {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("deviceId", new JsonString(deviceId));
        m.put("cursor", JsonNumber.of(cursor));
        m.put("hlc", new JsonString(hlc));
        m.put("seq", JsonNumber.of(seq));
        return new JsonObject(m);
    }

    /**
     * Reads the TypeScript shape.
     *
     * @param json the JSON value
     * @return the meta
     * @throws AccordException when malformed
     */
    public static StoredMeta fromJson(JsonValue json) {
        if (!(json instanceof JsonObject o)
                || !(o.get("deviceId") instanceof JsonString d)
                || !(o.get("cursor") instanceof JsonNumber c) || !c.isSafeInteger()
                || !(o.get("hlc") instanceof JsonString h)
                || !(o.get("seq") instanceof JsonNumber s) || !s.isSafeInteger()) {
            throw new AccordException("stored meta must be {deviceId, cursor, hlc, seq}");
        }
        return new StoredMeta(d.value(), c.longValue(), h.value(), s.longValue());
    }
}
