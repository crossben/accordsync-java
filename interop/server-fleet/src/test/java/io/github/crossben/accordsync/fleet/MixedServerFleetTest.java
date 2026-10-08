package io.github.crossben.accordsync.fleet;

import static io.github.crossben.accordsync.fleet.Fleet.ADMIN_URL;
import static io.github.crossben.accordsync.fleet.Fleet.HTTP;
import static io.github.crossben.accordsync.fleet.Fleet.LOG_DIR;
import static io.github.crossben.accordsync.fleet.Fleet.NAMES;
import static io.github.crossben.accordsync.fleet.Fleet.SCHEMA;
import static io.github.crossben.accordsync.fleet.Fleet.SERVERS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonBool;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.LocalWriter;
import io.github.crossben.accordsync.core.Wire;
import io.github.crossben.accordsync.fleet.Fleet.Device;
import io.github.crossben.accordsync.fleet.Fleet.JavaDevice;
import io.github.crossben.accordsync.fleet.Fleet.Served;
import io.github.crossben.accordsync.fleet.Fleet.Servers;
import io.github.crossben.accordsync.fleet.Fleet.TsDevice;
import io.github.crossben.accordsync.fleet.Fleet.Truth;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The mixed-server fleet (plan-java.md J5; a port of python/server-interop): one PostgreSQL
 * database, the TypeScript reference server and the Java server running on it at once. Two Java and
 * two TypeScript devices send every request to a server picked at random, through a network that
 * loses requests and responses; seeded random edits with awkward values; a compaction and a scope
 * change mid-run. After the network heals, every device must hold byte-identical canonical
 * snapshots, equal to what the database holds, and both servers must have served pushes and pulls.
 */
@SuppressWarnings("try") // try (Servers s = ...): the servers live for the block
class MixedServerFleetTest {
    static final double LOSS = 0.25;
    static final int ROUNDS = 160;
    static final int COMPACT_AT = 60;
    static final int SCOPE_AT = 110;
    static final List<String> SHARED = List.of("dossier:1", "dossier:2", "dossier:é");
    static final String THIES = "dossier:4"; // created by awa in zone thies; moussa gets thies mid-run
    static final int MIGRATIONS = 7;

    static final List<Object> AWKWARD = Arrays.asList(null, 2.5, 1e21, "é", "", "😀", "s0", 0, -1);
    static final List<Object> ELEMENTS = List.of("doc-0", "doc-1", 0, 1, 2.5, "", "😀", "é");

    @BeforeAll
    static void database() {
        if (ADMIN_URL.isEmpty()) throw new IllegalStateException("ACCORD_DATABASE_URL is not set: run interop/server-fleet/run.sh");
    }

    static Stream<Integer> seeds() {
        return Arrays.stream(Fleet.env("ACCORD_FLEET_SEEDS", "1,2,3").split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).map(Integer::parseInt);
    }

    static long now() {
        return System.currentTimeMillis();
    }

    /** Migrates with one implementation (even seeds TypeScript, odd Java); the other must find nothing to do. */
    static String migrateOnce(String url, int seed) throws Exception {
        String first = seed % 2 == 0 ? "ts" : "java";
        if (first.equals("ts")) Fleet.migrateTs(url);
        else assertEquals(MIGRATIONS, Fleet.migrateJava(url).size());
        List<String> done = Fleet.ledger(url);
        assertEquals(MIGRATIONS, done.size(), done.toString());
        if (first.equals("ts")) assertEquals(List.of(), Fleet.migrateJava(url));
        else Fleet.migrateTs(url);
        assertEquals(done, Fleet.ledger(url), "the second implementation migrated again");
        return first;
    }

    record Action(String cmd, String field, Object value) {}

    static Action action(Random rng, String record) {
        int k = rng.nextInt(10);
        switch (k) {
            case 0:
                return new Action("inc", "visits", rng.nextInt(9) - 3);
            case 1:
            case 2:
                return new Action(k == 1 ? "add" : "remove", "docs", ELEMENTS.get(rng.nextInt(ELEMENTS.size())));
            case 3:
                return new Action("assign", "status", AWKWARD.get(rng.nextInt(AWKWARD.size())));
            case 4: {
                int n = rng.nextInt(9);
                Map<String, Object> obj = new LinkedHashMap<>();
                obj.put("10", true);
                obj.put("a", 1e21);
                obj.put("n", n);
                List<Object> values = Arrays.asList(obj, "é", "", "😀", null, 2.5);
                return new Action("assign", "client_name", values.get(rng.nextInt(values.size())));
            }
            case 5: {
                List<Object> agents = Arrays.asList("awa", "moussa", null);
                return new Action("assign", "agent", agents.get(rng.nextInt(3)));
            }
            case 6:
                if (record.equals(THIES)) return new Action("assign", "zone", rng.nextBoolean() ? "thies" : "dakar");
                return new Action("sync", "", null);
            default:
                return new Action("sync", "", null);
        }
    }

    // ------------------------------------------------------------------ raw HTTP

    static JsonObject http(String server, String method, String path, String token, String device, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(SERVERS.get(server) + path)).timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + token).header("Accord-Device", device);
        if (body != null) b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> res = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), server + " " + path + ": " + res.statusCode() + " " + res.body());
        return (JsonObject) Json.parse(res.body());
    }

    static JsonObject push(String server, String token, String device, List<JsonObject> ops) throws Exception {
        return http(server, "POST", "/v1/push", token, device,
                Json.stringify(new JsonObject(Map.of("ops", new JsonArray(List.<JsonValue>copyOf(ops))))));
    }

    static JsonObject pull(String server, String token, String device, long cursor) throws Exception {
        return http(server, "GET", "/v1/pull?cursor=" + cursor + "&limit=10", token, device, null);
    }

    static long cursorOf(JsonObject page) {
        return ((JsonNumber) page.get("cursor")).longValue();
    }

    static boolean hasMore(JsonObject page) {
        return page.get("has_more") instanceof JsonBool b && b.value();
    }

    static List<JsonValue> items(JsonObject page) {
        return ((JsonArray) page.get("items")).items();
    }

    /** Pulls to the end, then once more at the end cursor (the server records cursors from requests). */
    static long pullAll(String server, String token, String device, long cursor) throws Exception {
        while (true) {
            JsonObject page = pull(server, token, device, cursor);
            assertFalse(page.has("resync_required"), page.toString());
            cursor = cursorOf(page);
            if (!hasMore(page)) {
                JsonObject last = pull(server, token, device, cursor);
                assertEquals(List.of(), items(last), Json.stringify(last));
                return cursor;
            }
        }
    }

    record Pages(List<JsonValue> items, long cursor) {}

    static Pages pages(String server, String token, String device, long cursor) throws Exception {
        List<JsonValue> all = new ArrayList<>();
        while (true) {
            JsonObject page = pull(server, token, device, cursor);
            assertFalse(page.has("resync_required"), page.toString());
            all.addAll(items(page));
            cursor = cursorOf(page);
            if (!hasMore(page)) return new Pages(all, cursor);
        }
    }

    static String str(JsonValue v, String key) {
        return v instanceof JsonObject o && o.get(key) instanceof JsonString s ? s.value() : null;
    }

    /** Whether items carry the record's history: a snapshot, or more than the one op that moved it. */
    static boolean hasHistory(List<JsonValue> items, String record) {
        int ops = 0;
        boolean snapshot = false;
        for (JsonValue i : items) {
            JsonObject o = (JsonObject) i;
            String type = str(o, "type");
            if ("op".equals(type) && record.equals(str(o.get("op"), "record"))) ops++;
            if ("snapshot".equals(type) && record.equals(str(o.get("snapshot"), "record"))) snapshot = true;
        }
        return ops > 1 || snapshot;
    }

    static boolean hasExit(List<JsonValue> items, String record) {
        for (JsonValue i : items) if ("exit".equals(str(i, "type")) && record.equals(str(i, "record"))) return true;
        return false;
    }

    static List<JsonObject> ops(Object... ops) {
        List<JsonObject> out = new ArrayList<>();
        for (Object o : ops) out.add(Wire.encode((io.github.crossben.accordsync.core.Op) o));
        return out;
    }

    static int size(JsonValue array) {
        return ((JsonArray) array).items().size();
    }

    // ------------------------------------------------------------------ the fleet

    @ParameterizedTest(name = "seed {0}")
    @MethodSource("seeds")
    void mixedServerFleet(int seed) throws Exception {
        Random rng = new Random(seed);
        String url = Fleet.recreateDatabase();
        String migratedBy = migrateOnce(url, seed);
        Served served = new Served();
        try (Servers s = new Servers(url, "seed" + seed)) {
            fleet(seed, rng, url, served, migratedBy);
        }
    }

    private void fleet(int seed, Random rng, String url, Served served, String migratedBy) throws Exception {
        Supplier<String> server = () -> NAMES.get(rng.nextInt(NAMES.size()));
        // A token from either control API works on both servers (same secret and issuer).
        String awa = Fleet.tokenFor("ts", "awa", List.of("dakar", "thies"));
        String moussa0 = Fleet.tokenFor("java", "moussa", List.of("dakar"));

        List<JavaDevice> java = new ArrayList<>();
        List<TsDevice> ts = new ArrayList<>();
        try {
            java.add(new JavaDevice("java-awa", awa, new Random(seed * 31L + 1), served));
            java.add(new JavaDevice("java-moussa", moussa0, new Random(seed * 31L + 2), served));
            ts.add(new TsDevice("ts-awa", awa, seed * 7L + 1));
            ts.add(new TsDevice("ts-moussa", moussa0, seed * 7L + 2));
            List<Device> devices = List.of(java.get(0), ts.get(0), java.get(1), ts.get(1));

            Runnable settle = () -> {
                for (Device d : devices) d.setLoss(0);
                for (int i = 0; i < 3; i++) for (Device d : devices) d.sync(false);
            };
            Runnable lossy = () -> {
                for (Device d : devices) d.setLoss(LOSS);
            };

            // Every shared record starts in zone dakar, written by both kinds of device; awa's
            // TypeScript device creates the 4th in zone thies, which moussa cannot see yet.
            java.get(0).edit("assign", SHARED.get(0), "zone", "dakar");
            ts.get(0).edit("assign", SHARED.get(1), "zone", "dakar");
            java.get(1).edit("assign", SHARED.get(2), "zone", "dakar");
            ts.get(0).edit("assign", THIES, "zone", "thies");
            settle.run();
            lossy.run();

            Map<String, Object> compacted = new LinkedHashMap<>();
            ExecutorService pool = Executors.newFixedThreadPool(devices.size());
            try {
                for (int step = 0; step < ROUNDS; step++) {
                    List<Future<?>> futures = new ArrayList<>();
                    List<Runnable> plan = new ArrayList<>();
                    for (Device d : devices) {
                        List<String> all = new ArrayList<>(SHARED);
                        all.add(THIES);
                        String record = all.get(rng.nextInt(all.size()));
                        boolean thenSync = rng.nextDouble() < 0.8;
                        Action a = action(rng, record);
                        boolean isAwa = d.id().endsWith("awa");
                        plan.add(() -> {
                            // Moussa edits the thies record only while he holds it (a racing move can
                            // still get the op refused: the client must cope).
                            if (!a.cmd().equals("sync") && (isAwa || !record.equals(THIES) || d.has(THIES))) {
                                d.edit(a.cmd(), record, a.field(), a.value());
                                if (!thenSync) return;
                            }
                            d.sync(true);
                        });
                    }
                    // The four devices act at once: pushes and pulls overlap on both servers.
                    for (Runnable r : plan) futures.add(pool.submit(r));
                    for (Future<?> f : futures) f.get();

                    if (step == COMPACT_AT) {
                        compacted = compactMidRun(server, url, awa, settle);
                        lossy.run();
                    }
                    if (step == SCOPE_AT) {
                        settle.run();
                        String moussa = scopeChange(server, awa);
                        // The first pull with the new token may be lost: the scope delta stays
                        // pending until it is received (ADR-0011, 2026-10-07).
                        for (Device d : devices) if (!d.id().endsWith("awa")) d.setToken(moussa);
                        lossy.run();
                    }
                }
            } finally {
                pool.shutdownNow();
            }

            // Heal the network, then sync everyone until nothing is pending and all are current.
            settle.run();

            Map<String, Map.Entry<String, Integer>> snapshots = new LinkedHashMap<>();
            for (Device d : devices) snapshots.put(d.id(), d.snapshot());
            if (Fleet.DEBUG != null) {
                Files.createDirectories(LOG_DIR);
                for (JavaDevice j : java) Files.write(LOG_DIR.resolve("seed" + seed + "-" + j.id() + ".pulls.log"), j.net.log);
                List<String> feed = new ArrayList<>();
                for (List<Object> row : Fleet.query(url, "select seq, pos, kind, op_id, scopes::text, scopes_before::text, op::text from feed where record = ? order by pos, seq", Fleet.DEBUG)) feed.add(row.toString());
                Files.write(LOG_DIR.resolve("seed" + seed + "-feed.log"), feed);
            }
            Set<String> distinct = new HashSet<>();
            snapshots.values().forEach(e -> distinct.add(e.getKey()));
            assertEquals(1, distinct.size(), () -> divergence(seed, url, snapshots));
            for (Map.Entry<String, Integer> e : snapshots.values()) assertEquals(0, e.getValue(), snapshots.toString());
            String snapshot = distinct.iterator().next();
            assertTrue(snapshot.contains("dossier:é"), snapshot);
            assertTrue(snapshot.contains(THIES), snapshot);

            // What the database holds agrees with its feed, and with every device.
            Truth truth = Fleet.serverTruth(url);
            assertEquals(List.of(), truth.wrong(), "records rows disagree with the feed");
            JsonObject held = (JsonObject) Json.parse(snapshot);
            Map<String, JsonValue> visible = new TreeMap<>();
            truth.rebuilt().forEach((r, v) -> {
                if (held.has(r)) visible.put(r, v);
            });
            assertEquals(snapshot, Json.canonical(new JsonObject(visible)));

            // Both servers served pushes and pulls.
            Map<String, Integer> counts = new TreeMap<>(served.snapshot());
            for (TsDevice t : ts) t.served().forEach((k, n) -> counts.merge(k, n, Integer::sum));
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("seed", seed);
            summary.put("migrated_by", migratedBy);
            summary.put("compacted", compacted);
            summary.put("served", counts);
            String line = Json.stringify(Json.of(summary));
            System.out.println(line);
            Files.createDirectories(LOG_DIR);
            Files.writeString(LOG_DIR.resolve("summary.jsonl"), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            for (String name : Fleet.ROUTE) {
                for (String kind : List.of("push", "pull")) {
                    assertTrue(counts.getOrDefault(name + "/" + kind, 0) > 0, name + " served no " + kind + ": " + counts);
                }
            }
        } finally {
            for (TsDevice t : ts) t.close();
            for (JavaDevice j : java) j.close();
        }
    }

    /** Which devices differ from the database, record by record. */
    static String divergence(int seed, String url, Map<String, Map.Entry<String, Integer>> snapshots) {
        try {
            Truth truth = Fleet.serverTruth(url);
            StringBuilder out = new StringBuilder("seed " + seed + ": devices diverge (records rows disagreeing with the feed: "
                    + truth.wrong() + ")");
            for (Map.Entry<String, Map.Entry<String, Integer>> e : snapshots.entrySet()) {
                JsonObject held = (JsonObject) Json.parse(e.getValue().getKey());
                Set<String> records = new java.util.TreeSet<>(held.members().keySet());
                records.addAll(truth.rebuilt().keySet());
                for (String r : records) {
                    String mine = held.has(r) ? Json.canonical(held.get(r)) : "absent";
                    String db = truth.rebuilt().containsKey(r) ? Json.canonical(truth.rebuilt().get(r)) : "absent";
                    if (!mine.equals(db)) {
                        out.append("\n  ").append(e.getKey()).append(" (pending ").append(e.getValue().getValue()).append(") ")
                                .append(r).append(": device ").append(mine).append(" / database ").append(db);
                    }
                }
            }
            return out.toString();
        } catch (Exception ex) {
            return "seed " + seed + ": devices diverge: " + snapshots + " (" + ex + ")";
        }
    }

    /**
     * Heal, settle, compact through a random server's control API. A probe device pushes an op with
     * awkward values first and pulls past it, as if its ack was lost; after the compaction folds it,
     * its retry must be acked by both servers, and the same id with other content refused by both
     * (each server checks the op_hash the other may have written).
     */
    static Map<String, Object> compactMidRun(Supplier<String> server, String url, String awa, Runnable settle) throws Exception {
        settle.run(); // the outbox ops of the lossy phase land before the probe op
        LocalWriter writer = new LocalWriter(SCHEMA, "probe-awa", MixedServerFleetTest::now);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("10", true);
        value.put("a", 1e21);
        value.put("n", "é😀");
        value.put("z", List.of(2.5));
        JsonObject op = Wire.encode(writer.assign(SHARED.get(0), "client_name", value));
        String opId = ((JsonString) op.get("op_id")).value();
        String ackedOnly = "{\"acked\":[" + Json.stringify(new JsonString(opId)) + "],\"refused\":[]}";
        JsonObject first = push(server.get(), awa, "probe-awa", List.of(op));
        assertEquals(ackedOnly, Json.canonical(first));
        pullAll(server.get(), awa, "probe-awa", 0);

        // Compaction folds only what every live device has pulled: settle the fleet first.
        settle.run();
        String by = server.get();
        JsonObject result = Fleet.control(by, "POST", "/compact", List.of());
        assertTrue(((JsonNumber) result.get("records")).longValue() > 0, result.toString());
        assertFalse(Fleet.query(url, "select 1 from compacted_ops where op_id = ?", opId).isEmpty(),
                () -> "the probe op was not folded: " + result);
        Map<String, JsonValue> m = new LinkedHashMap<>(op.members());
        m.put("value", new JsonString("other"));
        JsonObject tampered = new JsonObject(m);
        for (String s : NAMES) {
            JsonObject again = push(s, awa, "probe-awa", List.of(op));
            assertEquals(ackedOnly, Json.canonical(again), "compacted by " + by + ", retried on " + s);
            JsonObject bad = push(s, awa, "probe-awa", List.of(tampered));
            assertEquals(0, size(bad.get("acked")), "compacted by " + by + ", tampered retry on " + s + ": " + bad);
            String reason = str(((JsonArray) bad.get("refused")).items().get(0), "reason");
            assertTrue(reason != null && reason.startsWith("op id already used"), bad.toString());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("by", by);
        out.putAll(result.members());
        return out;
    }

    /**
     * Two raw probe devices of moussa's, at the same cursor, pull every scope event one from each
     * server, and must get identical items: the thies record moving into zone dakar and back out,
     * each move pushed once through each server; then moussa's token gaining zone thies, losing it
     * and gaining it again. Returns the token with thies, for the fleet.
     */
    static String scopeChange(Supplier<String> server, String awa) throws Exception {
        LocalWriter writer = new LocalWriter(SCHEMA, "probe-awa2", MixedServerFleetTest::now);
        // The thies record must be visible to nobody but thies: no agent, zone thies.
        JsonObject pushed = push(server.get(), awa, "probe-awa2",
                ops(writer.assign(THIES, "agent", (Object) null), writer.assign(THIES, "zone", "thies")));
        assertEquals(2, size(pushed.get("acked")), pushed.toString());
        String old = Fleet.tokenFor(server.get(), "moussa", List.of("dakar"));
        String fresh = Fleet.tokenFor(server.get(), "moussa", List.of("dakar", "thies"));
        String[] pair = {"probe-m-a", "probe-m-b"};
        long[] cursor = {Math.max(pullAll("ts", old, pair[0], 0), pullAll("java", old, pair[1], 0))};

        interface Both {
            List<JsonValue> run(String token, String a, String b, String what) throws Exception;
        }
        Both both = (token, a, b, what) -> {
            Pages pa = pages(a, token, pair[0], cursor[0]);
            Pages pb = pages(b, token, pair[1], cursor[0]);
            String ca = Json.canonical(new JsonArray(pa.items()));
            String cb = Json.canonical(new JsonArray(pb.items()));
            assertEquals(ca, cb, what + " answered differently (" + a + " vs " + b + ")");
            cursor[0] = Math.max(pa.cursor(), pb.cursor());
            return pa.items();
        };

        for (String via : List.of("ts", "java")) {
            JsonObject moved = push(via, awa, "probe-awa2", ops(writer.assign(THIES, "zone", "dakar")));
            assertEquals(1, size(moved.get("acked")), moved.toString());
            assertTrue(hasHistory(both.run(old, "ts", "java", "a move into dakar pushed via " + via), THIES));
            moved = push(via, awa, "probe-awa2", ops(writer.assign(THIES, "zone", "thies")));
            assertEquals(1, size(moved.get("acked")), moved.toString());
            List<JsonValue> items = both.run(old, "java", "ts", "a move out of dakar pushed via " + via);
            assertTrue(hasExit(items, THIES), items.toString());
        }
        Object[][] steps = {{fresh, "ts", "java", "a token gaining thies"}, {old, "java", "ts", "a token losing thies"},
            {fresh, "java", "ts", "a token gaining thies again"}};
        for (Object[] st : steps) {
            List<JsonValue> items = both.run((String) st[0], (String) st[1], (String) st[2], (String) st[3]);
            if (st[0] == old) assertTrue(hasExit(items, THIES), items.toString());
            else assertTrue(hasHistory(items, THIES), items.toString());
        }
        return fresh;
    }

    /**
     * A lone surrogate (valid JSON text: "\ud800") inside an op value, sent to each server.
     * PostgreSQL's jsonb cannot store it; both servers refuse that op as malformed (same reason),
     * apply the rest of the batch, store nothing of it, and keep serving afterwards.
     */
    @Test
    void loneSurrogateInAnOpValueIsAnsweredAlike() throws Exception {
        String url = Fleet.recreateDatabase();
        Fleet.migrateJava(url);
        Map<String, String> answers = new TreeMap<>();
        try (Servers s = new Servers(url, "surrogate")) {
            String token = Fleet.tokenFor("ts", "awa", List.of("dakar"));
            for (String name : NAMES) {
                String device = "surrogate-" + name;
                LocalWriter writer = new LocalWriter(SCHEMA, device, MixedServerFleetTest::now);
                List<JsonObject> batch = ops(writer.assign("dossier:s", "zone", "dakar"),
                        writer.assign("dossier:s", "client_name", "a\ud800b"));
                String body = Json.stringify(new JsonObject(Map.of("ops", new JsonArray(List.<JsonValue>copyOf(batch)))))
                        .replace("\ud800", "\\ud800");
                assertTrue(body.contains("\\ud800"), body);
                JsonObject res = http(name, "POST", "/v1/push", token, device, body);
                assertEquals(1, size(res.get("acked")), name + ": " + res);
                List<String> reasons = new ArrayList<>();
                for (JsonValue r : ((JsonArray) res.get("refused")).items()) reasons.add(str(r, "reason"));
                answers.put(name, "200, acked " + size(res.get("acked")) + ", refused " + reasons);
                assertEquals(List.of("malformed op: lone surrogate in op.value"), reasons, name + ": " + res);
                // Still serving: the same device's next push of a plain op works.
                JsonObject ok = Wire.encode(writer.assign("dossier:s", "zone", "dakar"));
                assertEquals(1, size(push(name, token, device, List.of(ok)).get("acked")));
            }
            System.out.println("lone surrogate: " + answers);
            assertEquals(List.of(), Fleet.query(url, "select op_id from feed where op::text like '%client_name%'"));
        }
        assertEquals(answers.get("ts"), answers.get("java"), answers.toString());
    }

    /**
     * A device whose read keys changed gets the history of the records entering its scope in its
     * next pull (ADR-0011). If that answer is lost, the retry (same cursor) must carry it again.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"java", "ts"})
    void aLostScopeDeltaIsSentAgain(String name) throws Exception {
        Assumptions.assumeTrue(NAMES.contains(name));
        String url = Fleet.recreateDatabase();
        Fleet.migrateJava(url);
        try (Servers s = new Servers(url, "lost-delta-" + name)) {
            String awa = Fleet.tokenFor("ts", "awa", List.of("thies"));
            LocalWriter writer = new LocalWriter(SCHEMA, "awa-1", MixedServerFleetTest::now);
            assertEquals(2, size(push(name, awa, "awa-1", ops(writer.assign(THIES, "zone", "thies"), writer.inc(THIES, "visits", 2)))
                    .get("acked")));
            String old = Fleet.tokenFor(name, "moussa", List.of("dakar"));
            String fresh = Fleet.tokenFor(name, "moussa", List.of("dakar", "thies"));
            long cursor = pullAll(name, old, "moussa-1", 0);
            List<JsonValue> first = pages(name, fresh, "moussa-1", cursor).items();
            assertTrue(hasHistory(first, THIES), first.toString()); // the delta... and its answer is lost
            List<JsonValue> again = pages(name, fresh, "moussa-1", cursor).items();
            assertTrue(hasHistory(again, THIES), name + ": the retried pull has no history: " + again);
        }
    }

    /**
     * Found by this fleet (seed 3): a device's read keys change (moussa gains zone thies) while a
     * record it could not see at its cursor (zone thies only) moves into a scope its OLD keys also
     * cover (agent moussa). The scope delta used to be computed from current scopes, so the record
     * was not "entering" and the feed's scope row, read with the new keys, was not a move-in either:
     * the device never got its history. Fixed by judging the delta at the device's cursor (ADR-0011,
     * 2026-10-07 b) in the reference and in the Java server.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"java", "ts"})
    void aRecordMovingIntoBothKeySetsDuringATokenChangeComesWithItsHistory(String name) throws Exception {
        String url = Fleet.recreateDatabase();
        Fleet.migrateJava(url);
        try (Servers s = new Servers(url, "both-keys-" + name)) {
            String awa = Fleet.tokenFor("ts", "awa", List.of("thies"));
            LocalWriter writer = new LocalWriter(SCHEMA, "awa-1", MixedServerFleetTest::now);
            assertEquals(2, size(push(name, awa, "awa-1", ops(writer.assign(THIES, "zone", "thies"), writer.inc(THIES, "visits", 2)))
                    .get("acked")));
            String old = Fleet.tokenFor(name, "moussa", List.of("dakar"));
            String fresh = Fleet.tokenFor(name, "moussa", List.of("dakar", "thies"));
            long cursor = pullAll(name, old, "moussa-1", 0);
            // The record now has agent moussa: visible to the old keys and the new ones.
            assertEquals(1, size(push(name, awa, "awa-1", ops(writer.assign(THIES, "agent", "moussa"))).get("acked")));
            List<JsonValue> items = pages(name, fresh, "moussa-1", cursor).items();
            assertTrue(hasHistory(items, THIES), name + ": the record entered without its history: " + items);
        }
    }
}
