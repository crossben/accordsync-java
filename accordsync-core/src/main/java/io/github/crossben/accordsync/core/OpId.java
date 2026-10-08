package io.github.crossben.accordsync.core;

import java.util.Comparator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed op id, {@code device:sequence}, and the rules for op ids and record ids.
 *
 * @param device the device id
 * @param seq the sequence number (as a JavaScript number: beyond 2^53 it is rounded)
 */
public record OpId(String device, long seq) {
    private static final Pattern OP_ID = Pattern.compile("([A-Za-z0-9_-]{1,64}):([1-9][0-9]{0,15})");
    private static final Pattern TYPE = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    /** Op id order: UTF-16 code units, never case-folded. */
    public static final Comparator<String> ORDER = OpId::compare;

    /**
     * Parses {@code device:sequence}.
     *
     * @param opId the op id
     * @return its parts
     * @throws AccordException when malformed
     */
    public static OpId parse(String opId) {
        Matcher m = OP_ID.matcher(opId);
        if (!m.matches()) throw new AccordException("malformed op id \"" + opId + "\" (expected device:sequence)");
        long seq = Long.parseLong(m.group(2));
        if (seq > JsonNumber.MAX_SAFE_INTEGER) seq = (long) (double) seq;
        return new OpId(m.group(1), seq);
    }

    /**
     * The type of a record id {@code type:id}: a type of 1 to 64 of {@code [A-Za-z][A-Za-z0-9_]*}
     * and an id of 1 to 256 UTF-16 code units (any characters).
     *
     * @param record the record id
     * @return the type
     * @throws AccordException when malformed
     */
    public static String recordType(String record) {
        int colon = record.indexOf(':');
        if (colon > 0) {
            String type = record.substring(0, colon);
            int idLength = record.length() - colon - 1;
            if (TYPE.matcher(type).matches() && idLength >= 1 && idLength <= 256) return type;
        }
        throw new AccordException("malformed record id \"" + record + "\" (expected type:id)");
    }

    /**
     * Compares op ids by UTF-16 code unit.
     *
     * @param a an op id
     * @param b another
     * @return negative, zero or positive
     */
    public static int compare(String a, String b) {
        return a.compareTo(b);
    }
}
