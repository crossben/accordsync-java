package io.github.crossben.accordsync.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * An op log and the state projected from it. Pure: no I/O, no clock. Two replicas holding the same
 * set of ops always read the same state, whatever order the ops arrived in. Not thread-safe.
 */
public final class Replica {
    private final Schema schema;
    private final Map<String, Op> ops = new LinkedHashMap<>();
    private final Map<String, Map<String, FieldState>> records = new LinkedHashMap<>();
    private final Map<String, RecordSnapshot> bases = new LinkedHashMap<>();

    /**
     * An empty replica.
     *
     * @param schema the schema
     */
    public Replica(Schema schema) {
        this.schema = Objects.requireNonNull(schema, "schema");
    }

    /** @return the schema */
    public Schema schema() {
        return schema;
    }

    /**
     * Whether the op was applied.
     *
     * @param opId the op id
     * @return true when known
     */
    public boolean has(String opId) {
        return ops.containsKey(opId);
    }

    /** @return the number of ops held */
    public int size() {
        return ops.size();
    }

    /** @return all ops, sorted by op id */
    public List<Op> ops() {
        List<Op> out = new ArrayList<>(ops.values());
        out.sort((a, b) -> OpId.compare(a.opId(), b.opId()));
        return out;
    }

    /**
     * Throws (without changing anything) if the op does not fit the schema.
     *
     * @param op the op
     * @throws AccordException naming what is wrong
     */
    public void validate(Op op) {
        Strategy strategy = schema.strategyFor(op.record(), op.field());
        if (!strategy.kinds().contains(op.kind())) {
            throw new AccordException("op kind \"" + op.kind() + "\" does not apply to " + op.field() + ", a "
                    + strategy.id() + " field");
        }
        OpId id = OpId.parse(op.opId());
        if (!id.device().equals(op.hlc().node())) {
            throw new AccordException("op " + op.opId() + " carries a clock from \"" + op.hlc().node() + "\"");
        }
    }

    /**
     * Applies an op once; a known op id is a duplicate and changes nothing.
     *
     * @param op the op
     * @return applied or duplicate
     * @throws AccordException when the op does not fit the schema
     */
    public ApplyResult apply(Op op) {
        if (ops.containsKey(op.opId())) return ApplyResult.DUPLICATE;
        validate(op);
        state(op.record(), op.field()).apply(op);
        ops.put(op.opId(), op);
        return ApplyResult.APPLIED;
    }

    /**
     * The record's fields as the app reads them: lww fields their value, counters their total, sets
     * their sorted elements, conflict fields {@code {"value"}} or {@code {"conflicted":[...]}}.
     * Never-written lww and conflict fields are left out.
     *
     * @param record the record id
     * @return the fields, or empty if no op has touched the record
     */
    public Optional<JsonObject> read(String record) {
        Map<String, FieldState> states = records.get(record);
        if (states == null) return Optional.empty();
        Map<String, JsonValue> out = new LinkedHashMap<>();
        for (Map.Entry<String, Strategy> f : schema.fieldsOf(record).entrySet()) {
            FieldState s = states.get(f.getKey());
            if (s == null) s = FieldState.empty(f.getValue());
            s.read().ifPresent(v -> out.put(f.getKey(), v));
        }
        return Optional.of(new JsonObject(out));
    }

    /** @return the ids of records touched, sorted by code unit */
    public List<String> records() {
        List<String> out = new ArrayList<>(records.keySet());
        out.sort(String::compareTo);
        return out;
    }

    /** @return every conflict field currently holding more than one value */
    public List<FieldRef> conflicts() {
        List<FieldRef> out = new ArrayList<>();
        for (String record : records()) {
            Map<String, FieldState> fields = records.get(record);
            List<String> names = new ArrayList<>(fields.keySet());
            names.sort(String::compareTo);
            for (String field : names) {
                if (fields.get(field) instanceof FieldState.Conflict c && c.live.size() > 1) {
                    out.add(new FieldRef(record, field));
                }
            }
        }
        return out;
    }

    /**
     * Op ids a new write to this field must cite.
     *
     * @param record the record id
     * @param field the field
     * @param element the set element (null for other strategies)
     * @return sorted op ids
     * @throws AccordException when the field is not in the schema
     */
    public List<String> observedDeps(String record, String field, JsonValue element) {
        schema.strategyFor(record, field);
        Map<String, FieldState> fields = records.get(record);
        FieldState state = fields == null ? null : fields.get(field);
        return state == null ? List.of() : state.observedDeps(element);
    }

    /**
     * Op ids a new write to this (non-set) field must cite.
     *
     * @param record the record id
     * @param field the field
     * @return sorted op ids
     */
    public List<String> observedDeps(String record, String field) {
        return observedDeps(record, field, null);
    }

    /** @return the whole state as canonical JSON: equal strings mean converged replicas */
    public String snapshot() {
        Map<String, JsonValue> all = new LinkedHashMap<>();
        for (String r : records()) all.put(r, read(r).orElseThrow());
        return Json.canonical(new JsonObject(all));
    }

    /**
     * The record's current state, with its history folded away.
     *
     * @param record the record id
     * @return the snapshot
     */
    public RecordSnapshot snapshotRecord(String record) {
        Map<String, JsonObject> fields = new LinkedHashMap<>();
        Map<String, FieldState> states = records.get(record);
        if (states != null) for (Map.Entry<String, FieldState> e : states.entrySet()) fields.put(e.getKey(), e.getValue().snapshot());
        return new RecordSnapshot(record, fields);
    }

    /**
     * Replaces a record's state with a snapshot and forgets that record's ops, except {@code keep}
     * (local ops not yet on the server), which are applied again on top.
     *
     * @param snap the snapshot
     * @param keep op ids to keep
     */
    public void loadSnapshot(RecordSnapshot snap, Set<String> keep) {
        List<Op> reapply = new ArrayList<>();
        for (Op o : ops.values()) if (o.record().equals(snap.record()) && keep.contains(o.opId())) reapply.add(o);
        ops.values().removeIf(o -> o.record().equals(snap.record()));
        Map<String, FieldState> fields = new LinkedHashMap<>();
        for (Map.Entry<String, JsonObject> e : snap.fields().entrySet()) {
            schema.strategyFor(snap.record(), e.getKey());
            fields.put(e.getKey(), FieldState.fromSnapshot(e.getValue()));
        }
        records.put(snap.record(), fields);
        bases.put(snap.record(), snap);
        for (Op op : reapply) apply(op);
    }

    /**
     * {@link #loadSnapshot(RecordSnapshot, Set)} keeping nothing.
     *
     * @param snap the snapshot
     */
    public void loadSnapshot(RecordSnapshot snap) {
        loadSnapshot(snap, Set.of());
    }

    /**
     * A copy without the given ops (same snapshots, every other op): used to roll back.
     *
     * @param drop op ids to leave out
     * @return the new replica
     */
    public Replica without(Set<String> drop) {
        Replica next = new Replica(schema);
        for (RecordSnapshot snap : bases.values()) next.loadSnapshot(snap);
        for (Op op : ops()) if (!drop.contains(op.opId())) next.apply(op);
        return next;
    }

    /**
     * Forgets a record entirely (it left this device's scope), except ops in {@code keep}.
     *
     * @param record the record id
     * @param keep op ids to keep
     * @return the new replica
     */
    public Replica forget(String record, Set<String> keep) {
        Replica next = new Replica(schema);
        for (Map.Entry<String, RecordSnapshot> b : bases.entrySet()) if (!b.getKey().equals(record)) next.loadSnapshot(b.getValue());
        for (Op op : ops()) if (!op.record().equals(record) || keep.contains(op.opId())) next.apply(op);
        return next;
    }

    /** @return snapshots this replica's state was started from */
    public List<RecordSnapshot> bases() {
        return List.copyOf(bases.values());
    }

    private FieldState state(String record, String field) {
        Map<String, FieldState> fields = records.computeIfAbsent(record, r -> new LinkedHashMap<>());
        FieldState s = fields.get(field);
        if (s == null) {
            s = FieldState.empty(schema.strategyFor(record, field));
            fields.put(field, s);
        }
        return s;
    }
}
