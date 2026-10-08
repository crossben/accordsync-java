package io.github.crossben.accordsync.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/** Reads contract files through the core's own parser. */
final class Contract {
    private Contract() {}

    static JsonObject read(String path) {
        try {
            return (JsonObject) Json.parse(Files.readString(ContractTest.contract().resolve(path), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static Schema schema(JsonObject json) {
        Map<String, Map<String, Object>> def = new LinkedHashMap<>();
        for (var t : json.members().entrySet()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (var f : ((JsonObject) t.getValue()).members().entrySet()) fields.put(f.getKey(), ((JsonString) f.getValue()).value());
            def.put(t.getKey(), fields);
        }
        return Schema.define(def);
    }

    static String str(JsonValue v) {
        return ((JsonString) v).value();
    }
}
