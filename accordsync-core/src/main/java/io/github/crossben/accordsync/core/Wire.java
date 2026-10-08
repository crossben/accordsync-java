package io.github.crossben.accordsync.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The wire format of ops, as they travel over the network and sit in golden vectors. */
public final class Wire {
    private Wire() {}

    /**
     * Encodes an op. An {@code add} omits {@code deps} when empty (the v0.1 wire shape).
     *
     * @param op the op
     * @return its wire object
     */
    public static JsonObject encode(Op op) {
        Map<String, JsonValue> o = new LinkedHashMap<>();
        o.put("op_id", new JsonString(op.opId()));
        o.put("record", new JsonString(op.record()));
        o.put("field", new JsonString(op.field()));
        o.put("hlc", new JsonString(op.hlc().encode()));
        o.put("kind", new JsonString(op.kind()));
        if (op instanceof AssignOp a) {
            o.put("value", a.value());
            o.put("deps", strings(a.deps()));
        } else if (op instanceof IncOp i) {
            o.put("by", JsonNumber.of(i.by()));
        } else if (op instanceof AddOp a) {
            o.put("element", a.element());
            if (!a.deps().isEmpty()) o.put("deps", strings(a.deps()));
        } else {
            RemoveOp r = (RemoveOp) op;
            o.put("element", r.element());
            o.put("deps", strings(r.deps()));
        }
        return new JsonObject(o);
    }

    /**
     * Parses untrusted input into an op, or throws with the reason. Schema checks happen later.
     *
     * @param input the wire value
     * @return the op
     * @throws AccordException naming what is wrong
     */
    public static Op decode(JsonValue input) {
        if (!(input instanceof JsonObject o)) throw new AccordException("op must be an object");
        String opId = str(o, "op_id");
        String record = str(o, "record");
        String field = str(o, "field");
        Hlc hlc = Hlc.decode(str(o, "hlc"));
        OpId id = OpId.parse(opId);
        OpId.recordType(record);
        if (!id.device().equals(hlc.node())) {
            throw new AccordException("op " + opId + " carries a clock from \"" + hlc.node() + "\"");
        }
        JsonValue kind = o.get("kind");
        String k = kind instanceof JsonString s ? s.value() : null;
        if ("assign".equals(k)) {
            if (!o.has("value")) throw new AccordException("assign needs a value");
            return new AssignOp(opId, record, field, hlc, o.get("value"), deps(o));
        }
        if ("inc".equals(k)) {
            if (!(o.get("by") instanceof JsonNumber by) || !by.isSafeInteger()) {
                throw new AccordException("inc needs an integer \"by\"");
            }
            return new IncOp(opId, record, field, hlc, (long) by.doubleValue());
        }
        if ("add".equals(k)) {
            return new AddOp(opId, record, field, hlc, element(o), o.has("deps") ? deps(o) : List.of());
        }
        if ("remove".equals(k)) return new RemoveOp(opId, record, field, hlc, element(o), deps(o));
        throw new AccordException("unknown op kind " + (kind == null ? "undefined" : Json.stringify(kind)));
    }

    /**
     * Whether a value may be a set element: a string or a finite number.
     *
     * @param e the value
     * @return true for a string or finite number
     */
    public static boolean isElement(JsonValue e) {
        return e instanceof JsonString || (e instanceof JsonNumber n && n.isFinite());
    }

    private static JsonArray strings(List<String> xs) {
        List<JsonValue> out = new ArrayList<>(xs.size());
        for (String x : xs) out.add(new JsonString(x));
        return new JsonArray(out);
    }

    private static String str(JsonObject o, String key) {
        if (!(o.get(key) instanceof JsonString s)) throw new AccordException("\"" + key + "\" must be a string");
        return s.value();
    }

    private static List<String> deps(JsonObject o) {
        if (!(o.get("deps") instanceof JsonArray d)) throw new AccordException("\"deps\" must be an array of op ids");
        List<String> out = new ArrayList<>(d.items().size());
        for (JsonValue v : d.items()) OpId.parse(jsString(v));
        for (JsonValue v : d.items()) {
            if (!(v instanceof JsonString s)) throw new AccordException("\"deps\" must contain strings");
            out.add(s.value());
        }
        return out;
    }

    private static JsonValue element(JsonObject o) {
        JsonValue e = o.get("element");
        if (e != null && isElement(e)) return e;
        throw new AccordException("\"element\" must be a string or finite number");
    }

    /** JavaScript's {@code String(v)} for a JSON value. */
    private static String jsString(JsonValue v) {
        if (v instanceof JsonString s) return s.value();
        if (v instanceof JsonNumber n) return n.isFinite() ? Json.number(n) : Double.toString(n.doubleValue());
        if (v instanceof JsonBool b) return Boolean.toString(b.value());
        if (v instanceof JsonNull) return "null";
        if (v instanceof JsonArray a) {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < a.items().size(); i++) {
                if (i > 0) out.append(',');
                JsonValue x = a.items().get(i);
                if (!(x instanceof JsonNull)) out.append(jsString(x));
            }
            return out.toString();
        }
        return "[object Object]";
    }
}
