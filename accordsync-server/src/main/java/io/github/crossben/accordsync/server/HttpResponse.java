package io.github.crossben.accordsync.server;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * An HTTP response from {@link AccordServer#handle}.
 *
 * @param status the status code
 * @param headers header name and value pairs, in order (a name may repeat)
 * @param body the body bytes (empty for 204)
 */
public record HttpResponse(int status, List<Map.Entry<String, String>> headers, byte[] body) {
    /**
     * Creates the response.
     *
     * @param status the status
     * @param headers the headers
     * @param body the body
     */
    public HttpResponse {
        headers = List.copyOf(headers);
        body = body == null ? new byte[0] : body;
    }

    /**
     * The first value of a header.
     *
     * @param name the name, any case
     * @return the value, or null
     */
    public String header(String name) {
        for (Map.Entry<String, String> h : headers) if (h.getKey().equalsIgnoreCase(name)) return h.getValue();
        return null;
    }

    /** @return the body as UTF-8 text */
    public String bodyText() {
        return new String(body, StandardCharsets.UTF_8);
    }
}
