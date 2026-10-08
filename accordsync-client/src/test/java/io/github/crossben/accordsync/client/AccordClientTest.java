package io.github.crossben.accordsync.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.core.Strategy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Port of the Python test_client.py / Dart client_test.dart against the in-memory FakeServer. */
class AccordClientTest {
    static final Schema SCHEMA = Schema.define(Map.of("dossier", orderedFields()));

    private static Map<String, Strategy> orderedFields() {
        Map<String, Strategy> f = new LinkedHashMap<>();
        f.put("agent", Strategy.lww());
        f.put("zone", Strategy.lww());
        f.put("client_name", Strategy.lww());
        f.put("status", Strategy.conflict());
        f.put("visits", Strategy.counter());
        f.put("docs", Strategy.set());
        return f;
    }

    static JsonObject obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return (JsonObject) Json.of(m);
    }

    final Map<String, List<String>> zones = new ConcurrentHashMap<>();
    FakeServer server;

    @BeforeEach
    void world() {
        zones.put("awa", List.of("dakar"));
        zones.put("moussa", List.of("dakar"));
        zones.put("fatou", List.of("thies"));
        server = new FakeServer(SCHEMA, (r, f) -> {
            List<String> out = new ArrayList<>();
            if (f.get("agent") instanceof JsonString a) out.add("agent:" + a.value());
            if (f.get("zone") instanceof JsonString z) out.add("zone:" + z.value());
            return out;
        }, user -> {
            List<String> keys = new ArrayList<>();
            keys.add("agent:" + user);
            for (String z : zones.get(user)) keys.add("zone:" + z);
            return new FakeServer.Access(keys, keys);
        });
    }

    AccordClient open(String user, String device) {
        return open(user, device, new MemoryStorage(), null, 500, 200);
    }

    AccordClient open(String user, String device, StorageAdapter storage) {
        return open(user, device, storage, null, 500, 200);
    }

    AccordClient open(String user, String device, Transport transport) {
        return open(user, device, new MemoryStorage(), transport, 500, 200);
    }

    AccordClient open(String user, String device, StorageAdapter storage, Transport transport, int pullLimit, int pushBatch) {
        return AccordClient.open(AccordClient.options().schema(SCHEMA).storage(storage)
                .transport(transport != null ? transport : server.transportFor(user)).deviceId(device)
                .pullLimit(pullLimit).pushBatch(pushBatch));
    }

    static void syncAll(AccordClient... cs) {
        for (int k = 0; k < 2; k++) for (AccordClient c : cs) c.sync();
    }

    static JsonValue field(AccordClient c, String record, String field) {
        return c.read(record).orElseThrow().get(field);
    }

    static class Spy implements Transport {
        final Transport inner;
        final List<String> pushed = new CopyOnWriteArrayList<>();
        final List<Integer> batches = new CopyOnWriteArrayList<>();
        final List<Long> pulls = new CopyOnWriteArrayList<>();
        volatile Runnable duringPull;

        Spy(Transport inner) {
            this.inner = inner;
        }

        @Override
        public PushResult push(String deviceId, List<JsonObject> ops) {
            batches.add(ops.size());
            for (JsonObject o : ops) pushed.add(((JsonString) o.get("op_id")).value());
            return inner.push(deviceId, ops);
        }

        @Override
        public PullResult pull(String deviceId, long cursor, int limit) {
            pulls.add(cursor);
            PullResult r = inner.pull(deviceId, cursor, limit);
            Runnable hook = duringPull;
            duringPull = null;
            if (hook != null) hook.run();
            return r;
        }
    }

    @Test
    void speaksProtocolVersion1() {
        assertThat(AccordClient.PROTOCOL_VERSION).isEqualTo(1);
    }

    @Test
    void twoAgentsEditOfflineThenConverge() {
        AccordClient awa = open("awa", "awa-phone");
        AccordClient moussa = open("moussa", "moussa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        awa.assign("dossier:1", "agent", "awa");
        awa.sync();
        moussa.sync();
        awa.inc("dossier:1", "visits", 2);
        awa.add("dossier:1", "docs", "cni.pdf");
        moussa.inc("dossier:1", "visits", 3);
        moussa.assign("dossier:1", "client_name", "Aminata Fall");
        syncAll(awa, moussa);
        for (AccordClient c : List.of(awa, moussa)) {
            assertThat(c.read("dossier:1")).contains(obj("agent", "awa", "zone", "dakar", "client_name", "Aminata Fall",
                    "visits", 5, "docs", List.of("cni.pdf")));
            assertThat(c.status().pending()).isZero();
        }
    }

    @Test
    void conflictSurfacesOnBothDevicesAndAResolutionClearsIt() {
        AccordClient awa = open("awa", "awa-phone");
        AccordClient moussa = open("moussa", "moussa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        syncAll(awa, moussa);
        awa.assign("dossier:1", "status", "approved");
        moussa.assign("dossier:1", "status", "rejected");
        syncAll(awa, moussa);
        for (AccordClient c : List.of(awa, moussa)) {
            assertThat(c.conflicts()).containsExactly(new ConflictInfo("dossier:1", "status", List.of(
                    new ConflictInfo.Value(new JsonString("approved"), "awa-phone:2"),
                    new ConflictInfo.Value(new JsonString("rejected"), "moussa-phone:1"))));
        }
        moussa.resolve("dossier:1", "status", "approved");
        syncAll(awa, moussa);
        for (AccordClient c : List.of(awa, moussa)) {
            assertThat(c.conflicts()).isEmpty();
            assertThat(field(c, "dossier:1", "status")).isEqualTo(obj("value", "approved"));
        }
    }

    @Test
    void refusedWriteIsRolledBackAndReported() {
        AccordClient fatou = open("fatou", "fatou-phone");
        AccordClient awa = open("awa", "awa-phone");
        fatou.assign("dossier:7", "zone", "thies");
        fatou.sync();
        List<Refusal> refusals = new ArrayList<>();
        List<List<String>> changes = new ArrayList<>();
        awa.onRefused(refusals::add);
        awa.assign("dossier:7", "client_name", "not mine");
        assertThat(field(awa, "dossier:7", "client_name")).isEqualTo(new JsonString("not mine")); // local-first
        awa.onChange(changes::add);
        awa.sync();
        assertThat(refusals).containsExactly(
                new Refusal("awa-phone:1", "dossier:7", "client_name", "out of scope: you may not write dossier:7"));
        assertThat(changes).contains(List.of("dossier:7"));
        assertThat(awa.read("dossier:7")).isEmpty();
        assertThat(awa.status().pending()).isZero();
    }

    @Test
    void refusalRollbackIsPersisted() {
        MemoryStorage storage = new MemoryStorage();
        AccordClient fatou = open("fatou", "fatou-phone");
        fatou.assign("dossier:7", "zone", "thies");
        fatou.sync();
        AccordClient awa = open("awa", "awa-phone", storage);
        awa.assign("dossier:7", "client_name", "not mine");
        awa.sync();
        assertThat(storage.load().ops()).isEmpty();
        assertThat(storage.load().outbox()).isEmpty();
        assertThat(open("awa", "x", storage).read("dossier:7")).isEmpty();
    }

    @Test
    void refusalRollsBackOnlyTheRefusedOpKeepingLaterAcceptedOnes() {
        AccordClient fatou = open("fatou", "fatou-phone");
        fatou.assign("dossier:7", "zone", "thies");
        fatou.assign("dossier:7", "client_name", "Fatou's");
        fatou.sync();
        AccordClient awa = open("awa", "awa-phone");
        awa.sync();
        awa.assign("dossier:7", "client_name", "not mine");
        awa.assign("dossier:1", "zone", "dakar");
        awa.sync();
        assertThat(awa.read("dossier:7")).isEmpty();
        assertThat(awa.read("dossier:1")).contains(obj("zone", "dakar", "visits", 0, "docs", List.of()));
    }

    @Test
    void recordReassignedAwayIsRemoved() {
        AccordClient awa = open("awa", "awa-phone");
        AccordClient fatou = open("fatou", "fatou-phone");
        awa.assign("dossier:3", "agent", "awa");
        awa.inc("dossier:3", "visits", 4);
        awa.sync();
        awa.assign("dossier:3", "agent", "fatou");
        List<List<String>> changed = new ArrayList<>();
        awa.onChange(changed::add);
        awa.sync();
        assertThat(awa.read("dossier:3")).isEmpty();
        assertThat(changed).contains(List.of("dossier:3"));
        fatou.sync();
        assertThat(field(fatou, "dossier:3", "agent")).isEqualTo(new JsonString("fatou"));
        assertThat(field(fatou, "dossier:3", "visits")).isEqualTo(Json.of(4));
    }

    @Test
    void exitIsPersisted() {
        MemoryStorage storage = new MemoryStorage();
        AccordClient awa = open("awa", "awa-phone", storage);
        awa.assign("dossier:3", "agent", "awa");
        awa.sync();
        awa.assign("dossier:3", "agent", "fatou");
        awa.sync();
        assertThat(storage.load().ops()).isEmpty();
        assertThat(open("awa", "x", storage).read("dossier:3")).isEmpty();
    }

    @Test
    void exitOfACompactedRecordDropsItsStoredSnapshot() {
        MemoryStorage storage = new MemoryStorage();
        AccordClient awa = open("awa", "awa-phone", storage);
        awa.assign("dossier:3", "agent", "awa");
        awa.sync();
        server.compact("dossier:3");
        awa.sync();
        assertThat(storage.load().snapshots()).hasSize(1);
        awa.assign("dossier:3", "agent", "fatou");
        awa.sync();
        assertThat(awa.read("dossier:3")).isEmpty();
        assertThat(storage.load().snapshots()).isEmpty();
        assertThat(open("awa", "x", storage).read("dossier:3")).isEmpty();
    }

    @Test
    void followsScopeChangeWithoutResyncKeepingUnpushedEdits() {
        AccordClient fatou = open("fatou", "fatou-phone");
        fatou.assign("dossier:8", "zone", "thies");
        fatou.sync();
        AccordClient awa = open("awa", "awa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        awa.sync();
        assertThat(awa.records()).containsExactly("dossier:1");
        zones.put("awa", List.of("dakar", "thies"));
        awa.inc("dossier:8", "visits", 1);
        List<String> resyncs = new ArrayList<>();
        awa.onResync(() -> resyncs.add("x"));
        awa.sync();
        assertThat(resyncs).isEmpty();
        assertThat(awa.records()).containsExactly("dossier:1", "dossier:8");
        assertThat(awa.records("dossier")).containsExactly("dossier:1", "dossier:8");
        assertThat(awa.records("other")).isEmpty();
        assertThat(awa.read("dossier:8")).contains(obj("zone", "thies", "visits", 1, "docs", List.of()));
        fatou.sync();
        assertThat(field(fatou, "dossier:8", "visits")).isEqualTo(Json.of(1));
    }

    @Test
    void resyncRequiredPushesThenReloadsFromZero() {
        MemoryStorage storage = new MemoryStorage();
        AccordClient awa = open("awa", "awa-phone", storage);
        awa.assign("dossier:1", "zone", "dakar");
        awa.sync();
        awa.inc("dossier:1", "visits", 2);
        server.requireResync("awa-phone");
        List<String> resyncs = new ArrayList<>();
        awa.onResync(() -> resyncs.add("x"));
        awa.sync();
        assertThat(resyncs).hasSize(1);
        assertThat(awa.status().pending()).isZero();
        JsonObject want = obj("zone", "dakar", "visits", 2, "docs", List.of());
        assertThat(awa.read("dossier:1")).contains(want);
        AccordClient again = open("awa", "x", storage);
        assertThat(again.read("dossier:1")).contains(want);
        assertThat(again.status().cursor()).isEqualTo(awa.status().cursor());
    }

    @Test
    void writeDuringAResyncPullSurvives() {
        Spy spy = new Spy(server.transportFor("awa"));
        AccordClient awa = open("awa", "awa-phone", spy);
        awa.assign("dossier:1", "zone", "dakar");
        awa.sync();
        server.requireResync("awa-phone");
        // lands while the resync answer is in flight
        spy.duringPull = () -> awa.inc("dossier:1", "visits", 3);
        awa.sync();
        assertThat(field(awa, "dossier:1", "visits")).isEqualTo(Json.of(3));
        awa.sync();
        assertThat(awa.status().pending()).isZero();
        AccordClient other = open("awa", "awa-laptop");
        other.sync();
        assertThat(field(other, "dossier:1", "visits")).isEqualTo(Json.of(3));
    }

    @Test
    void survivesRestart() {
        MemoryStorage storage = new MemoryStorage();
        AccordClient first = open("awa", "awa-tablet", storage);
        first.assign("dossier:1", "zone", "dakar");
        first.sync();
        first.inc("dossier:1", "visits", 1); // offline, then the app is killed
        long cursorBefore = first.status().cursor();
        assertThat(cursorBefore).isPositive();
        first.close();
        AccordClient again = open("awa", "other-id", storage);
        assertThat(again.deviceId()).isEqualTo("awa-tablet");
        assertThat(again.status().pending()).isEqualTo(1);
        assertThat(again.status().cursor()).isEqualTo(cursorBefore);
        assertThat(field(again, "dossier:1", "visits")).isEqualTo(Json.of(1));
        assertThat(again.inc("dossier:1", "visits", 1).opId()).isEqualTo("awa-tablet:3");
        again.sync();
        assertThat(again.status().pending()).isZero();
    }

    @Test
    void cursorIsPersistedAfterEveryPage() {
        AccordClient awa = open("awa", "awa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        for (int i = 0; i < 5; i++) awa.inc("dossier:1", "visits", 1);
        awa.sync();
        MemoryStorage storage = new MemoryStorage();
        Spy spy = new Spy(server.transportFor("moussa"));
        AccordClient reader = open("moussa", "moussa-phone", storage, spy, 2, 200);
        reader.sync();
        assertThat(storage.load().meta().orElseThrow().cursor()).isEqualTo(6);
        Spy spy2 = new Spy(server.transportFor("moussa"));
        AccordClient again = open("moussa", "x", storage, spy2, 2, 200);
        again.sync();
        assertThat(spy2.pulls).containsExactly(6L);
        assertThat(field(again, "dossier:1", "visits")).isEqualTo(Json.of(5));
    }

    @Test
    void writesDuringASyncRoundAreNotLost() {
        Spy spy = new Spy(server.transportFor("awa"));
        AccordClient awa = open("awa", "awa-phone", spy);
        awa.assign("dossier:1", "zone", "dakar");
        spy.duringPull = () -> awa.inc("dossier:1", "visits", 1);
        awa.sync();
        awa.sync();
        AccordClient other = open("awa", "awa-laptop");
        other.sync();
        assertThat(field(other, "dossier:1", "visits")).isEqualTo(Json.of(1));
    }

    @Test
    void writesFromAnotherThreadDuringARoundAreNotLost() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Spy slow = new Spy(server.transportFor("awa")) {
            @Override
            public PushResult push(String deviceId, List<JsonObject> ops) {
                gate.countDown();
                await(release);
                return super.push(deviceId, ops);
            }
        };
        AccordClient awa = open("awa", "awa-phone", slow);
        awa.assign("dossier:1", "zone", "dakar");
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Thread t = new Thread(() -> {
            try {
                awa.sync();
            } catch (RuntimeException e) {
                errors.add(e);
            }
        });
        t.start();
        assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
        awa.inc("dossier:1", "visits", 1); // the lock is not held during network I/O
        release.countDown();
        t.join(5000);
        assertThat(errors).isEmpty();
        awa.sync();
        AccordClient other = open("awa", "awa-laptop");
        other.sync();
        assertThat(field(other, "dossier:1", "visits")).isEqualTo(Json.of(1));
    }

    static void await(CountDownLatch l) {
        try {
            if (!l.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void concurrentSyncCallsShareOneRound() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Spy slow = new Spy(server.transportFor("awa")) {
            @Override
            public PullResult pull(String deviceId, long cursor, int limit) {
                gate.countDown();
                await(release);
                return super.pull(deviceId, cursor, limit);
            }
        };
        AccordClient awa = open("awa", "awa-phone", slow);
        List<Long> synced = new CopyOnWriteArrayList<>();
        awa.onSynced(synced::add);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 3; i++) threads.add(new Thread(awa::sync));
        threads.get(0).start();
        assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
        threads.get(1).start();
        threads.get(2).start();
        Thread.sleep(50); // let them reach the shared round
        release.countDown();
        for (Thread t : threads) t.join(5000);
        assertThat(slow.pulls).hasSize(1);
        assertThat(synced).hasSize(1);
    }

    @Test
    void concurrentSyncCallersShareTheRoundsFailure() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Transport failing = new Transport() {
            @Override
            public PushResult push(String deviceId, List<JsonObject> ops) {
                throw new AssertionError();
            }

            @Override
            public PullResult pull(String deviceId, long cursor, int limit) {
                gate.countDown();
                await(release);
                throw new HttpException(500, "boom");
            }
        };
        AccordClient awa = open("awa", "awa-phone", failing);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        Runnable run = () -> {
            try {
                awa.sync();
            } catch (RuntimeException e) {
                errors.add(e);
            }
        };
        Thread a = new Thread(run);
        Thread b = new Thread(run);
        a.start();
        assertThat(gate.await(5, TimeUnit.SECONDS)).isTrue();
        b.start();
        Thread.sleep(50);
        release.countDown();
        a.join(5000);
        b.join(5000);
        assertThat(errors).hasSize(2).allSatisfy(e -> assertThat(e).isInstanceOf(HttpException.class));
    }

    @Test
    void syncFromInsideItsOwnRoundFails() {
        AccordClient awa = open("awa", "awa-phone");
        List<Throwable> seen = new ArrayList<>();
        awa.onSynced(c -> {
            try {
                awa.sync();
            } catch (IllegalStateException e) {
                seen.add(e);
            }
        });
        awa.sync();
        assertThat(seen).hasSize(1);
    }

    @Test
    void pushesInWriteOrderInBatchesAndPullsEveryPage() {
        Spy spy = new Spy(server.transportFor("awa"));
        AccordClient awa = open("awa", "awa-phone", new MemoryStorage(), spy, 500, 3);
        awa.assign("dossier:1", "zone", "dakar");
        for (int i = 0; i < 9; i++) awa.inc("dossier:1", "visits", 1);
        awa.sync();
        List<String> want = new ArrayList<>();
        for (int i = 1; i <= 10; i++) want.add("awa-phone:" + i);
        assertThat(spy.pushed).isEqualTo(want);
        assertThat(spy.batches).containsExactly(3, 3, 3, 1);
        Spy readerSpy = new Spy(server.transportFor("moussa"));
        AccordClient reader = open("moussa", "moussa-phone", new MemoryStorage(), readerSpy, 4, 200);
        reader.sync();
        assertThat(field(reader, "dossier:1", "visits")).isEqualTo(Json.of(9));
        assertThat(reader.status().cursor()).isEqualTo(10);
        assertThat(readerSpy.pulls).containsExactly(0L, 4L, 8L);
    }

    @Test
    void outboxReloadedInWriteOrder() {
        MemoryStorage storage = new MemoryStorage();
        AccordClient awa = open("awa", "awa-phone", storage);
        awa.assign("dossier:1", "zone", "dakar");
        for (int i = 0; i < 11; i++) awa.inc("dossier:1", "visits", 1);
        awa.close();
        Spy spy = new Spy(server.transportFor("awa"));
        AccordClient again = open("awa", "awa-phone", storage, spy, 500, 200);
        again.sync();
        List<String> want = new ArrayList<>();
        for (int i = 1; i <= 12; i++) want.add("awa-phone:" + i);
        assertThat(spy.pushed).isEqualTo(want);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void randomWorkByThreeDevicesConverges(int seed) {
        Random rng = new Random(seed);
        List<AccordClient> devices = List.of(open("awa", "awa-1"), open("awa", "awa-2"),
                open("moussa", "moussa-1", new MemoryStorage(), null, 7, 200));
        devices.get(0).assign("dossier:1", "zone", "dakar");
        devices.get(0).assign("dossier:2", "zone", "dakar");
        syncAll(devices.toArray(AccordClient[]::new));
        for (int i = 0; i < 80; i++) {
            AccordClient d = devices.get(rng.nextInt(devices.size()));
            String record = "dossier:" + (1 + rng.nextInt(2));
            switch (rng.nextInt(6)) {
                case 0 -> d.inc(record, "visits", rng.nextInt(8) - 2);
                case 1 -> d.add(record, "docs", "doc-" + rng.nextInt(4));
                case 2 -> d.remove(record, "docs", "doc-" + rng.nextInt(4));
                case 3 -> d.assign(record, "status", "s" + rng.nextInt(3));
                case 4 -> d.assign(record, "client_name", "n" + rng.nextInt(10));
                default -> d.sync();
            }
        }
        syncAll(devices.toArray(AccordClient[]::new));
        Set<String> states = new HashSet<>();
        for (AccordClient d : devices) {
            states.add(Json.canonical(Json.of(List.of(d.read("dossier:1").orElseThrow(), d.read("dossier:2").orElseThrow()))));
        }
        assertThat(states).hasSize(1);
    }

    @Test
    void compactionSnapshotRestartAndMergeOnTop() {
        AccordClient awa = open("awa", "awa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        for (int i = 0; i < 4; i++) awa.inc("dossier:1", "visits", 1);
        awa.add("dossier:1", "docs", "a.pdf");
        awa.remove("dossier:1", "docs", "a.pdf");
        awa.assign("dossier:1", "status", "submitted");
        syncAll(awa);
        server.compact("dossier:1");
        MemoryStorage storage = new MemoryStorage();
        AccordClient tablet = open("awa", "awa-tablet", storage);
        tablet.inc("dossier:1", "visits", 10); // a blind offline write to an unseen record
        tablet.sync();
        Map<String, JsonValue> want = new LinkedHashMap<>(awa.read("dossier:1").orElseThrow().members());
        want.put("visits", Json.of(14));
        assertThat(tablet.read("dossier:1")).contains(new JsonObject(want));
        assertThat(storage.load().snapshots()).extracting(s -> s.record()).containsExactly("dossier:1");
        tablet.close();
        AccordClient again = open("awa", "x", storage);
        assertThat(again.read("dossier:1")).contains(obj("zone", "dakar", "visits", 14, "docs", List.of(),
                "status", obj("value", "submitted")));
        awa.sync();
        assertThat(field(awa, "dossier:1", "visits")).isEqualTo(Json.of(14));
    }

    @Test
    void lostStorageKeepsOldOpsAndNeverReusesAnOpId() {
        AccordClient before = open("awa", "awa-tablet");
        before.assign("dossier:1", "zone", "dakar");
        before.inc("dossier:1", "visits", 2);
        before.sync();
        AccordClient after = open("awa", "awa-tablet");
        after.sync();
        assertThat(after.inc("dossier:1", "visits", 3).opId()).isEqualTo("awa-tablet:3");
        after.sync();
        assertThat(after.status().pending()).isZero();
        assertThat(field(after, "dossier:1", "visits")).isEqualTo(Json.of(5));
    }

    @Test
    void deviceSeqAfterCompactionNeverReusesAnOpId() {
        AccordClient before = open("awa", "awa-tablet");
        before.assign("dossier:1", "zone", "dakar");
        before.inc("dossier:1", "visits", 2);
        before.sync();
        server.compact("dossier:1"); // its own ops now arrive folded in a snapshot
        AccordClient after = open("awa", "awa-tablet");
        after.sync();
        assertThat(after.inc("dossier:1", "visits", 3).opId()).isEqualTo("awa-tablet:3");
        after.sync();
        assertThat(after.status().pending()).isZero();
        assertThat(field(after, "dossier:1", "visits")).isEqualTo(Json.of(5));
    }

    @Test
    void deviceSeqIsPersisted() {
        AccordClient before = open("awa", "awa-tablet");
        before.assign("dossier:1", "zone", "dakar");
        before.inc("dossier:1", "visits", 2);
        before.sync();
        server.compact("dossier:1");
        MemoryStorage storage = new MemoryStorage();
        AccordClient after = open("awa", "awa-tablet", storage);
        after.sync();
        assertThat(storage.load().meta().orElseThrow().seq()).isEqualTo(2);
    }

    @Test
    void snapshotArrivingWithAnUnpushedEditKeepsItOnTop() {
        AccordClient awa = open("awa", "awa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        awa.inc("dossier:1", "visits", 4);
        awa.sync();
        server.compact("dossier:1");
        Spy spy = new Spy(server.transportFor("awa"));
        MemoryStorage storage = new MemoryStorage();
        AccordClient tablet = open("awa", "awa-tablet", storage, spy, 500, 200);
        tablet.sync();
        server.compact("dossier:1");
        spy.duringPull = () -> tablet.inc("dossier:1", "visits", 1);
        tablet.sync(); // the snapshot lands while the inc is in the outbox
        assertThat(tablet.status().pending()).isEqualTo(1);
        assertThat(field(tablet, "dossier:1", "visits")).isEqualTo(Json.of(5));
        assertThat(field(open("awa", "y", storage), "dossier:1", "visits")).isEqualTo(Json.of(5));
        tablet.sync();
        awa.sync();
        assertThat(field(awa, "dossier:1", "visits")).isEqualTo(Json.of(5));
    }

    @Test
    void reinstalledDeviceWritingBeforeFirstSyncGetsARefusal() {
        AccordClient before = open("awa", "awa-old");
        before.assign("dossier:1", "zone", "dakar");
        before.sync();
        AccordClient after = open("awa", "awa-old");
        List<Refusal> refusals = new ArrayList<>();
        after.onRefused(refusals::add);
        after.inc("dossier:1", "visits", 1); // offline write, numbered 1 again
        after.sync();
        assertThat(refusals).hasSize(1);
        assertThat(refusals.get(0).reason()).contains("op id already used");
    }

    @Test
    void backgroundSyncWithBackoff() throws Exception {
        server.failNext = 2;
        AccordClient awa = AccordClient.open(AccordClient.options().schema(SCHEMA).storage(new MemoryStorage())
                .transport(server.transportFor("awa")).deviceId("awa-bg").minBackoff(Duration.ofMillis(10))
                .maxBackoff(Duration.ofMillis(40)).syncInterval(Duration.ofMillis(20)));
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        CountDownLatch synced = new CountDownLatch(1);
        awa.onError(errors::add);
        awa.onSynced(c -> synced.countDown());
        awa.assign("dossier:1", "zone", "dakar");
        awa.start();
        assertThat(synced.await(5, TimeUnit.SECONDS)).isTrue();
        awa.stop();
        assertThat(errors).isNotEmpty();
        assertThat(errors.get(0)).isInstanceOf(HttpException.class);
        assertThat(awa.status().pending()).isZero();
        assertThat(awa.status().lastError()).isEmpty();
        assertThat(awa.status().lastSyncAt()).isPresent();
        awa.close();
    }

    @Test
    void backoffGrowsWithJitterAndCaps() throws Exception {
        server.failNext = 1000;
        List<Long> at = new CopyOnWriteArrayList<>();
        CountDownLatch five = new CountDownLatch(6);
        AccordClient awa = AccordClient.open(AccordClient.options().schema(SCHEMA).storage(new MemoryStorage())
                .transport(server.transportFor("awa")).deviceId("awa-bg").random(() -> 0.0)
                .minBackoff(Duration.ofMillis(40)).maxBackoff(Duration.ofMillis(160)));
        awa.onError(e -> {
            at.add(System.nanoTime());
            five.countDown();
        });
        awa.start();
        assertThat(five.await(10, TimeUnit.SECONDS)).isTrue();
        awa.close();
        List<Long> gaps = new ArrayList<>();
        for (int i = 1; i < 6; i++) gaps.add(TimeUnit.NANOSECONDS.toMillis(at.get(i) - at.get(i - 1)));
        // jitter 0 -> half of 40, 80, 160, 160, 160 ms
        assertThat(gaps.get(0)).isBetween(18L, 70L);
        assertThat(gaps.get(1)).isBetween(38L, 110L);
        assertThat(gaps.get(2)).isGreaterThanOrEqualTo(78L);
        assertThat(gaps.get(4)).isGreaterThanOrEqualTo(78L).isLessThan(200L);
    }

    @Test
    void backgroundSyncPicksUpWritesSoon() throws Exception {
        AccordClient awa = AccordClient.open(AccordClient.options().schema(SCHEMA).storage(new MemoryStorage())
                .transport(server.transportFor("awa")).deviceId("awa-bg").syncInterval(Duration.ofSeconds(60)));
        CountDownLatch first = new CountDownLatch(1);
        awa.onSynced(c -> first.countDown());
        awa.start();
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
        CountDownLatch second = new CountDownLatch(1);
        // A round already under way may end before the write is pushed: wait for an empty outbox.
        awa.onSynced(c -> {
            if (awa.status().pending() == 0) second.countDown();
        });
        awa.assign("dossier:1", "zone", "dakar");
        assertThat(second.await(5, TimeUnit.SECONDS)).isTrue(); // well before syncInterval
        awa.close();
    }

    @Test
    void aWriteDuringARoundIsSyncedSoonAfterIt() throws Exception {
        AccordClient awa = AccordClient.open(AccordClient.options().schema(SCHEMA).storage(new MemoryStorage())
                .transport(server.transportFor("awa")).deviceId("awa-bg").syncInterval(Duration.ofSeconds(60)));
        java.util.concurrent.atomic.AtomicBoolean wrote = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch pushed = new CountDownLatch(1);
        awa.onSynced(c -> {
            // Inside the round: the next one must not wait for syncInterval.
            if (wrote.compareAndSet(false, true)) awa.assign("dossier:1", "zone", "dakar");
            else if (awa.status().pending() == 0) pushed.countDown();
        });
        awa.start();
        assertThat(pushed.await(5, TimeUnit.SECONDS)).isTrue();
        awa.close();
    }

    @Test
    void aWriteDuringAManualRoundIsSyncedSoonAfterIt() throws Exception {
        AccordClient awa = AccordClient.open(AccordClient.options().schema(SCHEMA).storage(new MemoryStorage())
                .transport(server.transportFor("awa")).deviceId("awa-bg").syncInterval(Duration.ofSeconds(60)));
        CountDownLatch first = new CountDownLatch(1);
        awa.onSynced(c -> first.countDown());
        awa.start();
        assertThat(first.await(5, TimeUnit.SECONDS)).isTrue();
        java.util.concurrent.atomic.AtomicBoolean wrote = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch pushed = new CountDownLatch(1);
        awa.onSynced(c -> {
            if (wrote.compareAndSet(false, true)) awa.assign("dossier:1", "zone", "dakar");
            else if (awa.status().pending() == 0) pushed.countDown();
        });
        awa.sync(); // the write lands inside this round
        assertThat(pushed.await(5, TimeUnit.SECONDS)).isTrue();
        awa.close();
    }

    @Test
    void failedRoundKeepsTheOutbox() {
        AccordClient awa = open("awa", "awa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        server.failNext = 1;
        assertThatThrownBy(awa::sync).isInstanceOf(HttpException.class);
        assertThat(awa.status().pending()).isEqualTo(1);
        awa.sync();
        assertThat(awa.status().pending()).isZero();
    }

    @Test
    void receivedOpsAreStored() {
        AccordClient awa = open("awa", "awa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        awa.inc("dossier:1", "visits", 2);
        awa.sync();
        MemoryStorage storage = new MemoryStorage();
        AccordClient moussa = open("moussa", "moussa-phone", storage);
        moussa.sync();
        assertThat(storage.load().ops()).hasSize(2);
        // restarted offline: the state comes from storage alone
        AccordClient again = AccordClient.open(AccordClient.options().schema(SCHEMA).storage(storage).transport(new Transport() {
            @Override
            public PushResult push(String d, List<JsonObject> ops) {
                throw new AssertionError();
            }

            @Override
            public PullResult pull(String d, long c, int l) {
                throw new AssertionError();
            }
        }));
        assertThat(again.read("dossier:1")).contains(obj("zone", "dakar", "visits", 2, "docs", List.of()));
    }

    @Test
    void listenerExceptionsAreLoggedAndIgnored() {
        List<LogRecord> logged = new CopyOnWriteArrayList<>();
        Handler h = new Handler() {
            @Override
            public void publish(LogRecord r) {
                logged.add(r);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        AccordClient.LOG.addHandler(h);
        try {
            AccordClient awa = open("awa", "awa-phone");
            List<List<String>> seen = new ArrayList<>();
            awa.onChange(c -> {
                throw new IllegalStateException("listener bug");
            });
            Subscription off = awa.onChange(seen::add);
            awa.assign("dossier:1", "zone", "dakar");
            assertThat(seen).containsExactly(List.of("dossier:1"));
            assertThat(logged).anySatisfy(r -> assertThat(r.getMessage()).contains("listener threw"));
            off.unsubscribe();
            awa.assign("dossier:1", "zone", "thies");
            assertThat(seen).containsExactly(List.of("dossier:1"));
        } finally {
            AccordClient.LOG.removeHandler(h);
        }
    }

    @Test
    void sameListenerTwiceIsTwoSubscriptions() {
        AccordClient awa = open("awa", "awa-phone");
        List<List<String>> seen = Collections.synchronizedList(new ArrayList<>());
        java.util.function.Consumer<List<String>> l = seen::add;
        Subscription a = awa.onChange(l);
        awa.onChange(l);
        a.unsubscribe();
        awa.assign("dossier:1", "zone", "dakar");
        assertThat(seen).hasSize(1);
    }

    @Test
    void failedCommitStillReportsTheChangeThenThrows() {
        StorageAdapter broken = new StorageAdapter() {
            final MemoryStorage inner = new MemoryStorage();
            boolean fail;

            @Override
            public StorageSnapshot load() {
                return inner.load();
            }

            @Override
            public void commit(StorageTx tx) {
                if (fail) throw new StorageException("disk full", null);
                inner.commit(tx);
                fail = true;
            }

            @Override
            public void close() {}
        };
        AccordClient awa = open("awa", "awa-phone", broken);
        List<List<String>> seen = new ArrayList<>();
        awa.onChange(seen::add);
        assertThatThrownBy(() -> awa.assign("dossier:1", "zone", "dakar")).hasMessageContaining("disk full");
        assertThat(seen).containsExactly(List.of("dossier:1"));
    }

    @Test
    void survivesARestartOnSqlite(@TempDir Path dir) {
        Path path = dir.resolve("accord.db");
        AccordClient first = open("awa", "awa-tablet", JdbcStorage.sqlite(path));
        first.assign("dossier:1", "zone", "dakar");
        first.add("dossier:1", "docs", "a.pdf");
        first.sync();
        server.compact("dossier:1");
        first.inc("dossier:1", "visits", 2);
        first.sync();
        first.inc("dossier:1", "visits", 1);
        first.close();
        AccordClient again = open("awa", "x", JdbcStorage.sqlite(path));
        assertThat(again.deviceId()).isEqualTo("awa-tablet");
        assertThat(again.status().pending()).isEqualTo(1);
        assertThat(again.read("dossier:1")).contains(obj("zone", "dakar", "visits", 3, "docs", List.of("a.pdf")));
        assertThat(again.inc("dossier:1", "visits", 1).opId()).isEqualTo("awa-tablet:5");
        again.sync();
        assertThat(again.status().pending()).isZero();
        again.close();
    }

    @Test
    void readOfUnknownRecordIsEmpty() {
        assertThat(open("awa", "awa-phone").read("dossier:404")).isEqualTo(Optional.empty());
    }
}
