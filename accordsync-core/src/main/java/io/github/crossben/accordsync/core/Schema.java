package io.github.crossben.accordsync.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/** Record types and the strategy of each of their fields. Immutable. */
public final class Schema {
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final Map<String, Map<String, Strategy>> types;

    private Schema(Map<String, Map<String, Strategy>> types) {
        this.types = types;
    }

    /**
     * Defines a schema: type name to field name to a {@link Strategy} or its name.
     *
     * @param schema the definition (iteration order is kept)
     * @return the schema
     * @throws AccordException for an invalid type name or unknown strategy
     */
    public static Schema define(Map<String, ? extends Map<String, ?>> schema) {
        Map<String, Map<String, Strategy>> types = new LinkedHashMap<>();
        for (Map.Entry<String, ? extends Map<String, ?>> t : schema.entrySet()) {
            String type = t.getKey();
            if (type == null || !TYPE.matcher(type).matches()) throw new AccordException("invalid record type \"" + type + "\"");
            Map<String, Strategy> fields = new LinkedHashMap<>();
            for (Map.Entry<String, ?> f : t.getValue().entrySet()) {
                Object s = f.getValue();
                Strategy strategy;
                if (s instanceof Strategy st) strategy = st;
                else if (s instanceof String name) {
                    try {
                        strategy = Strategy.of(name);
                    } catch (AccordException e) {
                        throw new AccordException(type + "." + f.getKey() + ": unknown strategy");
                    }
                } else throw new AccordException(type + "." + f.getKey() + ": unknown strategy");
                fields.put(f.getKey(), strategy);
            }
            types.put(type, Collections.unmodifiableMap(fields));
        }
        return new Schema(Collections.unmodifiableMap(types));
    }

    /**
     * The definition.
     *
     * @return type to field to strategy, unmodifiable
     */
    public Map<String, Map<String, Strategy>> types() {
        return types;
    }

    /**
     * The strategy for {@code record.field}.
     *
     * @param record the record id
     * @param field the field
     * @return the strategy
     * @throws AccordException naming what is wrong
     */
    public Strategy strategyFor(String record, String field) {
        String type = OpId.recordType(record);
        Map<String, Strategy> fields = types.get(type);
        if (fields == null) throw new AccordException("unknown record type \"" + type + "\"");
        Strategy s = fields.get(field);
        if (s == null) throw new AccordException("unknown field \"" + type + "." + field + "\"");
        return s;
    }

    /**
     * The fields of a record's type.
     *
     * @param record the record id
     * @return field to strategy
     * @throws AccordException for an unknown type
     */
    public Map<String, Strategy> fieldsOf(String record) {
        String type = OpId.recordType(record);
        Map<String, Strategy> fields = types.get(type);
        if (fields == null) throw new AccordException("unknown record type \"" + type + "\"");
        return fields;
    }
}
