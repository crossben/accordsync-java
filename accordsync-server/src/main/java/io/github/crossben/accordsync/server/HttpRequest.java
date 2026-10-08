package io.github.crossben.accordsync.server;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * An HTTP request as {@link AccordServer#handle} needs it, independent of any framework.
 *
 * @param method the method, e.g. {@code GET} (any case)
 * @param path the path without the query string, e.g. {@code /v1/pull}
 * @param rawQuery the raw (still percent-encoded) query string without {@code ?}, or empty
 * @param headers header names (any case) to values; names are lowercased here
 * @param body the body bytes (empty when none)
 */
public record HttpRequest(String method, String path, String rawQuery, Map<String, String> headers, byte[] body) {
    /**
     * Creates the request.
     *
     * @param method the method
     * @param path the path
     * @param rawQuery the raw query, or null
     * @param headers the headers, or null
     * @param body the body, or null
     */
    public HttpRequest {
        method = Objects.requireNonNull(method, "method").toUpperCase(Locale.ROOT);
        path = Objects.requireNonNull(path, "path");
        rawQuery = rawQuery == null ? "" : rawQuery;
        Map<String, String> h = new LinkedHashMap<>();
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                h.putIfAbsent(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        headers = Map.copyOf(h);
        body = body == null ? new byte[0] : body;
    }

    /**
     * A header value.
     *
     * @param name the header name, any case
     * @return its value, or null when absent
     */
    public String header(String name) {
        return headers.get(name.toLowerCase(Locale.ROOT));
    }
}
