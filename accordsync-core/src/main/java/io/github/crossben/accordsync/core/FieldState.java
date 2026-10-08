package io.github.crossben.accordsync.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Per-field state. Each strategy's {@code apply} is commutative and associative over distinct ops;
 * the replica applies each op at most once, which makes the merge idempotent.
 */
abstract sealed class FieldState permits FieldState.Lww, FieldState.Counter, FieldState.SetState, FieldState.Conflict {
    abstract Strategy strategy();

    /** Applies a schema-validated op in place. */
    abstract void apply(Op op);

    /** The field as the app reads it; empty for a never-written lww or conflict field (left out). */
    abstract Optional<JsonValue> read();

    /** Op ids a writer must cite in deps: live conflict values, or the tags of a set element. */
    List<String> observedDeps(JsonValue element) {
        return List.of();
    }

    /** The live state as JSON, tombstones dropped (ADR-0008). */
    abstract JsonObject snapshot();

    static FieldState empty(Strategy s) {
        return switch (s) {
            case LWW -> new Lww();
            case COUNTER -> new Counter();
            case SET -> new SetState();
            case CONFLICT -> new Conflict();
        };
    }

    AccordException wrongKind(Op op) {
        return new AccordException("op kind \"" + op.kind() + "\" does not apply to a " + strategy().id() + " field");
    }

    static JsonObject obj(Object... kv) {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (JsonValue) kv[i + 1]);
        return new JsonObject(m);
    }

    static JsonArray pairs(Map<String, JsonValue> m) {
        List<String> ids = new ArrayList<>(m.keySet());
        ids.sort(OpId.ORDER);
        List<JsonValue> out = new ArrayList<>();
        for (String id : ids) out.add(JsonArray.of(new JsonString(id), m.get(id)));
        return new JsonArray(out);
    }

    static final class Lww extends FieldState {
        AssignOp winner;

        @Override
        Strategy strategy() {
            return Strategy.LWW;
        }

        @Override
        void apply(Op op) {
            if (!(op instanceof AssignOp a)) throw wrongKind(op);
            if (winner == null || Hlc.compare(a.hlc(), winner.hlc()) > 0) winner = a;
        }

        @Override
        Optional<JsonValue> read() {
            return winner == null ? Optional.empty() : Optional.of(winner.value());
        }

        @Override
        JsonObject snapshot() {
            JsonValue w = winner == null
                    ? JsonNull.INSTANCE
                    : obj("opId", new JsonString(winner.opId()), "hlc", new JsonString(winner.hlc().encode()),
                            "value", winner.value());
            return obj("strategy", new JsonString("lww"), "winner", w);
        }
    }

    static final class Counter extends FieldState {
        /** A JavaScript number: a double, so totals never wrap. */
        double total;

        @Override
        Strategy strategy() {
            return Strategy.COUNTER;
        }

        @Override
        void apply(Op op) {
            if (!(op instanceof IncOp i)) throw wrongKind(op);
            total += i.by();
        }

        JsonNumber value() {
            if (total == Math.rint(total) && Math.abs(total) <= JsonNumber.MAX_SAFE_INTEGER) {
                return JsonNumber.of((long) total);
            }
            return JsonNumber.of(total);
        }

        @Override
        Optional<JsonValue> read() {
            return Optional.of(value());
        }

        @Override
        JsonObject snapshot() {
            return obj("strategy", new JsonString("counter"), "total", value());
        }
    }

    static final class SetState extends FieldState {
        final Map<String, JsonValue> tags = new LinkedHashMap<>();
        final Set<String> removed = new HashSet<>();

        @Override
        Strategy strategy() {
            return Strategy.SET;
        }

        @Override
        void apply(Op op) {
            if (op instanceof AddOp a) {
                for (String tag : a.deps()) {
                    removed.add(tag);
                    tags.remove(tag);
                }
                if (!removed.contains(a.opId())) tags.put(a.opId(), a.element());
                return;
            }
            if (op instanceof RemoveOp r) {
                for (String tag : r.deps()) {
                    removed.add(tag);
                    tags.remove(tag);
                }
                return;
            }
            throw wrongKind(op);
        }

        @Override
        Optional<JsonValue> read() {
            List<JsonValue> elements = new ArrayList<>(new LinkedHashSet<>(tags.values()));
            elements.sort(SetState::compareElements);
            return Optional.of(new JsonArray(elements));
        }

        /** Numbers first (ascending), then strings (by code unit). */
        static int compareElements(JsonValue a, JsonValue b) {
            if (a instanceof JsonNumber x) {
                if (!(b instanceof JsonNumber y)) return -1;
                double p = x.doubleValue();
                double q = y.doubleValue();
                return p < q ? -1 : p > q ? 1 : 0;
            }
            if (b instanceof JsonNumber) return 1;
            return ((JsonString) a).value().compareTo(((JsonString) b).value());
        }

        @Override
        List<String> observedDeps(JsonValue element) {
            List<String> out = new ArrayList<>();
            for (Map.Entry<String, JsonValue> e : tags.entrySet()) {
                if (e.getValue().equals(element)) out.add(e.getKey());
            }
            out.sort(OpId.ORDER);
            return out;
        }

        @Override
        JsonObject snapshot() {
            return obj("strategy", new JsonString("set"), "tags", pairs(tags));
        }
    }

    static final class Conflict extends FieldState {
        final Map<String, JsonValue> live = new LinkedHashMap<>();
        final Set<String> superseded = new HashSet<>();

        @Override
        Strategy strategy() {
            return Strategy.CONFLICT;
        }

        @Override
        void apply(Op op) {
            if (!(op instanceof AssignOp a)) throw wrongKind(op);
            for (String dep : a.deps()) {
                superseded.add(dep);
                live.remove(dep);
            }
            if (!superseded.contains(a.opId())) live.put(a.opId(), a.value());
        }

        @Override
        Optional<JsonValue> read() {
            List<String> ids = new ArrayList<>(live.keySet());
            ids.sort(OpId.ORDER);
            if (ids.isEmpty()) return Optional.empty();
            if (ids.size() == 1) return Optional.of(obj("value", live.get(ids.get(0))));
            List<JsonValue> conflicted = new ArrayList<>();
            for (String id : ids) conflicted.add(obj("value", live.get(id), "opId", new JsonString(id)));
            return Optional.of(obj("conflicted", new JsonArray(conflicted)));
        }

        @Override
        List<String> observedDeps(JsonValue element) {
            List<String> out = new ArrayList<>(live.keySet());
            out.sort(OpId.ORDER);
            return out;
        }

        @Override
        JsonObject snapshot() {
            return obj("strategy", new JsonString("conflict"), "live", pairs(live));
        }
    }

    /** Rebuilds a field's state from its snapshot JSON. */
    static FieldState fromSnapshot(JsonObject snap) {
        if (!(snap.get("strategy") instanceof JsonString s)) throw new AccordException("field snapshot needs a strategy");
        switch (Strategy.of(s.value())) {
            case LWW: {
                Lww st = new Lww();
                JsonValue w = snap.get("winner");
                if (w instanceof JsonObject o) {
                    st.winner = new AssignOp(string(o, "opId"), "", "", Hlc.decode(string(o, "hlc")),
                            o.has("value") ? o.get("value") : JsonNull.INSTANCE, List.of());
                } else if (w != null && !(w instanceof JsonNull)) {
                    throw new AccordException("lww snapshot: winner must be an object or null");
                }
                return st;
            }
            case COUNTER: {
                Counter st = new Counter();
                if (!(snap.get("total") instanceof JsonNumber n)) throw new AccordException("counter snapshot needs a total");
                st.total = n.doubleValue();
                return st;
            }
            case SET: {
                SetState st = new SetState();
                for (JsonValue p : list(snap, "tags")) {
                    JsonArray pair = pair(p);
                    st.tags.put(((JsonString) pair.items().get(0)).value(), pair.items().get(1));
                }
                return st;
            }
            default: {
                Conflict st = new Conflict();
                for (JsonValue p : list(snap, "live")) {
                    JsonArray pair = pair(p);
                    st.live.put(((JsonString) pair.items().get(0)).value(), pair.items().get(1));
                }
                return st;
            }
        }
    }

    private static String string(JsonObject o, String key) {
        if (!(o.get(key) instanceof JsonString s)) throw new AccordException("snapshot: \"" + key + "\" must be a string");
        return s.value();
    }

    private static List<JsonValue> list(JsonObject o, String key) {
        if (!(o.get(key) instanceof JsonArray a)) throw new AccordException("snapshot: \"" + key + "\" must be an array");
        return a.items();
    }

    private static JsonArray pair(JsonValue p) {
        if (p instanceof JsonArray a && a.items().size() == 2 && a.items().get(0) instanceof JsonString) return a;
        throw new AccordException("snapshot: expected [opId, value] pairs");
    }
}
