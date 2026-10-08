package io.github.crossben.accordsync.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WireTest {
    static final AssignOp OP = new AssignOp("dev-7f3a:1042", "dossier:91", "status",
            new Hlc(1727871000123L, 4, "dev-7f3a"), new JsonString("submitted"), List.of("dev-7f3a:1041"));

    static JsonObject with(JsonObject o, String key, JsonValue v) {
        Map<String, JsonValue> m = new LinkedHashMap<>(o.members());
        if (v == null) m.remove(key);
        else m.put(key, v);
        return new JsonObject(m);
    }

    @Test
    void roundTripsAnOp() {
        assertThat(Wire.decode(Json.parse(Json.stringify(Wire.encode(OP))))).isEqualTo(OP);
    }

    @Test
    void matchesTheDocumentedShape() {
        assertThat(Json.stringify(Wire.encode(OP))).isEqualTo("{\"op_id\":\"dev-7f3a:1042\",\"record\":\"dossier:91\","
                + "\"field\":\"status\",\"hlc\":\"1727871000123:00004:dev-7f3a\",\"kind\":\"assign\","
                + "\"value\":\"submitted\",\"deps\":[\"dev-7f3a:1041\"]}");
    }

    @Test
    void addOmitsDepsWhenEmptyAndDecodesWithout() {
        AddOp add = new AddOp("a:1", "dossier:1", "docs", new Hlc(1, 0, "a"), new JsonString("x"), List.of());
        JsonObject wire = Wire.encode(add);
        assertThat(wire.has("deps")).isFalse();
        assertThat(Wire.decode(wire)).isEqualTo(add);
        AddOp readd = new AddOp("a:2", "dossier:1", "docs", new Hlc(2, 0, "a"), JsonNumber.of(1.5), List.of("a:1"));
        assertThat(Wire.encode(readd).get("deps")).isEqualTo(JsonArray.of(new JsonString("a:1")));
        assertThat(Wire.decode(Wire.encode(readd))).isEqualTo(readd);
    }

    @Test
    void acceptsAWholeDoubleForBy() {
        JsonObject inc = (JsonObject) Json.parse("{\"op_id\":\"a:1\",\"record\":\"d:1\",\"field\":\"v\",\"hlc\":\"1:00000:a\",\"kind\":\"inc\",\"by\":3.0}");
        assertThat(((IncOp) Wire.decode(inc)).by()).isEqualTo(3);
        assertThat(((IncOp) Wire.decode(with(inc, "by", Json.parse("-9007199254740991")))).by()).isEqualTo(-9007199254740991L);
        assertThat(Json.canonical(Wire.encode(Wire.decode(inc)))).contains("\"by\":3");
    }

    @Test
    void assignKeepsNullAsAValue() {
        JsonObject wire = with(Wire.encode(OP), "value", JsonNull.INSTANCE);
        assertThat(((AssignOp) Wire.decode(wire)).value()).isEqualTo(JsonNull.INSTANCE);
    }

    @Test
    void recordIdsAllow256Utf16CodeUnits() {
        String astral = "\ud83d\ude00".repeat(128); // 256 code units, 128 code points
        assertThat(OpId.recordType("t:" + astral)).isEqualTo("t");
        assertThatThrownBy(() -> OpId.recordType("t:" + astral + "x")).isInstanceOf(AccordException.class);
        assertThat(OpId.recordType("t:a\nb:c")).isEqualTo("t");
        assertThat(OpId.recordType("t:\ud800")).isEqualTo("t");
        assertThatThrownBy(() -> OpId.recordType("t:")).isInstanceOf(AccordException.class);
        assertThatThrownBy(() -> OpId.recordType("1t:a")).isInstanceOf(AccordException.class);
    }

    @Test
    void opIdsAreAnchored() {
        assertThat(OpId.parse("dev-A_1:42")).isEqualTo(new OpId("dev-A_1", 42));
        for (String bad : new String[] {"a:0", "a:01", "a:1\n", "a:1:2", ":1", "a:", "a:12345678901234567", "a:-1", "a:١"}) {
            assertThatThrownBy(() -> OpId.parse(bad)).as(bad).isInstanceOf(AccordException.class);
        }
        assertThat(OpId.parse("a:9999999999999999").seq()).isEqualTo(10000000000000000L);
    }

    @Test
    void rejectsMalformedOpsWithAReason() {
        JsonObject good = Wire.encode(OP);
        List<JsonValue> bad = List.of(
                JsonNull.INSTANCE,
                JsonArray.EMPTY,
                with(good, "op_id", new JsonString("no-seq")),
                with(good, "op_id", new JsonString("other:1")),
                with(good, "record", new JsonString("no-type")),
                with(good, "kind", new JsonString("explode")),
                with(good, "kind", null),
                with(with(good, "kind", new JsonString("inc")), "by", JsonNumber.of(1.5)),
                with(with(good, "kind", new JsonString("inc")), "by", new JsonString("1")),
                with(with(good, "kind", new JsonString("inc")), "by", JsonNumber.of(9007199254740992.0)),
                with(with(good, "kind", new JsonString("add")), "element", JsonArray.EMPTY),
                with(with(good, "kind", new JsonString("add")), "element", JsonNull.INSTANCE),
                with(with(with(good, "kind", new JsonString("remove")), "element", new JsonString("x")), "deps", null),
                with(good, "deps", new JsonString("a:1")),
                with(good, "deps", JsonArray.of(JsonArray.of(new JsonString("a:1")))),
                with(good, "deps", JsonArray.of(new JsonString("bad"))),
                with(good, "value", null),
                with(good, "hlc", new JsonString("garbage")),
                with(good, "field", JsonNumber.of(1)));
        for (JsonValue b : bad) {
            assertThatThrownBy(() -> Wire.decode(b)).as(Json.stringify(b)).isInstanceOf(AccordException.class);
        }
    }

    @Test
    void opHashUsesCanonicalJsonAndValidUtf8ForLoneSurrogates() {
        AssignOp op = new AssignOp("a:1", "d:1", "f", new Hlc(1, 0, "a"), new JsonString("\ud800"), List.of());
        String canonical = Json.canonical(Wire.encode(op));
        assertThat(canonical).contains("\\ud800");
        assertThat(OpHash.of(op)).isEqualTo(OpHash.sha256(canonical)).hasSize(64).matches("[0-9a-f]{64}");
    }
}
