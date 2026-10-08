package io.github.crossben.accordsync.fleet;

import io.github.crossben.accordsync.client.AccordClient;
import io.github.crossben.accordsync.client.PullResult;
import io.github.crossben.accordsync.client.PushResult;
import io.github.crossben.accordsync.client.Transport;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonBool;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.core.Strategy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/**
 * Shared helpers: the server under test ({@code node/server.mjs}), its test-only routes, a lossy
 * network for the Java devices, and TypeScript devices driven over stdin/stdout.
 */
final class Fleet {
    private Fleet() {}

    static final String SERVER_URL = env("ACCORD_URL", "http://localhost:8721");
    static final String TEST_URL = env("ACCORD_TEST_URL", "http://localhost:8722");
    static final Path NODE_DIR = Path.of(env("ACCORD_NODE_DIR", "node")).toAbsolutePath();

    /** The same schema as {@code node/schema.mjs}. */
    static final Schema SCHEMA = Schema.define(Map.of("dossier", Map.of(
            "agent", Strategy.lww(),
            "zone", Strategy.lww(),
            "client_name", Strategy.lww(),
            "status", Strategy.conflict(),
            "visits", Strategy.counter(),
            "docs", Strategy.set())));

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static JsonObject get(String base, String path) {
        try {
            HttpResponse<String> res = HTTP.send(
                    HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(30)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) throw new IllegalStateException(path + " → " + res.statusCode() + " " + res.body());
            return (JsonObject) Json.parse(res.body());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Fails with instructions when the server is not running. */
    static void requireServer() {
        try {
            HTTP.send(HttpRequest.newBuilder(URI.create(SERVER_URL + "/health")).timeout(Duration.ofSeconds(5)).build(),
                    HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(
                    "No Accord server at " + SERVER_URL + ". Start it with interop/client-fleet/run.sh (see README).", e);
        }
    }

    static void resetServer() {
        get(TEST_URL, "/reset");
    }

    /** Runs compaction; returns how many records were folded. */
    static long compactServer() {
        return ((JsonNumber) get(TEST_URL, "/compact").get("records")).longValue();
    }

    /** A JWT for {@code sub} with read and write access to {@code zones}. */
    static String tokenFor(String sub, List<String> zones) {
        StringBuilder q = new StringBuilder("/token?sub=").append(URLEncoder.encode(sub, StandardCharsets.UTF_8));
        for (String z : zones) q.append("&zone=").append(URLEncoder.encode(z, StandardCharsets.UTF_8));
        return ((JsonString) get(TEST_URL, q.toString()).get("token")).value();
    }

    /** The canonical state of every record a device holds, like the TypeScript side computes it. */
    static String snapshotOf(AccordClient c) {
        Map<String, JsonValue> all = new TreeMap<>();
        for (String r : c.records()) all.put(r, c.read(r).orElseThrow());
        return Json.canonical(Json.of(all));
    }

    /** A network that loses requests, and loses responses after the server applied the request. */
    static final class FlakyTransport implements Transport {
        private final Transport inner;
        private final Random rng;
        volatile double loss;

        FlakyTransport(Transport inner, Random rng, double loss) {
            this.inner = inner;
            this.rng = rng;
            this.loss = loss;
        }

        private void maybeLose(String what) {
            if (rng.nextDouble() < loss / 2) throw new UncheckedIOException(new IOException("network down (" + what + " lost)"));
        }

        @Override
        public PushResult push(String deviceId, List<JsonObject> ops) {
            maybeLose("request");
            PushResult r = inner.push(deviceId, ops);
            maybeLose("response");
            return r;
        }

        @Override
        public PullResult pull(String deviceId, long cursor, int limit) {
            maybeLose("request");
            PullResult r = inner.pull(deviceId, cursor, limit);
            maybeLose("response");
            return r;
        }
    }

    /** A TypeScript device ({@code node/ts-client.mjs}), driven over stdin/stdout, one JSON line each. */
    static final class TsDevice implements AutoCloseable {
        private final Process proc;
        private final BufferedWriter in;
        private final BufferedReader out;

        TsDevice(String deviceId, String token, long seed, double loss) {
            try {
                proc = new ProcessBuilder("node", "ts-client.mjs")
                        .directory(NODE_DIR.toFile())
                        .redirectError(ProcessBuilder.Redirect.INHERIT)
                        .start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            call(cmd("open", "deviceId", deviceId, "token", token, "url", SERVER_URL, "seed", seed, "loss", loss));
        }

        /** A command object; arguments are name/value pairs (values may be null). */
        static Map<String, Object> cmd(String name, Object... pairs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cmd", name);
            for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
            return m;
        }

        JsonObject call(Map<String, Object> command) {
            try {
                in.write(Json.stringify(Json.of(command)));
                in.write('\n');
                in.flush();
                String line = out.readLine();
                if (line == null) throw new IllegalStateException("ts-client exited");
                JsonObject answer = (JsonObject) Json.parse(line);
                if (!JsonBool.TRUE.equals(answer.get("ok")) && !"sync".equals(command.get("cmd"))) {
                    throw new IllegalStateException("ts: " + answer.get("error"));
                }
                return answer;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        String snapshot() {
            return ((JsonString) call(cmd("snapshot")).get("snapshot")).value();
        }

        long pending() {
            return ((JsonNumber) call(cmd("snapshot")).get("pending")).longValue();
        }

        @Override
        public void close() {
            if (proc.isAlive()) {
                try {
                    call(cmd("close"));
                    if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly();
                } catch (RuntimeException e) {
                    proc.destroyForcibly();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    proc.destroyForcibly();
                }
            }
        }
    }
}
