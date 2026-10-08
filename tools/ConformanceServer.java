/*
 * The Java server configured with the conformance profile, plus the test-only control API.
 * Mirrors app/conformance/reference-server.ts (see app/conformance/PROFILE.md). Never expose the
 * control port outside a test environment.
 *
 *   ACCORD_DATABASE_URL=postgres://user:pw@127.0.0.1:5432/db ACCORD_PORT=8701 ACCORD_CONTROL_PORT=8702 \
 *     tools/conformance-server.sh
 *
 * Then, from the Accord repository's app/:
 *
 *   ACCORD_URL=http://127.0.0.1:8701 ACCORD_CONTROL_URL=http://127.0.0.1:8702 \
 *     pnpm --filter @accordsync/conformance test
 *
 * ACCORD_PROFILE names the profile.json to read (default contract/conformance/profile.json).
 */

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.server.Access;
import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.Auth;
import io.github.crossben.accordsync.server.Compaction;
import io.github.crossben.accordsync.server.Databases;
import io.github.crossben.accordsync.server.HttpRequest;
import io.github.crossben.accordsync.server.HttpResponse;
import io.github.crossben.accordsync.server.JsNumber;
import io.github.crossben.accordsync.server.Limits;
import io.github.crossben.accordsync.server.Migrations;
import io.github.crossben.accordsync.server.RateLimit;
import io.github.crossben.accordsync.server.ServerDefinition;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

public class ConformanceServer {
    static JsonObject profile;
    static AccordServer server;
    static PGSimpleDataSource raw;

    public static void main(String[] args) throws Exception {
        String url = System.getenv("ACCORD_DATABASE_URL");
        if (url == null || url.isEmpty()) throw new IllegalStateException("ACCORD_DATABASE_URL is required");
        int port = Integer.parseInt(env("ACCORD_PORT", "8701"));
        int controlPort = Integer.parseInt(env("ACCORD_CONTROL_PORT", "8702"));
        String profilePath = env("ACCORD_PROFILE", "contract/conformance/profile.json");
        profile = (JsonObject) Json.parse(Files.readString(Path.of(profilePath)));

        raw = Databases.fromUrl(url);
        Migrations.migrate(raw);
        DataSource pool = new TinyPool(raw, 20);
        server = new AccordServer(definition(), pool);

        HttpServer sync = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 1024);
        sync.setExecutor(Executors.newCachedThreadPool());
        sync.createContext("/", ConformanceServer::serveSync);
        sync.start();
        HttpServer control = HttpServer.create(new InetSocketAddress("127.0.0.1", controlPort), 128);
        control.setExecutor(Executors.newCachedThreadPool());
        control.createContext("/", ConformanceServer::serveControl);
        control.start();
        System.out.println("accord java server on :" + port + ", control API on :" + controlPort);
    }

    static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isEmpty() ? fallback : v;
    }

    // ------------------------------------------------------------------ the profile

    static JsonObject obj(JsonObject o, String key) {
        return (JsonObject) o.get(key);
    }

    static double num(JsonObject o, String key) {
        return ((JsonNumber) o.get(key)).doubleValue();
    }

    static List<String> strings(JsonValue v) {
        List<String> out = new ArrayList<>();
        if (v instanceof JsonArray a) for (JsonValue x : a.items()) if (x instanceof JsonString s) out.add(s.value());
        return out;
    }

    static ServerDefinition definition() {
        JsonObject auth = obj(profile, "auth");
        JsonObject limits = obj(profile, "limits");
        JsonObject rate = obj(profile, "rateLimit");
        JsonObject comp = obj(profile, "compaction");
        Map<String, Map<String, String>> types = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> t : obj(profile, "schema").members().entrySet()) {
            Map<String, String> fields = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> f : ((JsonObject) t.getValue()).members().entrySet()) {
                fields.put(f.getKey(), ((JsonString) f.getValue()).value());
            }
            types.put(t.getKey(), fields);
        }
        Auth hs = Auth.hs256(((JsonString) auth.get("hs256Secret")).value());
        Auth a = auth.get("issuer") instanceof JsonString iss ? hs.issuer(iss.value()) : hs;
        return AccordServer.define(d -> d
                .schema(Schema.define(types))
                .scope("dossier", r -> {
                    List<String> keys = new ArrayList<>();
                    if (r.string("agent") != null) keys.add("agent:" + r.string("agent"));
                    if (r.string("zone") != null) keys.add("zone:" + r.string("zone"));
                    return keys;
                })
                .access(claims -> {
                    List<String> write = new ArrayList<>();
                    write.add("agent:" + ((JsonString) claims.get("sub")).value());
                    for (String z : strings(claims.get("zones"))) write.add("zone:" + z);
                    List<String> read = new ArrayList<>(write);
                    for (String z : strings(claims.get("readonly_zones"))) read.add("zone:" + z);
                    return Access.of(read, write);
                })
                .auth(a)
                .limits(Limits.DEFAULT
                        .withMaxPushOps((int) num(limits, "maxPushOps"))
                        .withMaxPullLimit((int) num(limits, "maxPullLimit"))
                        .withMaxScopeDelta((int) num(limits, "maxScopeDelta"))
                        .withMaxBodyBytes((int) num(limits, "maxBodyBytes"))
                        .withMaxSkewMs((long) num(limits, "maxSkewMs")))
                .rateLimit(bucket(obj(rate, "perDevice")), bucket(obj(rate, "perUser")))
                .compaction(new Compaction(num(comp, "deviceTtlDays"), (long) num(comp, "intervalMs"),
                        (int) num(comp, "minOps"))));
    }

    static RateLimit bucket(JsonObject o) {
        double perMinute = num(o, "perMinute");
        return o.get("burst") instanceof JsonNumber b ? RateLimit.of(perMinute, b.doubleValue()) : RateLimit.perMinute(perMinute);
    }

    // ------------------------------------------------------------------ the sync port

    static void serveSync(HttpExchange ex) throws IOException {
        try (ex) {
            byte[] body;
            try (InputStream in = ex.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, String> headers = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> {
                if (!v.isEmpty()) headers.put(k, v.get(0));
            });
            HttpResponse res = server.handle(new HttpRequest(ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                    ex.getRequestURI().getRawQuery(), headers, body));
            for (Map.Entry<String, String> h : res.headers()) ex.getResponseHeaders().add(h.getKey(), h.getValue());
            boolean empty = res.status() == 204 || ex.getRequestMethod().equalsIgnoreCase("HEAD");
            ex.sendResponseHeaders(res.status(), empty ? -1 : res.body().length);
            if (!empty) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(res.body());
                }
            }
        }
    }

    // ------------------------------------------------------------------ the control port

    static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** The record lock held by /hold-record: a connection of its own, in an open transaction. */
    static Connection held;
    static int heldPid;
    static final Object HOLD = new Object();

    static void serveControl(HttpExchange ex) throws IOException {
        try (ex) {
            int status = 200;
            JsonValue body;
            try {
                body = control(ex.getRequestMethod(), ex.getRequestURI().getPath(), query(ex.getRequestURI().getRawQuery()));
            } catch (HttpError e) {
                status = e.status;
                body = new JsonObject(Map.of("error", new JsonString(e.getMessage())));
            } catch (Exception e) {
                e.printStackTrace();
                status = 500;
                body = new JsonObject(Map.of("error", new JsonString(String.valueOf(e.getMessage()))));
            }
            byte[] bytes = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    static Map<String, List<String>> query(String rawQuery) {
        Map<String, List<String>> q = new LinkedHashMap<>();
        if (rawQuery == null) return q;
        for (String part : rawQuery.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = URLDecoder.decode(eq < 0 ? part : part.substring(0, eq), StandardCharsets.UTF_8);
            String v = eq < 0 ? "" : URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
            q.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return q;
    }

    static String first(Map<String, List<String>> q, String name) {
        List<String> v = q.get(name);
        return v == null ? null : v.get(0);
    }

    static JsonValue control(String method, String path, Map<String, List<String>> q) throws Exception {
        if (method.equals("GET") && path.equals("/token")) {
            String sub = first(q, "sub");
            if (sub == null || sub.isEmpty()) throw new HttpError(400, "sub is required");
            JsonObject auth = obj(profile, "auth");
            long now = System.currentTimeMillis() / 1000;
            String expIn = first(q, "exp_in");
            double exp = now + JsNumber.parse(expIn == null ? "3600" : expIn);
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
            if (q.containsKey("zone")) claims.claim("zones", q.get("zone"));
            if (q.containsKey("readonly_zone")) claims.claim("readonly_zones", q.get("readonly_zone"));
            claims.subject(sub).issueTime(new Date(now * 1000)).expirationTime(new Date((long) Math.floor(exp) * 1000));
            if (auth.get("issuer") instanceof JsonString iss) claims.issuer(iss.value());
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(((JsonString) auth.get("hs256Secret")).value().getBytes(StandardCharsets.UTF_8)));
            return new JsonObject(Map.of("token", new JsonString(jwt.serialize())));
        }
        if (method.equals("POST") && path.equals("/reset")) {
            release();
            try (Connection c = server.dataSource().getConnection(); Statement st = c.createStatement()) {
                st.execute("truncate feed, records, devices, compacted_ops restart identity");
            }
            server.resetRateLimits();
            return JsonObject.EMPTY;
        }
        if (method.equals("POST") && path.equals("/compact")) return server.compact();
        if (method.equals("POST") && path.equals("/age-device")) {
            String device = first(q, "device");
            String d = first(q, "days");
            double days = d == null ? Double.NaN : JsNumber.parse(d);
            if (device == null || device.isEmpty() || Double.isNaN(days) || Double.isInfinite(days)) {
                throw new HttpError(400, "device and days are required");
            }
            int n;
            try (Connection c = server.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(
                    "update devices set last_seen = now() - make_interval(days => ?) where device_id = ?")) {
                ps.setInt(1, (int) days);
                ps.setString(2, device);
                n = ps.executeUpdate();
            }
            if (n == 0) throw new HttpError(404, "unknown device");
            return JsonObject.EMPTY;
        }
        if (method.equals("POST") && path.equals("/hold-record")) {
            String record = first(q, "record");
            if (record == null || record.isEmpty()) throw new HttpError(400, "record is required");
            synchronized (HOLD) {
                if (held != null) throw new HttpError(409, "a record is already held");
                Connection c = raw.getConnection();
                try {
                    c.setAutoCommit(false);
                    try (PreparedStatement ps = c.prepareStatement("select record from records where record = ? for update")) {
                        ps.setString(1, record);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) throw new HttpError(404, "unknown record");
                        }
                    }
                    try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("select pg_backend_pid()")) {
                        rs.next();
                        heldPid = rs.getInt(1);
                    }
                    held = c;
                } catch (RuntimeException | SQLException e) {
                    c.rollback();
                    c.close();
                    throw e;
                }
            }
            return JsonObject.EMPTY;
        }
        if (method.equals("GET") && path.equals("/held")) {
            int pid;
            synchronized (HOLD) {
                if (held == null) return new JsonObject(Map.of("waiting", JsonNumber.of(0)));
                pid = heldPid;
            }
            try (Connection c = server.dataSource().getConnection(); PreparedStatement ps = c.prepareStatement(
                    "select count(*) from pg_stat_activity where ?::int = any(pg_blocking_pids(pid))")) {
                ps.setInt(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new JsonObject(Map.of("waiting", JsonNumber.of(rs.getLong(1))));
                }
            }
        }
        if (method.equals("POST") && path.equals("/release")) {
            release();
            return JsonObject.EMPTY;
        }
        throw new HttpError(404, "not found");
    }

    static void release() throws SQLException {
        synchronized (HOLD) {
            if (held == null) return;
            try {
                held.rollback();
            } finally {
                held.close();
                held = null;
            }
        }
    }

    // ------------------------------------------------------------------ a tiny connection pool

    /** At most {@code size} connections, reused; close() returns a connection (autocommit restored). */
    static final class TinyPool implements DataSource {
        private final PGSimpleDataSource source;
        private final BlockingQueue<Connection> idle;
        private final Semaphore permits;

        TinyPool(PGSimpleDataSource source, int size) {
            this.source = source;
            this.idle = new ArrayBlockingQueue<>(size);
            this.permits = new Semaphore(size, true);
        }

        @Override
        public Connection getConnection() throws SQLException {
            try {
                permits.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SQLException("interrupted", e);
            }
            Connection real = idle.poll();
            try {
                if (real == null) real = source.getConnection();
            } catch (SQLException e) {
                permits.release();
                throw e;
            }
            Connection c = real;
            boolean[] closed = {false};
            InvocationHandler h = (proxy, m, args) -> {
                if (m.getName().equals("close")) {
                    if (closed[0]) return null;
                    closed[0] = true;
                    boolean reusable;
                    try {
                        if (!c.getAutoCommit()) c.rollback();
                        c.setAutoCommit(true);
                        reusable = !c.isClosed();
                    } catch (SQLException e) {
                        reusable = false;
                    }
                    if (reusable) idle.offer(c);
                    else c.close();
                    permits.release();
                    return null;
                }
                if (m.getName().equals("isClosed")) return closed[0] || c.isClosed();
                try {
                    return m.invoke(c, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, h);
        }

        @Override
        public Connection getConnection(String user, String password) throws SQLException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            throw new SQLException("not a wrapper");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
