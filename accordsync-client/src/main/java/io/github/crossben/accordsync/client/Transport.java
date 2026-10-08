package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.JsonObject;
import java.util.List;

/**
 * How a client reaches the server. {@link HttpTransport} is the real one; tests can fake it.
 * Failures are unchecked exceptions ({@link HttpException}, {@link java.io.UncheckedIOException}).
 */
public interface Transport {
    /**
     * Pushes ops ({@code POST /v1/push}).
     *
     * @param deviceId the device
     * @param ops ops in the wire format, in write order
     * @return what the server acknowledged and refused
     */
    PushResult push(String deviceId, List<JsonObject> ops);

    /**
     * Pulls one page ({@code GET /v1/pull}).
     *
     * @param deviceId the device
     * @param cursor the feed position already applied
     * @param limit the most items wanted
     * @return a page, or {@link PullResult.ResyncRequired}
     */
    PullResult pull(String deviceId, long cursor, int limit);
}
