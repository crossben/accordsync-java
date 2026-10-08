package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The server's answer to a push.
 *
 * @param acked op ids now stored by the server
 * @param refused ops the server will never accept
 */
public record PushResult(List<String> acked, List<Refused> refused) {
    /**
     * Creates the result; the lists are copied.
     *
     * @param acked acknowledged ids
     * @param refused refusals
     */
    public PushResult {
        acked = List.copyOf(acked);
        refused = List.copyOf(refused);
    }

    /**
     * A refused op.
     *
     * @param opId the op id
     * @param reason why
     */
    public record Refused(String opId, String reason) {
        /**
         * Creates the refusal.
         *
         * @param opId the op id
         * @param reason the reason
         */
        public Refused {
            Objects.requireNonNull(opId, "opId");
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * Reads {@code {"acked": [...], "refused": [{"op_id", "reason"}]}}.
     *
     * @param json the response body
     * @return the result
     * @throws AccordException when malformed
     */
    public static PushResult fromJson(JsonValue json) {
        if (!(json instanceof JsonObject o) || !(o.get("acked") instanceof JsonArray a)
                || !(o.get("refused") instanceof JsonArray r)) {
            throw new AccordException("push response must be {acked, refused}");
        }
        List<String> acked = new ArrayList<>();
        for (JsonValue v : a.items()) {
            if (!(v instanceof JsonString s)) throw new AccordException("acked must hold op ids");
            acked.add(s.value());
        }
        List<Refused> refused = new ArrayList<>();
        for (JsonValue v : r.items()) {
            if (!(v instanceof JsonObject ro) || !(ro.get("op_id") instanceof JsonString id)
                    || !(ro.get("reason") instanceof JsonString why)) {
                throw new AccordException("refused must hold {op_id, reason}");
            }
            refused.add(new Refused(id.value(), why.value()));
        }
        return new PushResult(acked, refused);
    }
}
