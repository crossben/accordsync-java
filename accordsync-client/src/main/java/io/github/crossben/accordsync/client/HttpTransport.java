package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonValue;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Talks to an Accord server over HTTP(S) with {@link HttpURLConnection} (available on Android, unlike
 * {@code java.net.http}). The token supplier is called before every request, so an app can refresh
 * its JWT at will.
 */
public final class HttpTransport implements Transport {
    private final String base;
    private final Supplier<String> token;
    private final int timeoutMs;

    /**
     * A transport with a 30 s timeout.
     *
     * @param url the server's base URL, e.g. {@code https://sync.example.com}
     * @param token returns the current JWT
     */
    public HttpTransport(String url, Supplier<String> token) {
        this(url, token, Duration.ofSeconds(30));
    }

    /**
     * A transport.
     *
     * @param url the server's base URL
     * @param token returns the current JWT
     * @param timeout connect and read timeout
     */
    public HttpTransport(String url, Supplier<String> token, Duration timeout) {
        String u = Objects.requireNonNull(url, "url");
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.base = u;
        this.token = Objects.requireNonNull(token, "token");
        this.timeoutMs = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
    }

    @Override
    public PushResult push(String deviceId, List<JsonObject> ops) {
        String body = Json.stringify(new JsonObject(Map.of("ops", new JsonArray(List.<JsonValue>copyOf(ops)))));
        return PushResult.fromJson(call(deviceId, "POST", "/v1/push", body));
    }

    @Override
    public PullResult pull(String deviceId, long cursor, int limit) {
        return PullResult.fromJson(call(deviceId, "GET", "/v1/pull?cursor=" + cursor + "&limit=" + limit, null));
    }

    private JsonValue call(String deviceId, String method, String path, String body) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) URI.create(base + path).toURL().openConnection();
            c.setRequestMethod(method);
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + token.get());
            c.setRequestProperty("Accord-Device", deviceId);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(bytes);
                }
            }
            int status = c.getResponseCode();
            boolean ok = status >= 200 && status < 300;
            String text = read(ok ? c.getInputStream() : c.getErrorStream());
            if (!ok) throw new HttpException(status, method + " " + path + " → " + status + " " + text);
            return Json.parse(text);
        } catch (IOException e) {
            throw new UncheckedIOException(method + " " + path + " failed: " + e.getMessage(), e);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
