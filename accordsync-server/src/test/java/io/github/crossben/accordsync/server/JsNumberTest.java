package io.github.crossben.accordsync.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JsNumberTest {
    @Test
    void readsQueryNumbersLikeJavaScript() {
        assertThat(JsNumber.safeInteger("")).isEqualTo(0L);
        assertThat(JsNumber.safeInteger("  ")).isEqualTo(0L);
        assertThat(JsNumber.safeInteger("0x10")).isEqualTo(16L);
        assertThat(JsNumber.safeInteger("0b101")).isEqualTo(5L);
        assertThat(JsNumber.safeInteger("0o17")).isEqualTo(15L);
        assertThat(JsNumber.safeInteger("1e3")).isEqualTo(1000L);
        assertThat(JsNumber.safeInteger(" 42\n")).isEqualTo(42L);
        assertThat(JsNumber.safeInteger("+7")).isEqualTo(7L);
        assertThat(JsNumber.safeInteger("-0")).isEqualTo(0L);
        assertThat(JsNumber.safeInteger("5.0")).isEqualTo(5L);
        assertThat(JsNumber.safeInteger(".5e1")).isEqualTo(5L);
        assertThat(JsNumber.safeInteger("9007199254740991")).isEqualTo(9007199254740991L);
    }

    @Test
    void refusesWhatIsNotASafeInteger() {
        assertThat(JsNumber.safeInteger("1.5")).isNull();
        assertThat(JsNumber.safeInteger("abc")).isNull();
        assertThat(JsNumber.safeInteger("12abc")).isNull();
        assertThat(JsNumber.safeInteger("Infinity")).isNull();
        assertThat(JsNumber.safeInteger("9007199254740992")).isNull();
        assertThat(JsNumber.safeInteger("0x")).isNull();
        assertThat(JsNumber.safeInteger("-0x10")).isNull();
        assertThat(JsNumber.safeInteger("1_000")).isNull();
        assertThat(JsNumber.safeInteger("1d")).isNull();
        assertThat(Double.isNaN(JsNumber.parse("NaN"))).isTrue();
        assertThat(JsNumber.parse("-Infinity")).isEqualTo(Double.NEGATIVE_INFINITY);
    }
}
