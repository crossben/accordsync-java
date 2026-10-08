package io.github.crossben.accordsync.core;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * One device's replica plus the means to write to it: every local write becomes an op, applied
 * locally first and returned so the caller can queue it for sync. Not thread-safe.
 */
public final class LocalWriter {
    /** Remote clocks further ahead than this are refused by default: 24 hours. */
    public static final long DEFAULT_MAX_SKEW_MS = 24L * 60 * 60 * 1000;

    /**
     * Where a device resumes: its last clock and op sequence number.
     *
     * @param hlc the last clock
     * @param seq the last sequence number
     */
    public record Resume(Hlc hlc, long seq) {}

    private Replica replica;
    private final String deviceId;
    private final LongSupplier now;
    private final long maxSkewMs;
    private Hlc hlc;
    private long seq;

    /**
     * A fresh device with the default skew limit.
     *
     * @param schema the schema
     * @param deviceId the device id (node id rules)
     * @param now physical time in ms; the core never reads the clock itself
     */
    public LocalWriter(Schema schema, String deviceId, LongSupplier now) {
        this(schema, deviceId, now, DEFAULT_MAX_SKEW_MS, null);
    }

    /**
     * A device.
     *
     * @param schema the schema
     * @param deviceId the device id (node id rules)
     * @param now physical time in ms
     * @param maxSkewMs remote clocks further ahead than this are refused
     * @param resume the clock and sequence to resume from, or null for a fresh device
     */
    public LocalWriter(Schema schema, String deviceId, LongSupplier now, long maxSkewMs, Resume resume) {
        Hlc.assertNode(deviceId);
        this.deviceId = deviceId;
        this.replica = new Replica(schema);
        this.now = Objects.requireNonNull(now, "now");
        this.maxSkewMs = maxSkewMs;
        this.hlc = resume != null ? resume.hlc() : Hlc.initial(deviceId);
        this.seq = resume != null ? resume.seq() : 0;
    }

    /** @return the replica */
    public Replica replica() {
        return replica;
    }

    /** @return the device id */
    public String deviceId() {
        return deviceId;
    }

    /** @return the current clock */
    public Hlc clock() {
        return hlc;
    }

    /** @return the last sequence number used or seen */
    public long seq() {
        return seq;
    }

    /**
     * Assigns a value to an lww or conflict field.
     *
     * @param record the record id
     * @param field the field
     * @param value the value; use {@link JsonNull#INSTANCE} for "no value"
     * @return the op
     */
    public AssignOp assign(String record, String field, JsonValue value) {
        if (value == null) throw new AccordException("value must be JSON (use JsonNull for \"no value\")");
        List<String> deps = replica.observedDeps(record, field);
        Base b = base(record, field);
        return write(new AssignOp(b.opId, record, field, b.hlc, value, deps));
    }

    /**
     * Assigns a plain Java value converted with {@link Json#of(Object)} (Java null is JSON null).
     *
     * @param record the record id
     * @param field the field
     * @param value the value
     * @return the op
     */
    public AssignOp assign(String record, String field, Object value) {
        return assign(record, field, Json.of(value));
    }

    /**
     * Increments a counter.
     *
     * @param record the record id
     * @param field the field
     * @param by an integer within ±(2^53 - 1)
     * @return the op
     */
    public IncOp inc(String record, String field, long by) {
        if (Math.abs(by) > JsonNumber.MAX_SAFE_INTEGER) {
            throw new AccordException("counter increment must be an integer, got " + by);
        }
        Base b = base(record, field);
        return write(new IncOp(b.opId, record, field, b.hlc, by));
    }

    /**
     * Increments a counter by a whole double.
     *
     * @param record the record id
     * @param field the field
     * @param by a whole number within ±(2^53 - 1)
     * @return the op
     */
    public IncOp inc(String record, String field, double by) {
        if (!JsonNumber.of(by).isSafeInteger()) {
            throw new AccordException("counter increment must be an integer, got " + Json.number(by));
        }
        return inc(record, field, (long) by);
    }

    /**
     * Adds an element to a set.
     *
     * @param record the record id
     * @param field the field
     * @param element a string or finite number
     * @return the op
     */
    public AddOp add(String record, String field, JsonValue element) {
        assertElement(element);
        List<String> deps = replica.observedDeps(record, field, element);
        Base b = base(record, field);
        return write(new AddOp(b.opId, record, field, b.hlc, element, deps));
    }

    /**
     * Adds a string element.
     *
     * @param record the record id
     * @param field the field
     * @param element the element
     * @return the op
     */
    public AddOp add(String record, String field, String element) {
        return add(record, field, new JsonString(element));
    }

    /**
     * Adds a number element.
     *
     * @param record the record id
     * @param field the field
     * @param element the element
     * @return the op
     */
    public AddOp add(String record, String field, double element) {
        return add(record, field, JsonNumber.of(element));
    }

    /**
     * Removes the element's tags this device has seen (concurrent adds survive).
     *
     * @param record the record id
     * @param field the field
     * @param element a string or finite number
     * @return the op
     */
    public RemoveOp remove(String record, String field, JsonValue element) {
        assertElement(element);
        List<String> deps = replica.observedDeps(record, field, element);
        Base b = base(record, field);
        return write(new RemoveOp(b.opId, record, field, b.hlc, element, deps));
    }

    /**
     * Removes a string element.
     *
     * @param record the record id
     * @param field the field
     * @param element the element
     * @return the op
     */
    public RemoveOp remove(String record, String field, String element) {
        return remove(record, field, new JsonString(element));
    }

    /**
     * Applies an op from elsewhere. Refuses it, leaving state untouched, if its clock is absurd.
     *
     * @param op the op
     * @return applied or duplicate
     * @throws ClockSkewException when the op's clock is too far ahead
     */
    public ApplyResult receive(Op op) {
        if (replica.has(op.opId())) {
            advanceSeq(op.opId());
            return ApplyResult.DUPLICATE;
        }
        replica.validate(op);
        Hlc next = Hlc.receive(hlc, op.hlc(), now.getAsLong(), maxSkewMs);
        ApplyResult result = replica.apply(op);
        hlc = next;
        advanceSeq(op.opId());
        return result;
    }

    /**
     * Makes sure future op ids come after an op id of this device (other devices' ids are ignored).
     *
     * @param seenOpId an op id
     */
    public void advanceSeq(String seenOpId) {
        OpId id = OpId.parse(seenOpId);
        if (id.device().equals(deviceId)) advanceSeq(id.seq());
    }

    /**
     * Makes sure future op ids come after a sequence number (as the server reports it).
     *
     * @param seenSeq the sequence number
     */
    public void advanceSeq(long seenSeq) {
        if (seenSeq > seq) seq = seenSeq;
    }

    /**
     * Rolls back ops the server refused; the clock and sequence are not rewound.
     *
     * @param opIds op ids to drop
     */
    public void discard(Iterable<String> opIds) {
        Set<String> drop = new HashSet<>();
        for (String id : opIds) drop.add(id);
        replica = replica.without(drop);
    }

    /**
     * The record left this device's scope: forget it, except the local ops in {@code keep}.
     *
     * @param record the record id
     * @param keep op ids to keep
     */
    public void forget(String record, Set<String> keep) {
        replica = replica.forget(record, keep);
    }

    /**
     * Forgets a record entirely.
     *
     * @param record the record id
     */
    public void forget(String record) {
        forget(record, Set.of());
    }

    private record Base(String opId, Hlc hlc) {}

    private Base base(String record, String field) {
        replica.observedDeps(record, field); // validate before consuming a tick or sequence number
        hlc = Hlc.tick(hlc, now.getAsLong());
        seq += 1;
        return new Base(deviceId + ":" + seq, hlc);
    }

    private <T extends Op> T write(T op) {
        replica.apply(op);
        return op;
    }

    private static void assertElement(JsonValue e) {
        if (e == null || !Wire.isElement(e)) throw new AccordException("set elements must be a string or number");
    }
}
