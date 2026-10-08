package io.github.crossben.accordsync.core;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hybrid logical clock: physical time + logical counter + node id. {@link #compare} is a total
 * order: two distinct clocks never compare equal, because the node id breaks ties.
 *
 * @param wall milliseconds since the Unix epoch, as seen by the node (possibly pushed forward)
 * @param counter disambiguates events within the same {@code wall} millisecond (0..99999)
 * @param node the device or server that produced the clock
 */
public record Hlc(long wall, int counter, String node) implements Comparable<Hlc> {
    /** The highest counter; a full counter rolls into the next millisecond. */
    public static final int MAX_COUNTER = 99_999;

    private static final Pattern NODE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ENCODED = Pattern.compile("(\\d{1,16}):(\\d{5}):([A-Za-z0-9_-]{1,64})");

    /**
     * Creates a clock.
     *
     * @param wall wall time in ms
     * @param counter the logical counter
     * @param node the node id
     */
    public Hlc {
        Objects.requireNonNull(node, "node");
    }

    /**
     * The zero clock of a node.
     *
     * @param node the node id
     * @return {@code 0:00000:node}
     * @throws AccordException when the node id is malformed
     */
    public static Hlc initial(String node) {
        assertNode(node);
        return new Hlc(0, 0, node);
    }

    /**
     * Compares two clocks: wall, then counter, then node id by UTF-16 code unit.
     *
     * @param a a clock
     * @param b another clock
     * @return negative, zero or positive
     */
    public static int compare(Hlc a, Hlc b) {
        if (a.wall != b.wall) return a.wall < b.wall ? -1 : 1;
        if (a.counter != b.counter) return a.counter < b.counter ? -1 : 1;
        int c = a.node.compareTo(b.node);
        return c < 0 ? -1 : c > 0 ? 1 : 0;
    }

    @Override
    public int compareTo(Hlc other) {
        return compare(this, other);
    }

    /**
     * The clock for a new local event at physical time {@code now}.
     *
     * @param local the current local clock
     * @param now physical time in ms
     * @return a clock greater than {@code local}
     */
    public static Hlc tick(Hlc local, long now) {
        if (now > local.wall) return new Hlc(now, 0, local.node);
        return after(local.wall, local.counter, local.node);
    }

    /**
     * The clock after observing {@code remote} at physical time {@code now}.
     *
     * @param local the current local clock
     * @param remote the observed clock
     * @param now physical time in ms
     * @param maxSkewMs how far ahead of {@code now} a remote clock may be
     * @return a clock greater than both
     * @throws ClockSkewException when {@code remote} is more than {@code maxSkewMs} ahead
     */
    public static Hlc receive(Hlc local, Hlc remote, long now, long maxSkewMs) {
        if (remote.wall - now > maxSkewMs) {
            throw new ClockSkewException("clock of " + remote.node + " is " + (remote.wall - now)
                    + " ms ahead (limit " + maxSkewMs + " ms)");
        }
        long wall = Math.max(Math.max(local.wall, remote.wall), now);
        if (wall == local.wall && wall == remote.wall) {
            return after(wall, Math.max(local.counter, remote.counter), local.node);
        }
        if (wall == local.wall) return after(wall, local.counter, local.node);
        if (wall == remote.wall) return after(wall, remote.counter, local.node);
        return new Hlc(wall, 0, local.node);
    }

    /**
     * {@code wall:counter:node}, the counter zero-padded to 5 digits.
     *
     * @return the wire encoding
     */
    public String encode() {
        String c = Integer.toString(counter);
        return wall + ":" + "00000".substring(Math.min(5, c.length())) + c + ":" + node;
    }

    /**
     * Parses the wire encoding.
     *
     * @param s the encoding
     * @return the clock
     * @throws AccordException when malformed or the wall exceeds 2^53 - 1
     */
    public static Hlc decode(String s) {
        Matcher m = ENCODED.matcher(s);
        if (!m.matches()) throw new AccordException("malformed hlc \"" + s + "\"");
        long wall = Long.parseLong(m.group(1));
        if (wall > JsonNumber.MAX_SAFE_INTEGER) throw new AccordException("hlc wall out of range in \"" + s + "\"");
        return new Hlc(wall, Integer.parseInt(m.group(2)), m.group(3));
    }

    /**
     * Checks a node (device) id: 1 to 64 of {@code A-Za-z0-9_-}.
     *
     * @param node the id
     * @throws AccordException when malformed
     */
    public static void assertNode(String node) {
        if (node == null || !NODE.matcher(node).matches()) {
            throw new AccordException("node id must match /^[A-Za-z0-9_-]{1,64}$/, got \"" + node + "\"");
        }
    }

    private static Hlc after(long wall, int counter, String node) {
        if (counter >= MAX_COUNTER) return new Hlc(wall + 1, 0, node);
        return new Hlc(wall, counter + 1, node);
    }
}
