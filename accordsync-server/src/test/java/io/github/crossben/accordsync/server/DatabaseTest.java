package io.github.crossben.accordsync.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.Schema;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * Against a real PostgreSQL: set ACCORD_TEST_DATABASE_URL (a database this test may create other
 * databases from). Skipped without it.
 */
class DatabaseTest {
    static final String ADMIN = System.getenv("ACCORD_TEST_DATABASE_URL");
    static final String SECRET = "0123456789abcdef0123456789abcdef";
    static String name;
    static PGSimpleDataSource db;

    @BeforeAll
    static void createDatabase() throws SQLException {
        if (ADMIN == null || ADMIN.isEmpty()) return;
        name = "accord_jtest_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = Databases.fromUrl(ADMIN).getConnection(); Statement st = c.createStatement()) {
            st.execute("create database " + name);
        }
        db = Databases.fromUrl(ADMIN);
        String url = Databases.jdbcUrl(ADMIN);
        int q = url.indexOf('?');
        String base = q < 0 ? url : url.substring(0, q);
        db.setURL(base.substring(0, base.lastIndexOf('/') + 1) + name + (q < 0 ? "" : url.substring(q)));
    }

    @AfterAll
    static void dropDatabase() throws SQLException {
        if (name == null) return;
        try (Connection c = Databases.fromUrl(ADMIN).getConnection(); Statement st = c.createStatement()) {
            st.execute("drop database if exists " + name + " with (force)");
        }
    }

    static void requireDb() {
        assumeTrue(db != null, "ACCORD_TEST_DATABASE_URL is not set");
    }

    static List<String> col(String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = db.getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Test
    void migrationsUseTheKyselyLedgerAndAreIdempotent() throws SQLException {
        requireDb();
        synchronized (DatabaseTest.class) {
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute("create schema if not exists ledger_test");
            }
            Migrations.migrate(db);
            assertThat(Migrations.migrate(db)).isEmpty();
            assertThat(col("select name from kysely_migration order by name")).isEqualTo(Migrations.names());
            assertThat(col("select timestamp from kysely_migration"))
                    .allMatch(t -> t.matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));
            assertThat(col("select id || ':' || is_locked from kysely_migration_lock")).containsExactly("migration_lock:0");
            // The migration lock is released (Kysely's session advisory lock).
            assertThat(col("select count(*) from pg_locks where locktype = 'advisory' and objid = "
                    + (Migrations.KYSELY_LOCK_ID & 0xffffffffL))).containsExactly("0");
            // The feed is append-only.
            assertThatThrownBy(() -> {
                try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                    st.execute("insert into feed (kind, record, op_id, op, scopes) values ('op', 'x:1', 'd:1', '{}', '{}')");
                    st.execute("update feed set record = 'x:2'");
                }
            }).hasMessageContaining("append-only");
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute("begin; set local accord.compaction = 'on'; delete from feed; commit");
            }
        }
    }

    @Test
    void refusesALedgerItDoesNotKnow() throws SQLException {
        requireDb();
        synchronized (DatabaseTest.class) {
            Migrations.migrate(db);
            try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                st.execute("insert into kysely_migration values ('0008_from_the_future', '2099-01-01T00:00:00.000Z')");
            }
            try {
                assertThatThrownBy(() -> Migrations.migrate(db))
                        .hasMessageContaining("previously executed migration 0008_from_the_future is missing");
            } finally {
                try (Connection c = db.getConnection(); Statement st = c.createStatement()) {
                    st.execute("delete from kysely_migration where name = '0008_from_the_future'");
                }
            }
        }
    }

    // ------------------------------------------------------------------ push, pull, compaction

    static AccordServer server() {
        Schema schema = Schema.define(Map.of("dossier", Map.of("agent", "lww", "visits", "counter")));
        return new AccordServer(AccordServer.define(d -> d.schema(schema)
                .scope("dossier", r -> r.string("agent") == null ? List.of() : List.of("agent:" + r.string("agent")))
                .access(c -> Access.readWrite("agent:" + Json.stringify(c.get("sub")).replace("\"", "")))
                .auth(Auth.hs256(SECRET)).noRateLimit()
                .compaction(new Compaction(30, 0, 2))), db);
    }

    static String token(String sub) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder().subject(sub).build());
        jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    static JsonObject call(AccordServer s, String method, String path, String query, String sub, String device, String body)
            throws Exception {
        HttpResponse r = s.handle(new HttpRequest(method, path, query,
                Map.of("Authorization", "Bearer " + token(sub), "Accord-Device", device),
                body.getBytes(StandardCharsets.UTF_8)));
        assertThat(r.status()).as(r.bodyText()).isEqualTo(200);
        return (JsonObject) Json.parse(r.bodyText());
    }

    static String op(String device, int seq, String record, String field, String kind, String value) {
        String hlc = (System.currentTimeMillis()) + ":" + String.format("%05d", seq % 99_999) + ":" + device;
        String payload = kind.equals("inc") ? "\"by\":" + value : "\"value\":" + value + ",\"deps\":[]";
        return "{\"op_id\":\"" + device + ":" + seq + "\",\"record\":\"" + record + "\",\"field\":\"" + field
                + "\",\"hlc\":\"" + hlc + "\",\"kind\":\"" + kind + "\"," + payload + "}";
    }

    static Set<String> drain(AccordServer s, String sub, String device) throws Exception {
        Set<String> seen = new HashSet<>();
        long cursor = 0;
        for (int page = 0; page < 1000; page++) {
            JsonObject res = call(s, "GET", "/v1/pull", "cursor=" + cursor + "&limit=7", sub, device, "");
            for (JsonValue item : ((JsonArray) res.get("items")).items()) {
                if (((JsonObject) item).get("op") instanceof JsonObject op) seen.add(Json.stringify(op.get("op_id")));
            }
            cursor = (long) ((JsonNumber) res.get("cursor")).doubleValue();
            if (res.get("has_more").equals(io.github.crossben.accordsync.core.JsonBool.of(false))) break;
        }
        // One more pull from the final cursor tells the server this device has everything.
        call(s, "GET", "/v1/pull", "cursor=" + cursor, sub, device, "");
        return seen;
    }

    @Test
    void concurrentPushesAreAllPulledAndCompactionKeepsState() throws Exception {
        requireDb();
        Migrations.migrate(db);
        AccordServer s = server();
        String sub = "u" + UUID.randomUUID().toString().substring(0, 8);
        int devices = 6;
        int perDevice = 10;
        ExecutorService pool = Executors.newFixedThreadPool(devices);
        List<Future<?>> futures = new ArrayList<>();
        for (int d = 0; d < devices; d++) {
            String dev = sub + "d" + d;
            futures.add(pool.submit(() -> {
                for (int i = 1; i <= perDevice; i++) {
                    String rec = "dossier:" + sub + (i % 3);
                    String ops = "[" + op(dev, 2 * i - 1, rec, "agent", "assign", "\"" + sub + "\"") + ","
                            + op(dev, 2 * i, rec, "visits", "inc", "1") + "]";
                    JsonObject res = call(s, "POST", "/v1/push", "", sub, dev, "{\"ops\":" + ops + "}");
                    assertThat(((JsonArray) res.get("acked")).items()).hasSize(2);
                }
                return null;
            }));
        }
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        Set<String> seen = drain(s, sub, sub + "reader");
        assertThat(seen).hasSize(devices * perDevice * 2);

        // Compaction waits for every live device: each writer catches up first.
        for (int d = 0; d < devices; d++) assertThat(drain(s, sub, sub + "d" + d)).hasSize(devices * perDevice * 2);
        JsonObject result = s.compact();
        assertThat(((JsonNumber) result.get("records")).doubleValue()).isEqualTo(3);
        assertThat(((JsonNumber) result.get("opsFolded")).doubleValue()).isEqualTo(devices * perDevice * 2);
        // Folded ids are kept (with their hash) only at or above each device's push floor: each device's
        // last push started at op 2 * perDevice - 1, so two ids per device remain.
        assertThat(col("select count(*) from compacted_ops where op_hash is not null and op_id like '" + sub + "%'"))
                .containsExactly(String.valueOf(devices * 2));
        // A fresh device gets snapshots with the folded state.
        JsonObject fresh = call(s, "GET", "/v1/pull", "cursor=0", sub, sub + "fresh", "");
        long visits = 0;
        for (JsonValue item : ((JsonArray) fresh.get("items")).items()) {
            JsonObject snap = (JsonObject) ((JsonObject) item).get("snapshot");
            JsonObject fields = (JsonObject) snap.get("fields");
            visits += (long) ((JsonNumber) ((JsonObject) fields.get("visits")).get("total")).doubleValue();
        }
        assertThat(visits).isEqualTo(devices * perDevice);
        // An old op id sent again with other content is refused, never applied twice.
        String dev = sub + "d0";
        JsonObject again = call(s, "POST", "/v1/push", "", sub, dev,
                "{\"ops\":[" + op(dev, 2 * perDevice, "dossier:" + sub + "1", "visits", "inc", "5") + "]}");
        assertThat(Json.stringify(again.get("refused"))).contains("op id already used");
    }
}
