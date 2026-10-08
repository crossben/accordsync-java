package io.github.crossben.accordsync.fleet;

import io.github.crossben.accordsync.client.AccordClient;
import io.github.crossben.accordsync.client.HttpTransport;
import io.github.crossben.accordsync.client.MemoryStorage;
import io.github.crossben.accordsync.client.PullResult;
import io.github.crossben.accordsync.client.PushResult;
import io.github.crossben.accordsync.client.Transport;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.RecordSnapshot;
import io.github.crossben.accordsync.core.Replica;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.core.Wire;
import io.github.crossben.accordsync.server.Databases;
import io.github.crossben.accordsync.server.Migrations;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;

/**
 * The mixed-server fleet's machinery (a port of python/server-interop/tests/fleet.py): one
 * PostgreSQL database, the TypeScript reference server (Accord workspace sources) and the Java
 * server running on it at once, devices of both languages whose every request goes to a server
 * picked at random through a lossy network.
 */
final class Fleet {
    private Fleet() {}

    static final Path HERE = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    static final Path JAVA_DIR = HERE.resolve("../..").normalize();
    static final Path NODE_DIR = HERE.resolve("node");
    static final Path LOG_DIR = Path.of(env("ACCORD_FLEET_LOGS", HERE.resolve(".logs").toString()));
    static final String ADMIN_URL = env("ACCORD_DATABASE_URL", "");
    static final Path APP_DIR = Path.of(env("ACCORD_APP_DIR", JAVA_DIR.resolve("../app").toString())).toAbsolutePath().normalize();
    static final String SERVER_CP = env("ACCORD_SERVER_CP", "");
    static final int TS_PORT = Integer.parseInt(env("ACCORD_TS_PORT", "8741"));
    static final int TS_CONTROL_PORT = Integer.parseInt(env("ACCORD_TS_CONTROL_PORT", "8742"));
    static final int JAVA_PORT = Integer.parseInt(env("ACCORD_JAVA_PORT", "8743"));
    static final int JAVA_CONTROL_PORT = Integer.parseInt(env("ACCORD_JAVA_CONTROL_PORT", "8744"));
    static final String DATABASE = "accord_fleet";
    /** ACCORD_FLEET_DEBUG=<record>: the Java devices log every pull's items for that record. */
    static final String DEBUG = System.getenv("ACCORD_FLEET_DEBUG");

    /** Server name to base URL, in name order (the random pick indexes this order). */
    static final Map<String, String> SERVERS = new TreeMap<>(Map.of(
            "java", "http://127.0.0.1:" + JAVA_PORT, "ts", "http://127.0.0.1:" + TS_PORT));
    static final Map<String, String> CONTROL = Map.of(
            "java", "http://127.0.0.1:" + JAVA_CONTROL_PORT, "ts", "http://127.0.0.1:" + TS_CONTROL_PORT);
    static final List<String> NAMES = List.copyOf(SERVERS.keySet());
    /** The servers devices route to: both, or one (ACCORD_FLEET_ONLY=ts|java, for diagnosis). */
    static final List<String> ROUTE = env("ACCORD_FLEET_ONLY", "").isEmpty() ? NAMES : List.of(env("ACCORD_FLEET_ONLY", ""));
    static final List<String> TSX = List.of("node", "--import", "tsx", "--conditions=@accordsync/source");

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** The conformance profile's schema (app/conformance/PROFILE.md). */
    static final Schema SCHEMA = Schema.define(Map.of("dossier", Map.of(
            "agent", "lww", "zone", "lww", "client_name", "lww", "status", "conflict", "visits", "counter", "docs", "set")));

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    static List<String> scopeKeys(JsonObject fields) {
        TreeSet<String> keys = new TreeSet<>();
        for (String k : List.of("agent", "zone")) {
            if (fields.get(k) instanceof JsonString s) keys.add(k + ":" + s.value());
        }
        return List.copyOf(keys);
    }

    // ------------------------------------------------------------------ database

    static String databaseUrl(String name) {
        URI u = URI.create(ADMIN_URL);
        return u.getScheme() + "://" + u.getRawAuthority() + "/" + name + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
    }

    static String recreateDatabase() throws SQLException {
        try (Connection c = Databases.fromUrl(ADMIN_URL).getConnection(); Statement st = c.createStatement()) {
            st.execute("drop database if exists \"" + DATABASE + "\" with (force)");
            st.execute("create database \"" + DATABASE + "\"");
        }
        return databaseUrl(DATABASE);
    }

    static DataSource db(String url) {
        return Databases.fromUrl(url);
    }

    static List<String> ledger(String url) throws SQLException {
        List<String> out = new ArrayList<>();
        for (List<Object> r : query(url, "select name, timestamp from kysely_migration order by name")) {
            out.add(r.get(0) + "@" + r.get(1));
        }
        return out;
    }

    static void migrateTs(String url) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(TSX);
        cmd.add(NODE_DIR.resolve("migrate.mts").toString());
        cmd.add(url);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(APP_DIR.resolve("conformance").toFile()).redirectErrorStream(true);
        pb.environment().put("ACCORD_APP_DIR", APP_DIR.toString());
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IllegalStateException("migrate.mts failed: " + out);
    }

    static List<String> migrateJava(String url) throws SQLException {
        return Migrations.migrate(db(url));
    }

    static List<List<Object>> query(String url, String sql, Object... params) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (Connection c = db(url).getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                int n = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    List<Object> row = new ArrayList<>();
                    for (int i = 1; i <= n; i++) row.add(rs.getObject(i));
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** What the database holds: each record rebuilt from the feed, and the records whose row disagrees. */
    record Truth(Map<String, JsonObject> rebuilt, List<String> wrong) {}

    static Truth serverTruth(String url) throws SQLException {
        Map<String, JsonObject> rebuilt = new TreeMap<>();
        List<String> wrong = new ArrayList<>();
        try (Connection c = db(url).getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select record, scopes, state::text from records order by record");
                PreparedStatement feed = c.prepareStatement(
                        "select kind, op::text from feed where record = ? and kind in ('op', 'snapshot') order by pos, seq")) {
            while (rs.next()) {
                String record = rs.getString(1);
                Array a = rs.getArray(2);
                List<String> scopes = new ArrayList<>(Arrays.asList((String[]) a.getArray()));
                String state = rs.getString(3);
                Replica replica = new Replica(SCHEMA);
                feed.setString(1, record);
                try (ResultSet f = feed.executeQuery()) {
                    while (f.next()) {
                        JsonValue op = Json.parse(f.getString(2));
                        if (f.getString(1).equals("snapshot")) replica.loadSnapshot(RecordSnapshot.fromJson(op));
                        else replica.apply(Wire.decode(op));
                    }
                }
                JsonObject fields = replica.read(record).orElse(JsonObject.EMPTY);
                rebuilt.put(record, fields);
                Replica stored = new Replica(SCHEMA);
                if (state != null) stored.loadSnapshot(RecordSnapshot.fromJson(Json.parse(state)));
                scopes.sort(null);
                if (!Json.canonical(stored.read(record).orElse(JsonObject.EMPTY)).equals(Json.canonical(fields))
                        || !scopes.equals(scopeKeys(fields))) {
                    wrong.add(record);
                }
            }
        }
        return new Truth(rebuilt, wrong);
    }

    // ------------------------------------------------------------------ servers

    /** The TypeScript reference server and the Java server, both on one database. */
    static final class Servers implements AutoCloseable {
        private final List<Process> procs = new ArrayList<>();

        Servers(String url, String tag) throws IOException, InterruptedException {
            Files.createDirectories(LOG_DIR);
            if (SERVER_CP.isEmpty()) throw new IllegalStateException("ACCORD_SERVER_CP is not set: run interop/server-fleet/run.sh");
            try {
                List<String> ts = new ArrayList<>(TSX);
                ts.add("reference-server.ts");
                start("ts", ts, APP_DIR.resolve("conformance"), url, TS_PORT, TS_CONTROL_PORT, tag);
                // The harness of tools/conformance-server.sh, with the classpath run.sh built.
                start("java", List.of("java", "-cp", SERVER_CP, "tools/ConformanceServer.java"), JAVA_DIR, url, JAVA_PORT,
                        JAVA_CONTROL_PORT, tag);
            } catch (IOException | InterruptedException | RuntimeException e) {
                close();
                throw e;
            }
        }

        private void start(String name, List<String> cmd, Path cwd, String url, int port, int control, String tag)
                throws IOException, InterruptedException {
            Path log = LOG_DIR.resolve(tag + "-" + name + ".log");
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true)
                    .redirectOutput(log.toFile());
            pb.environment().put("ACCORD_DATABASE_URL", url);
            pb.environment().put("ACCORD_PORT", String.valueOf(port));
            pb.environment().put("ACCORD_CONTROL_PORT", String.valueOf(control));
            Process p = pb.start();
            procs.add(p);
            // run.sh kills these by PID if the tests themselves are killed.
            Files.writeString(LOG_DIR.resolve("servers.pid"), p.pid() + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            String health = SERVERS.get(name) + "/health";
            for (int i = 0; i < 480; i++) {
                if (!p.isAlive()) throw new IllegalStateException(name + " server exited (" + p.exitValue() + "):\n" + Files.readString(log));
                try {
                    HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(health)).timeout(Duration.ofSeconds(2)).build(),
                            HttpResponse.BodyHandlers.ofString());
                    if (res.statusCode() == 200) return;
                } catch (IOException e) {
                    // not up yet
                }
                Thread.sleep(250);
            }
            throw new IllegalStateException(name + " server did not start:\n" + Files.readString(log));
        }

        @Override
        public void close() {
            for (Process p : procs) {
                p.destroy();
                try {
                    if (!p.waitFor(10, TimeUnit.SECONDS)) {
                        p.destroyForcibly();
                        p.waitFor(10, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    static String encodeQuery(List<Map.Entry<String, String>> params) {
        StringBuilder q = new StringBuilder();
        for (Map.Entry<String, String> e : params) {
            if (q.length() > 0) q.append('&');
            q.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return q.toString();
    }

    static JsonObject control(String server, String method, String path, List<Map.Entry<String, String>> params)
            throws IOException, InterruptedException {
        String q = params.isEmpty() ? "" : "?" + encodeQuery(params);
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(CONTROL.get(server) + path + q))
                .method(method, HttpRequest.BodyPublishers.noBody()).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) throw new IllegalStateException(server + " " + path + " -> " + res.statusCode() + " " + res.body());
        return (JsonObject) Json.parse(res.body());
    }

    static String tokenFor(String server, String sub, List<String> zones) throws IOException, InterruptedException {
        List<Map.Entry<String, String>> p = new ArrayList<>();
        p.add(Map.entry("sub", sub));
        for (String z : zones) p.add(Map.entry("zone", z));
        return ((JsonString) control(server, "GET", "/token", p).get("token")).value();
    }

    // ------------------------------------------------------------------ devices

    /** Requests that reached each server, by "server/kind". */
    static final class Served {
        final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        void add(String server, String kind, int n) {
            counts.computeIfAbsent(server + "/" + kind, k -> new AtomicInteger()).addAndGet(n);
        }

        int get(String key) {
            AtomicInteger n = counts.get(key);
            return n == null ? 0 : n.get();
        }

        Map<String, Integer> snapshot() {
            Map<String, Integer> out = new TreeMap<>();
            counts.forEach((k, v) -> out.put(k, v.get()));
            return out;
        }
    }

    /** One device of the fleet, Java or TypeScript. */
    interface Device extends AutoCloseable {
        String id();

        void edit(String cmd, String record, String field, Object value);

        void setToken(String token);

        void sync(boolean lossy);

        void setLoss(double loss);

        boolean has(String record);

        /** @return the canonical snapshot and the pending count */
        Map.Entry<String, Integer> snapshot();

        @Override
        void close();
    }

    static boolean networkDown(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("network down")) return true;
        }
        return false;
    }

    /**
     * Sends each request to a server picked at random; loses requests, and loses responses after
     * the server applied the request.
     */
    static final class FleetTransport implements Transport {
        private final Random rng;
        private final Served served;
        private final Map<String, HttpTransport> inner = new LinkedHashMap<>();
        volatile double loss;

        FleetTransport(Random rng, Served served, java.util.function.Supplier<String> token) {
            this.rng = rng;
            this.served = served;
            for (String name : NAMES) inner.put(name, new HttpTransport(SERVERS.get(name), token, Duration.ofSeconds(60)));
        }

        private synchronized String pick() {
            return ROUTE.get(rng.nextInt(ROUTE.size()));
        }

        private synchronized boolean lose() {
            return rng.nextDouble() < loss / 2;
        }

        @Override
        public PushResult push(String deviceId, List<JsonObject> ops) {
            String name = pick();
            if (lose()) throw new UncheckedIOException(new IOException("network down (request lost)"));
            served.add(name, "push", 1);
            PushResult r = inner.get(name).push(deviceId, ops);
            if (lose()) throw new UncheckedIOException(new IOException("network down (response lost)"));
            return r;
        }

        final List<String> log = java.util.Collections.synchronizedList(new ArrayList<>());

        private void debug(String name, String deviceId, long cursor, PullResult r) {
            StringBuilder b = new StringBuilder(name + " " + deviceId + " pull@" + cursor + " -> ");
            if (r instanceof PullResult.PullPage p) {
                b.append(p.cursor()).append(p.hasMore() ? "+" : "").append(" ");
                for (io.github.crossben.accordsync.client.PullItem i : p.items()) {
                    if (i instanceof io.github.crossben.accordsync.client.PullItem.OpItem o
                            && DEBUG.equals(((JsonString) o.op().get("record")).value())) b.append("op ").append(Json.stringify(o.op())).append(" ");
                    if (i instanceof io.github.crossben.accordsync.client.PullItem.SnapshotItem sn && DEBUG.equals(sn.snapshot().record())) {
                        b.append("snapshot ").append(Json.stringify(sn.snapshot().toJson())).append(" ");
                    }
                    if (i instanceof io.github.crossben.accordsync.client.PullItem.ExitItem e && DEBUG.equals(e.record())) b.append("EXIT ");
                }
            } else {
                b.append("RESYNC");
            }
            log.add(b.toString());
        }

        @Override
        public PullResult pull(String deviceId, long cursor, int limit) {
            String name = pick();
            if (lose()) throw new UncheckedIOException(new IOException("network down (request lost)"));
            served.add(name, "pull", 1);
            PullResult r = inner.get(name).pull(deviceId, cursor, limit);
            if (DEBUG != null) debug(name, deviceId, cursor, r);
            if (lose()) throw new UncheckedIOException(new IOException("network down (response lost)"));
            return r;
        }
    }

    /** accordsync-client with a MemoryStorage. */
    static final class JavaDevice implements Device {
        private final String id;
        private volatile String token;
        final FleetTransport net;
        final AccordClient client;

        JavaDevice(String id, String token, Random rng, Served served) {
            this.id = id;
            this.token = token;
            this.net = new FleetTransport(rng, served, () -> this.token);
            this.client = AccordClient.open(AccordClient.options().schema(SCHEMA).storage(new MemoryStorage())
                    .deviceId(id).transport(net));
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void edit(String cmd, String record, String field, Object value) {
            switch (cmd) {
                case "assign" -> client.assign(record, field, value);
                case "inc" -> client.inc(record, field, ((Number) value).longValue());
                case "add" -> client.add(record, field, value);
                case "remove" -> client.remove(record, field, value);
                default -> throw new IllegalArgumentException(cmd);
            }
        }

        @Override
        public void setToken(String token) {
            this.token = token;
        }

        @Override
        public void sync(boolean lossy) {
            try {
                client.sync();
            } catch (RuntimeException e) {
                if (!lossy || !networkDown(e)) throw e;
            }
        }

        @Override
        public void setLoss(double loss) {
            net.loss = loss;
        }

        @Override
        public boolean has(String record) {
            return client.records().contains(record);
        }

        @Override
        public Map.Entry<String, Integer> snapshot() {
            Map<String, Object> all = new LinkedHashMap<>();
            for (String r : client.records()) all.put(r, client.read(r).orElse(null));
            return Map.entry(Json.canonical(Json.of(all)), client.status().pending());
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /** node/ts-client.mts: @accordsync/client from the workspace, over stdin/stdout. */
    static final class TsDevice implements Device {
        private final String id;
        private final Process proc;
        private final BufferedWriter in;
        private final BufferedReader out;

        TsDevice(String id, String token, long seed) throws IOException {
            this.id = id;
            List<String> cmd = new ArrayList<>(TSX);
            cmd.add(NODE_DIR.resolve("ts-client.mts").toString());
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(APP_DIR.resolve("conformance").toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT);
            pb.environment().put("ACCORD_APP_DIR", APP_DIR.toString());
            proc = pb.start();
            in = new BufferedWriter(new OutputStreamWriter(proc.getOutputStream(), StandardCharsets.UTF_8));
            out = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8));
            Map<String, Object> open = new LinkedHashMap<>();
            open.put("cmd", "open");
            open.put("deviceId", id);
            open.put("token", token);
            Map<String, String> route = new TreeMap<>();
            for (String name : ROUTE) route.put(name, SERVERS.get(name));
            open.put("servers", route);
            open.put("seed", seed);
            open.put("loss", 0);
            call(open);
        }

        synchronized JsonObject call(Map<String, Object> command) {
            try {
                in.write(Json.stringify(Json.of(command)));
                in.write('\n');
                in.flush();
                String line = out.readLine();
                if (line == null) throw new IllegalStateException("ts-client exited");
                JsonObject answer = (JsonObject) Json.parse(line);
                if (!(answer.get("ok") instanceof io.github.crossben.accordsync.core.JsonBool b && b.value())
                        && !command.get("cmd").equals("sync")) {
                    throw new IllegalStateException("ts: " + answer.get("error"));
                }
                return answer;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static Map<String, Object> cmd(Object... kv) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
            return m;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void edit(String c, String record, String field, Object value) {
            call(cmd("cmd", c, "record", record, "field", field, "value", value));
        }

        @Override
        public void setToken(String token) {
            call(cmd("cmd", "token", "token", token));
        }

        @Override
        public void sync(boolean lossy) {
            JsonObject a = call(cmd("cmd", "sync"));
            if (a.get("ok") instanceof io.github.crossben.accordsync.core.JsonBool b && b.value()) return;
            String error = String.valueOf(a.get("error"));
            if (!(lossy && error.contains("network down"))) throw new IllegalStateException(id + " sync: " + error);
        }

        @Override
        public void setLoss(double loss) {
            call(cmd("cmd", "loss", "loss", loss));
        }

        @Override
        public boolean has(String record) {
            return call(cmd("cmd", "has", "record", record)).get("has") instanceof io.github.crossben.accordsync.core.JsonBool b
                    && b.value();
        }

        @Override
        public Map.Entry<String, Integer> snapshot() {
            JsonObject a = call(cmd("cmd", "snapshot"));
            return Map.entry(((JsonString) a.get("snapshot")).value(), (int) ((JsonNumber) a.get("pending")).longValue());
        }

        Map<String, Integer> served() {
            Map<String, Integer> out = new TreeMap<>();
            JsonObject s = (JsonObject) call(cmd("cmd", "snapshot")).get("served");
            for (Map.Entry<String, JsonValue> server : s.members().entrySet()) {
                for (Map.Entry<String, JsonValue> kind : ((JsonObject) server.getValue()).members().entrySet()) {
                    out.put(server.getKey() + "/" + kind.getKey(), (int) ((JsonNumber) kind.getValue()).longValue());
                }
            }
            return out;
        }

        @Override
        public void close() {
            if (!proc.isAlive()) return;
            try {
                call(cmd("cmd", "close"));
                if (!proc.waitFor(10, TimeUnit.SECONDS)) proc.destroyForcibly();
            } catch (RuntimeException e) {
                proc.destroyForcibly();
            } catch (InterruptedException e) {
                proc.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    static JsonArray array(JsonValue v) {
        return (JsonArray) v;
    }
}
