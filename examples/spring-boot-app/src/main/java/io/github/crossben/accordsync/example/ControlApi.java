package io.github.crossben.accordsync.example;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.JsNumber;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * The conformance suite's control API (app/conformance/PROFILE.md): {@code GET /token},
 * {@code POST /reset} (also clears rate limits and releases a held record), {@code /compact},
 * {@code /age-device}, {@code /hold-record}, {@code GET /held}, {@code /release}. TEST ONLY: it mints
 * tokens and wipes the database. It runs on a port of its own (a JDK HttpServer, 127.0.0.1 only)
 * and exists only when {@code accord.example.control.enabled=true}.
 */
@Component
@ConditionalOnProperty(prefix = "accord.example.control", name = "enabled", havingValue = "true")
public class ControlApi implements SmartLifecycle {
    private final AccordServer server;
    private final ConformanceProfile profile;
    private final int port;
    private HttpServer http;
    private ExecutorService executor;

    /** The record lock held by /hold-record: a connection of its own, in an open transaction. */
    private Connection held;
    private int heldPid;
    private final Object hold = new Object();

    /**
     * The control API.
     *
     * @param server the Accord server (from the starter)
     * @param profile the profile.json path
     * @param port the port
     * @throws IOException when the profile cannot be read
     */
    public ControlApi(AccordServer server, @Value("${accord.example.profile}") String profile,
            @Value("${accord.example.control.port}") int port) throws IOException {
        this.server = server;
        this.profile = ConformanceProfile.load(Path.of(profile));
        this.port = port;
    }

    @Override
    public synchronized void start() {
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 128);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        executor = Executors.newCachedThreadPool();
        http.setExecutor(executor);
        http.createContext("/", this::serve);
        http.start();
    }

    @Override
    public synchronized void stop() {
        if (http != null) {
            http.stop(0);
            executor.shutdownNow();
            http = null;
        }
        try {
            release();
        } catch (SQLException e) {
            // closing anyway
        }
    }

    @Override
    public synchronized boolean isRunning() {
        return http != null;
    }

    private static final class HttpError extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        HttpError(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    private void serve(HttpExchange ex) throws IOException {
        try (ex) {
            int status = 200;
            JsonValue body;
            try {
                body = control(ex.getRequestMethod(), ex.getRequestURI().getPath(), query(ex.getRequestURI().getRawQuery()));
            } catch (HttpError e) {
                status = e.status;
                body = new JsonObject(Map.of("error", new JsonString(e.getMessage())));
            } catch (Exception e) {
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

    private static Map<String, List<String>> query(String rawQuery) {
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

    private static String first(Map<String, List<String>> q, String name) {
        List<String> v = q.get(name);
        return v == null ? null : v.get(0);
    }

    JsonValue control(String method, String path, Map<String, List<String>> q) throws Exception {
        if (method.equals("GET") && path.equals("/token")) {
            String sub = first(q, "sub");
            if (sub == null || sub.isEmpty()) throw new HttpError(400, "sub is required");
            long now = System.currentTimeMillis() / 1000;
            String expIn = first(q, "exp_in");
            double exp = now + JsNumber.parse(expIn == null ? "3600" : expIn);
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder();
            if (q.containsKey("zone")) claims.claim("zones", q.get("zone"));
            if (q.containsKey("readonly_zone")) claims.claim("readonly_zones", q.get("readonly_zone"));
            claims.subject(sub).issueTime(new Date(now * 1000)).expirationTime(new Date((long) Math.floor(exp) * 1000));
            if (profile.issuer() != null) claims.issuer(profile.issuer());
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
            jwt.sign(new MACSigner(profile.secret().getBytes(StandardCharsets.UTF_8)));
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
            synchronized (hold) {
                if (held != null) throw new HttpError(409, "a record is already held");
                Connection c = server.dataSource().getConnection();
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
            synchronized (hold) {
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

    private void release() throws SQLException {
        synchronized (hold) {
            if (held == null) return;
            try {
                held.rollback();
            } finally {
                held.close();
                held = null;
            }
        }
    }
}
