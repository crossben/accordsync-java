package io.github.crossben.accordsync.server;

import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.Hlc;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.Protocol;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * The Accord sync server: push, pull and health over a {@link DataSource}, with auth, limits, CORS
 * and rate limits, framework-agnostic. Adapters translate their request into an {@link HttpRequest}
 * and the returned {@link HttpResponse} back. Thread-safe.
 *
 * <pre>{@code
 * ServerDefinition def = AccordServer.define(d -> d
 *     .schema(schema)
 *     .scope("dossier", r -> r.string("agent") == null ? List.of() : List.of("agent:" + r.string("agent")))
 *     .access(claims -> Access.readWrite("agent:" + ((JsonString) claims.get("sub")).value()))
 *     .auth(Auth.jwks("https://auth.example.com/.well-known/jwks.json")));
 * Migrations.migrate(dataSource);
 * AccordServer server = new AccordServer(def, dataSource);
 * HttpResponse res = server.handle(request);
 * }</pre>
 */
public final class AccordServer {
    /** The protocol version this module speaks. */
    public static final int PROTOCOL_VERSION = Protocol.VERSION;

    private static final System.Logger LOG = System.getLogger("io.github.crossben.accordsync.server");

    private final ServerDefinition def;
    private final DataSource db;
    private final Sync sync;
    private final TokenVerifier verifier;
    private final RateLimiter deviceLimiter;
    private final RateLimiter userLimiter;

    /**
     * Declares a server definition.
     *
     * @param configure fills the builder
     * @return the checked definition
     * @throws IllegalArgumentException when the definition is invalid
     */
    public static ServerDefinition define(Consumer<ServerDefinition.Builder> configure) {
        ServerDefinition.Builder b = new ServerDefinition.Builder();
        configure.accept(b);
        return b.build();
    }

    /**
     * A server on the wall clock.
     *
     * @param def the definition
     * @param db the data source (a pool such as HikariCP)
     */
    public AccordServer(ServerDefinition def, DataSource db) {
        this(def, db, System::currentTimeMillis);
    }

    /**
     * A server with its own clock (tests).
     *
     * @param def the definition
     * @param db the data source
     * @param now physical time in milliseconds
     */
    public AccordServer(ServerDefinition def, DataSource db, LongSupplier now) {
        this.def = Objects.requireNonNull(def, "def");
        this.db = Objects.requireNonNull(db, "db");
        this.sync = new Sync(db, def, now);
        this.verifier = new TokenVerifier(def.auth());
        if (def.rateLimitPerDevice() != null) {
            this.deviceLimiter = new TokenBucketRateLimiter(def.rateLimitPerDevice(), now, 100_000);
            this.userLimiter = new TokenBucketRateLimiter(def.rateLimitPerUser(), now, 100_000);
        } else {
            this.deviceLimiter = null;
            this.userLimiter = null;
        }
    }

    /** @return the definition */
    public ServerDefinition definition() {
        return def;
    }

    /** @return the data source */
    public DataSource dataSource() {
        return db;
    }

    /** Starts every rate-limit bucket full again (the conformance control API's reset). */
    public void resetRateLimits() {
        if (deviceLimiter != null) {
            deviceLimiter.clear();
            userLimiter.clear();
        }
    }

    /**
     * Folds the history of records every live device already has into one snapshot per record
     * (ADR-0005, ADR-0008, ADR-0010).
     *
     * @return {@code {watermark, records, opsFolded, tombstonesPruned}}
     * @throws SQLException on a database error
     */
    public JsonObject compact() throws SQLException {
        return sync.compact();
    }

    /**
     * Runs pending migrations on this server's database.
     *
     * @return the names run
     * @throws SQLException on a database error
     */
    public List<String> migrate() throws SQLException {
        return Migrations.migrate(db);
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * Handles one HTTP request.
     *
     * @param req the request
     * @return the response; never throws
     */
    public HttpResponse handle(HttpRequest req) {
        String method = req.method();
        Cors cors = cors(method, req);
        Res res;
        if (cors != null && cors.preflight) {
            res = new Res(204, new ArrayList<>(cors.headers), new byte[0]);
        } else {
            try {
                res = route(req);
            } catch (AccordHttpException e) {
                res = error(e.status(), e.getMessage());
                if (e.status() == 429) res.headers.add(Map.entry("Retry-After", String.valueOf((long) Math.ceil(e.retryAfterMs() / 1000.0))));
            } catch (Exception | StackOverflowError e) {
                LOG.log(System.Logger.Level.ERROR, "accord: internal error", e);
                res = error(500, "internal error");
            }
            if (cors != null) res.headers.addAll(cors.headers);
        }
        res.headers.add(Map.entry("Accord-Protocol", String.valueOf(PROTOCOL_VERSION)));
        return new HttpResponse(res.status, res.headers, res.body);
    }

    private record Res(int status, List<Map.Entry<String, String>> headers, byte[] body) {}

    private record Cors(boolean preflight, List<Map.Entry<String, String>> headers) {}

    private static Res json(int status, JsonValue body) {
        List<Map.Entry<String, String>> h = new ArrayList<>();
        h.add(Map.entry("Content-Type", "application/json"));
        return new Res(status, h, Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    }

    private static Res error(int status, String message) {
        return json(status, new JsonObject(Map.of("error", new JsonString(message))));
    }

    private Cors cors(String method, HttpRequest req) {
        List<String> origins = def.cors();
        if (origins.isEmpty()) return null;
        String origin = req.header("origin");
        List<Map.Entry<String, String>> out = new ArrayList<>();
        if (origin != null && origins.contains(origin)) out.add(Map.entry("Access-Control-Allow-Origin", origin));
        out.add(Map.entry("Vary", "Origin"));
        if (method.equals("OPTIONS")) {
            out.add(Map.entry("Access-Control-Max-Age", "600"));
            out.add(Map.entry("Access-Control-Allow-Methods", "GET,HEAD,PUT,POST,DELETE,PATCH"));
            out.add(Map.entry("Access-Control-Allow-Headers", "Authorization,Accord-Device,Content-Type"));
            return new Cors(true, out);
        }
        out.add(Map.entry("Access-Control-Expose-Headers", "Accord-Protocol"));
        return new Cors(false, out);
    }

    private Res route(HttpRequest req) throws SQLException, InterruptedException {
        String method = req.method();
        String path = req.path();
        boolean get = method.equals("GET") || method.equals("HEAD");
        if (path.equals("/health") && get) return health();
        if (path.startsWith("/v1/")) {
            int limit = def.limits().maxBodyBytes();
            String cl = req.header("content-length");
            Long length = JsNumber.safeInteger(cl == null ? "0" : cl);
            if (req.body().length > limit || (length != null && length > limit)) {
                return error(413, "request body too large");
            }
            if (path.equals("/v1/push") && method.equals("POST")) return push(req);
            if (path.equals("/v1/pull") && get) return pull(req);
        }
        return error(404, "not found");
    }

    // ------------------------------------------------------------------ routes

    private Res health() {
        try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
            st.execute("select 1");
        } catch (SQLException | RuntimeException e) {
            Map<String, JsonValue> m = new LinkedHashMap<>();
            m.put("status", new JsonString("unavailable"));
            m.put("reason", new JsonString("database unreachable"));
            return json(503, new JsonObject(m));
        }
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("status", new JsonString("ok"));
        m.put("protocolVersion", JsonNumber.of(PROTOCOL_VERSION));
        return json(200, new JsonObject(m));
    }

    private Sync.Caller caller(HttpRequest req) throws SQLException {
        JsonObject claims = verifier.verify(req.header("authorization"));
        String sub = ((JsonString) claims.get("sub")).value();
        String deviceId = req.header("accord-device");
        if (deviceId == null) deviceId = "";
        try {
            Hlc.assertNode(deviceId);
        } catch (AccordException e) {
            throw AccordHttpException.badRequest("Accord-Device header must be a device id ([A-Za-z0-9_-]{1,64})");
        }
        if (deviceLimiter != null) {
            long wait = Math.max(deviceLimiter.take(deviceId), userLimiter.take(sub));
            if (wait > 0) throw AccordHttpException.tooManyRequests(wait);
        }
        Access access = def.access().apply(claims);
        Sync.Caller who = new Sync.Caller(sub, deviceId, access.read(), access.write());
        sync.touchDevice(who);
        return who;
    }

    private Res push(HttpRequest req) throws SQLException, InterruptedException {
        Sync.Caller who = caller(req);
        JsonValue body;
        try {
            String text = new String(req.body(), StandardCharsets.UTF_8);
            if (text.startsWith("﻿")) text = text.substring(1);
            body = Json.parse(text);
        } catch (AccordException | StackOverflowError e) {
            throw AccordHttpException.badRequest("body must be JSON");
        }
        if (!(body instanceof JsonObject o) || o.members().size() != 1 || !(o.get("ops") instanceof JsonArray ops)) {
            throw AccordHttpException.badRequest("body must be { \"ops\": [...] }");
        }
        return json(200, sync.push(who, ops.items()));
    }

    private Res pull(HttpRequest req) throws SQLException {
        Sync.Caller who = caller(req);
        Map<String, String> q = query(req.rawQuery());
        Long cursor = JsNumber.safeInteger(q.getOrDefault("cursor", "0"));
        Long limit = JsNumber.safeInteger(q.getOrDefault("limit", "500"));
        if (cursor == null || cursor < 0) throw AccordHttpException.badRequest("cursor must be an integer ≥ 0");
        if (limit == null || limit < 1) throw AccordHttpException.badRequest("limit must be an integer ≥ 1");
        return json(200, sync.pull(who, cursor, limit));
    }

    /** The first value of each query parameter, decoded like a URL's search params. */
    static Map<String, String> query(String raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = decode(eq < 0 ? part : part.substring(0, eq));
            String v = eq < 0 ? "" : decode(part.substring(eq + 1));
            out.putIfAbsent(k, v);
        }
        return out;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (IllegalArgumentException | UnsupportedEncodingException e) {
            return s;
        }
    }
}
