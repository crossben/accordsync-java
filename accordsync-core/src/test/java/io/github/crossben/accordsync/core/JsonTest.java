package io.github.crossben.accordsync.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Canonical JSON must equal JSON.stringify in the TypeScript core, including what JSON input can't carry. */
class JsonTest {
    static String c(Object v) {
        return Json.canonical(Json.of(v));
    }

    @Test
    void numbersPrintAsJavaScriptPrintsThem() {
        Object[][] cases = {
            {-0.0, "0"}, {0.0, "0"}, {1.0, "1"}, {-2.0, "-2"}, {0.1, "0.1"}, {1e21, "1e+21"},
            {1e20, "100000000000000000000"}, {1e-7, "1e-7"}, {1.5e-7, "1.5e-7"}, {1e-6, "0.000001"},
            {123456789.125, "123456789.125"}, {5e-324, "5e-324"}, {-1.5e300, "-1.5e+300"},
            {9007199254740991L, "9007199254740991"}, {1L << 60, "1152921504606847000"},
            {Double.NaN, "null"}, {Double.POSITIVE_INFINITY, "null"}, {Double.NEGATIVE_INFINITY, "null"},
            {true, "true"}, {false, "false"}, {null, "null"}, {2e-3, "0.002"}, {1.7976931348623157e308, "1.7976931348623157e+308"},
            {2.82879384806159E17, "282879384806159000"}, {1e23, "1e+23"}, {0.30000000000000004, "0.30000000000000004"},
            {123e-20, "1.23e-18"}, {100.0, "100"}, {1.5, "1.5"}, {-1e-7, "-1e-7"}, {4.35, "4.35"},
        };
        for (Object[] t : cases) assertThat(c(t[0])).as(String.valueOf(t[0])).isEqualTo(t[1]);
    }

    @Test
    void doublesRoundTripWithTheShortestDigits() {
        Random rnd = new Random(42);
        for (int i = 0; i < 20000; i++) {
            double d = Double.longBitsToDouble(rnd.nextLong());
            if (!Double.isFinite(d)) continue;
            String s = Json.number(d);
            assertThat(Double.parseDouble(s)).isEqualTo(d);
            // Never more digits than Java's shortest representation.
            String digits = s.replaceAll("e.*", "").replaceAll("[-.]", "").replaceAll("^0+", "").replaceAll("0+$", "");
            assertThat(digits.length()).isLessThanOrEqualTo(17);
        }
    }

    @Test
    void numbersReadBackFromJsonPrintTheSame() {
        assertThat(Json.canonical(Json.parse("[1.0, -0, 1E2, 1e-7, 9007199254740993, 0.1e1, 12345678901234567890]")))
                .isEqualTo("[1,0,100,1e-7,9007199254740992,1,12345678901234567000]");
    }

    @Test
    void oneAndOnePointZeroAreEqualButNotTheString() {
        assertThat(JsonNumber.of(1)).isEqualTo(JsonNumber.of(1.0)).hasSameHashCodeAs(JsonNumber.of(1.0));
        assertThat(JsonNumber.of(0)).isEqualTo(JsonNumber.of(-0.0)).hasSameHashCodeAs(JsonNumber.of(-0.0));
        assertThat(Json.parse("1")).isEqualTo(Json.parse("1.0"));
        assertThat((Object) new JsonString("1")).isNotEqualTo(JsonNumber.of(1));
        assertThat(Json.parse("{\"a\":[1,{\"b\":2}]}")).isEqualTo(Json.parse("{\"a\":[1.0,{\"b\":2e0}]}"));
    }

    @Test
    void keysArrayIndicesFirstInNumericOrderThenCodeUnitOrder() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", 1);
        m.put("a", 2);
        m.put("10", 3);
        m.put("9", 4);
        m.put("01", 5);
        m.put("4294967295", 6);
        m.put("B", 7);
        m.put("4294967294", 8);
        assertThat(c(m)).isEqualTo("{\"9\":4,\"10\":3,\"4294967294\":8,\"01\":5,\"4294967295\":6,\"B\":7,\"a\":2,\"b\":1}");
    }

    @Test
    void keysAstralCharactersSortBeforeThePrivateUseArea() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("\ue000", 1);
        m.put("\ud83d\ude00", 2);
        m.put("-1", 3);
        m.put("1.5", 4);
        assertThat(c(m)).isEqualTo("{\"-1\":3,\"1.5\":4,\"\ud83d\ude00\":2,\"\ue000\":1}");
    }

    @Test
    void stringifyKeepsInsertionOrderAfterIndices() {
        assertThat(Json.stringify(Json.parse("{\"b\":1,\"a\":2,\"1\":3}"))).isEqualTo("{\"1\":3,\"b\":1,\"a\":2}");
    }

    @Test
    void stringsEscapeLikeJsonStringify() {
        assertThat(c("\u0000\u001f\u007f\b\f\n\r\t\"\\ /é\ud83d\ude00\u2028\u2029"))
                .isEqualTo("\"\\u0000\\u001f\u007f\\b\\f\\n\\r\\t\\\"\\\\ /é\ud83d\ude00\u2028\u2029\"");
        assertThat(c("\u001b")).isEqualTo("\"\\u001b\"");
    }

    @Test
    void loneSurrogatesAreEscapedAndPairsKept() {
        assertThat(c("\ud800")).isEqualTo("\"\\ud800\"");
        assertThat(c("a\udfffb")).isEqualTo("\"a\\udfffb\"");
        assertThat(c("\udbff\ud800")).isEqualTo("\"\\udbff\\ud800\"");
        assertThat(c("\ud83d\ude00")).isEqualTo("\"\ud83d\ude00\"");
    }

    @Test
    void parsesEscapesIncludingLoneSurrogates() {
        assertThat(Json.parse("\"\\ud800\\u00E9\\/\\b\"")).isEqualTo(new JsonString("\ud800é/\b"));
        assertThat(Json.parse("\"\\ud83d\\ude00\"")).isEqualTo(new JsonString("\ud83d\ude00"));
        assertThat(Json.parse(" {\"x\" : [ true , false , null ] } ")).isEqualTo(Json.of(Map.of("x", List.of(true, false, JsonNull.INSTANCE))));
    }

    @Test
    void duplicateKeysKeepTheLastValue() {
        assertThat(Json.canonical(Json.parse("{\"a\":1,\"a\":2}"))).isEqualTo("{\"a\":2}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "01", "1.", ".5", "+1", "-", "1e", "1e+", "0x10", "NaN", "Infinity", "[1,]", "{\"a\":1,}",
        "{a:1}", "'a'", "\"a", "\"\t\"", "\"\\x\"", "\"\\u12\"", "\"\\u١٢٣٤\"", "tru", "nul", "[1] 2", "{\"a\" 1}", "[", "\u00a0 1", "1 /"})
    void rejectsInvalidJson(String text) {
        assertThatThrownBy(() -> Json.parse(text)).isInstanceOf(AccordException.class);
    }

    @Test
    void nestingAndEmptyContainers() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("z", List.of(1, List.of(2.0, Map.of("y", JsonNull.INSTANCE))));
        m.put("a", Map.of());
        m.put("x", List.of());
        assertThat(c(m)).isEqualTo("{\"a\":{},\"x\":[],\"z\":[1,[2,{\"y\":null}]]}");
    }

    @Test
    void refusesWhatIsNotJson() {
        assertThatThrownBy(() -> Json.of(new Object())).isInstanceOf(AccordException.class);
        assertThatThrownBy(() -> Json.of(Map.of(1, "x"))).isInstanceOf(AccordException.class);
    }
}
