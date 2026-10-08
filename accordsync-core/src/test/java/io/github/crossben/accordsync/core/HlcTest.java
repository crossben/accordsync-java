package io.github.crossben.accordsync.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Random;
import org.junit.jupiter.api.Test;

class HlcTest {
    static final String NODE_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789-";

    static Hlc random(Random rnd) {
        StringBuilder node = new StringBuilder();
        for (int i = 1 + rnd.nextInt(12); i > 0; i--) node.append(NODE_CHARS.charAt(rnd.nextInt(NODE_CHARS.length())));
        return new Hlc((long) (rnd.nextDouble() * 4_000_000_000_000L), rnd.nextInt(100_000), node.toString());
    }

    @Test
    void roundTripsThroughItsWireEncoding() {
        Random rnd = new Random(1);
        for (int i = 0; i < 1000; i++) {
            Hlc h = random(rnd);
            assertThat(Hlc.decode(h.encode())).isEqualTo(h);
        }
    }

    @Test
    void encodesInTheDocumentedFormat() {
        assertThat(new Hlc(1727871000123L, 4, "dev-7f3a").encode()).isEqualTo("1727871000123:00004:dev-7f3a");
        assertThat(new Hlc(0, 99999, "a").encode()).isEqualTo("0:99999:a");
    }

    @Test
    void rejectsMalformedEncodings() {
        for (String bad : new String[] {"", "1:2", "x:00001:a", "1:00001:", "-1:00001:a", "1:00001:a:b",
            "1:00001:a\n", "9007199254740992:00000:a", "12345678901234567:00000:a", "1:0001:a", "١:00001:a"}) {
            assertThatThrownBy(() -> Hlc.decode(bad)).as(bad).isInstanceOf(AccordException.class);
        }
        assertThat(Hlc.decode("9007199254740991:00000:a").wall()).isEqualTo(9007199254740991L);
    }

    @Test
    void ordersTotallyAndConsistently() {
        Random rnd = new Random(2);
        for (int i = 0; i < 1000; i++) {
            Hlc a = random(rnd);
            Hlc b = rnd.nextBoolean() ? random(rnd) : new Hlc(a.wall(), a.counter(), rnd.nextBoolean() ? a.node() : "Z" + a.node());
            assertThat(Integer.signum(Hlc.compare(a, b))).isEqualTo(-Integer.signum(Hlc.compare(b, a)));
            if (Hlc.compare(a, b) == 0) assertThat(a).isEqualTo(b);
        }
        // Node ids compare by code unit: 'B' < '_' < 'a'.
        assertThat(Hlc.compare(new Hlc(1, 0, "B"), new Hlc(1, 0, "a"))).isNegative();
        assertThat(Hlc.compare(new Hlc(1, 0, "_"), new Hlc(1, 0, "a"))).isNegative();
    }

    @Test
    void tickIsStrictlyIncreasingEvenWhenTheWallClockGoesBackwards() {
        Random rnd = new Random(3);
        Hlc h = Hlc.initial("a");
        for (int i = 0; i < 500; i++) {
            Hlc next = Hlc.tick(h, rnd.nextInt(10_001));
            assertThat(Hlc.compare(next, h)).isPositive();
            h = next;
        }
    }

    @Test
    void receiveMovesPastBothTheLocalAndTheRemoteClock() {
        Random rnd = new Random(4);
        for (int i = 0; i < 1000; i++) {
            Hlc l = random(rnd);
            Hlc local = new Hlc(l.wall(), l.counter(), "local");
            Hlc r = random(rnd);
            Hlc next = Hlc.receive(local, r, (long) (rnd.nextDouble() * 4_000_000_000_000L), JsonNumber.MAX_SAFE_INTEGER);
            assertThat(Hlc.compare(next, local)).isPositive();
            assertThat(next.wall() > r.wall() || (next.wall() == r.wall() && next.counter() > r.counter())).isTrue();
            assertThat(next.node()).isEqualTo("local");
        }
    }

    @Test
    void aFullCounterRollsIntoTheNextMillisecond() {
        Hlc full = new Hlc(5, Hlc.MAX_COUNTER, "b");
        assertThat(Hlc.tick(new Hlc(5, Hlc.MAX_COUNTER, "a"), 0)).isEqualTo(new Hlc(6, 0, "a"));
        assertThat(Hlc.receive(Hlc.initial("a"), full, 0, 1_000)).isEqualTo(new Hlc(6, 0, "a"));
    }

    @Test
    void refusesARemoteClockTooFarInTheFuture() {
        Hlc local = Hlc.initial("a");
        Hlc remote = new Hlc(10_000_000, 0, "b");
        assertThatThrownBy(() -> Hlc.receive(local, remote, 1_000, 60_000)).isInstanceOf(ClockSkewException.class);
        assertThatCode(() -> Hlc.receive(local, remote, 9_990_000, 60_000)).doesNotThrowAnyException();
    }

    @Test
    void nodeIdsAreChecked() {
        for (String bad : new String[] {"", "a:b", "é", "a b", "x".repeat(65)}) {
            assertThatThrownBy(() -> Hlc.initial(bad)).isInstanceOf(AccordException.class);
        }
        assertThat(Hlc.initial("x".repeat(64)).node()).hasSize(64);
    }
}
