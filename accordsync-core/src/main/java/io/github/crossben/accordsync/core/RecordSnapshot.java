package io.github.crossben.accordsync.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A record's state with its history folded away (log compaction, ADR-0008). Each field's snapshot
 * is its JSON form: {@code {"strategy":"lww","winner":{"opId","hlc","value"}|null}},
 * {@code {"strategy":"counter","total":n}}, {@code {"strategy":"set","tags":[[opId,element],...]}}
 * or {@code {"strategy":"conflict","live":[[opId,value],...]}}.
 *
 * @param record the record id
 * @param fields field name to field snapshot
 */
public record RecordSnapshot(String record, Map<String, JsonObject> fields) {
    /**
     * Creates the snapshot.
     *
     * @param record the record id
     * @param fields the field snapshots
     */
    public RecordSnapshot {
        Objects.requireNonNull(record, "record");
        fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
    }

    /**
     * The JSON form, {@code {"record": ..., "fields": {...}}}.
     *
     * @return the JSON object
     */
    public JsonObject toJson() {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("record", new JsonString(record));
        m.put("fields", new JsonObject(new LinkedHashMap<String, JsonValue>(fields)));
        return new JsonObject(m);
    }

    /**
     * Reads the JSON form.
     *
     * @param json the JSON object
     * @return the snapshot
     * @throws AccordException when malformed
     */
    public static RecordSnapshot fromJson(JsonValue json) {
        if (!(json instanceof JsonObject o) || !(o.get("record") instanceof JsonString r)
                || !(o.get("fields") instanceof JsonObject f)) {
            throw new AccordException("record snapshot must be {record, fields}");
        }
        Map<String, JsonObject> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> e : f.members().entrySet()) {
            if (!(e.getValue() instanceof JsonObject fs)) throw new AccordException("field snapshot must be an object");
            FieldState.fromSnapshot(fs); // validates
            fields.put(e.getKey(), fs);
        }
        return new RecordSnapshot(r.value(), fields);
    }
}
