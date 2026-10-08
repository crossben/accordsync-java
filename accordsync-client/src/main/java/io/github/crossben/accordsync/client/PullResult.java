package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonBool;
import io.github.crossben.accordsync.core.JsonNull;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonValue;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/** The server's answer to a pull: a page, or a request to resync. */
public sealed interface PullResult {
    /**
     * A page of the feed.
     *
     * @param items the items
     * @param cursor the feed position after this page
     * @param hasMore whether more pages follow
     * @param deviceSeq the highest op number the server has applied from this device, if sent
     */
    record PullPage(List<PullItem> items, long cursor, boolean hasMore, OptionalLong deviceSeq) implements PullResult {
        /**
         * Creates the page; the list is copied.
         *
         * @param items the items
         * @param cursor the cursor
         * @param hasMore more pages
         * @param deviceSeq the device's sequence
         */
        public PullPage {
            items = List.copyOf(items);
            deviceSeq = deviceSeq == null ? OptionalLong.empty() : deviceSeq;
        }
    }

    /** The server asks the device to drop its data and pull from zero. */
    record ResyncRequired() implements PullResult {}

    /**
     * Reads a pull response body.
     *
     * @param json the body
     * @return the result
     * @throws AccordException when malformed
     */
    static PullResult fromJson(JsonValue json) {
        if (!(json instanceof JsonObject o)) throw new AccordException("pull response must be an object");
        if (JsonBool.TRUE.equals(o.get("resync_required"))) return new ResyncRequired();
        if (!(o.get("items") instanceof JsonArray a) || !(o.get("cursor") instanceof JsonNumber c) || !c.isSafeInteger()
                || !(o.get("has_more") instanceof JsonBool more)) {
            throw new AccordException("pull response must be {items, cursor, has_more}");
        }
        List<PullItem> items = new ArrayList<>();
        for (JsonValue v : a.items()) items.add(PullItem.fromJson(v));
        JsonValue seq = o.get("device_seq");
        OptionalLong deviceSeq;
        if (seq == null || seq instanceof JsonNull) deviceSeq = OptionalLong.empty();
        else if (seq instanceof JsonNumber n && n.isSafeInteger()) deviceSeq = OptionalLong.of(n.longValue());
        else throw new AccordException("device_seq must be an integer");
        return new PullPage(items, c.longValue(), more.value(), deviceSeq);
    }
}
