package io.github.crossben.accordsync.server;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.Op;
import io.github.crossben.accordsync.core.OpHash;
import io.github.crossben.accordsync.core.Wire;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The hash the server stores for compacted ops ({@code compacted_ops.op_hash}) matches every server's. */
class OpHashVectorsTest {
    @Test
    void hashesMatchTheContractVectors() throws Exception {
        Path file = Path.of(System.getProperty("accord.contract", "../contract"), "vectors", "op-hash", "op-hash.json");
        JsonObject doc = (JsonObject) Json.parse(Files.readString(file));
        List<JsonValue> cases = ((JsonArray) doc.get("cases")).items();
        assertThat(cases).isNotEmpty();
        for (JsonValue v : cases) {
            JsonObject c = (JsonObject) v;
            Op op = Wire.decode(c.get("op"));
            assertThat(Json.canonical(Wire.encode(op))).as(name(c)).isEqualTo(((JsonString) c.get("canonical")).value());
            assertThat(OpHash.of(op)).as(name(c)).isEqualTo(((JsonString) c.get("hash")).value());
        }
    }

    @Test
    void lonePathNamesWhereTheSurrogateHides() {
        assertThat(Sync.lonePath(new JsonString("ok 😀"), "op")).isNull();
        JsonObject op = new JsonObject(Map.of("value", new JsonArray(List.of(new JsonString("a"), new JsonString("\uD800")))));
        assertThat(Sync.lonePath(op, "op")).isEqualTo("op.value[1]");
        assertThat(Sync.lonePath(new JsonObject(Map.of("\uDC00", new JsonString("x"))), "op")).isEqualTo("op (a key)");
    }

    private static String name(JsonObject c) {
        return ((JsonString) c.get("name")).value();
    }
}
