package io.github.crossben.accordsync.fleet;

import static io.github.crossben.accordsync.fleet.Fleet.SCHEMA;
import static io.github.crossben.accordsync.fleet.Fleet.SERVER_URL;
import static io.github.crossben.accordsync.fleet.Fleet.compactServer;
import static io.github.crossben.accordsync.fleet.Fleet.snapshotOf;
import static io.github.crossben.accordsync.fleet.Fleet.tokenFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.crossben.accordsync.client.AccordClient;
import io.github.crossben.accordsync.client.ConflictInfo;
import io.github.crossben.accordsync.client.HttpException;
import io.github.crossben.accordsync.client.HttpTransport;
import io.github.crossben.accordsync.client.JdbcStorage;
import io.github.crossben.accordsync.client.MemoryStorage;
import io.github.crossben.accordsync.client.Refusal;
import io.github.crossben.accordsync.client.StorageAdapter;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.RecordSnapshot;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Java client over real HTTP against the real server: the scenarios of the TypeScript e2e suite
 * whose outcome depends on the server (refusal reasons, exits, scope deltas, compaction, device_seq).
 * Port of python/interop/tests/test_http.py.
 */
class HttpTest {
    private final Map<String, String> tokens = new HashMap<>();
    private final List<AccordClient> opened = new ArrayList<>();

    @BeforeAll
    static void server() {
        Fleet.requireServer();
    }

    @BeforeEach
    void reset() {
        Fleet.resetServer();
        tokens.put("awa", tokenFor("awa", List.of("dakar")));
        tokens.put("moussa", tokenFor("moussa", List.of("dakar")));
        tokens.put("fatou", tokenFor("fatou", List.of("thies")));
    }

    @AfterEach
    void closeAll() {
        for (AccordClient c : opened) c.close();
    }

    private AccordClient open(String user, String deviceId) {
        return open(user, deviceId, new MemoryStorage());
    }

    private AccordClient open(String user, String deviceId, StorageAdapter storage) {
        AccordClient c = AccordClient.open(AccordClient.options()
                .schema(SCHEMA)
                .storage(storage)
                .deviceId(deviceId)
                .transport(new HttpTransport(SERVER_URL, () -> tokens.get(user))));
        opened.add(c);
        return c;
    }

    private static void syncAll(AccordClient... cs) {
        for (int i = 0; i < 2; i++) for (AccordClient c : cs) c.sync();
    }

    /** Canonical JSON of what a device reads, or "null" when it does not hold the record. */
    private static String read(AccordClient c, String record) {
        return c.read(record).map(Json::canonical).orElse("null");
    }

    private static String canonical(Object value) {
        return Json.canonical(Json.of(value));
    }

    @Test
    void twoAgentsEditOfflineThenConverge() {
        AccordClient awa = open("awa", "awa-phone");
        AccordClient moussa = open("moussa", "moussa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        awa.assign("dossier:1", "agent", "awa");
        syncAll(awa, moussa);
        awa.inc("dossier:1", "visits", 2);
        awa.add("dossier:1", "docs", "cni.pdf");
        moussa.inc("dossier:1", "visits", 3);
        moussa.assign("dossier:1", "client_name", "Aminata Fall");
        moussa.assign("dossier:1", "status", "rejected");
        awa.assign("dossier:1", "status", "approved");
        syncAll(awa, moussa);
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("agent", "awa");
        expected.put("zone", "dakar");
        expected.put("client_name", "Aminata Fall");
        expected.put("status", Map.of("conflicted", List.of(
                Map.of("value", "approved", "opId", "awa-phone:5"),
                Map.of("value", "rejected", "opId", "moussa-phone:3"))));
        expected.put("visits", 5);
        expected.put("docs", List.of("cni.pdf"));
        for (AccordClient c : List.of(awa, moussa)) {
            assertThat(read(c, "dossier:1")).isEqualTo(canonical(expected));
            assertThat(c.conflicts()).containsExactly(new ConflictInfo("dossier:1", "status", List.of(
                    new ConflictInfo.Value(Json.of("approved"), "awa-phone:5"),
                    new ConflictInfo.Value(Json.of("rejected"), "moussa-phone:3"))));
            assertThat(c.status().pending()).isZero();
        }
        moussa.resolve("dossier:1", "status", "approved");
        syncAll(awa, moussa);
        assertThat(awa.conflicts()).isEmpty();
        assertThat(snapshotOf(awa)).isEqualTo(snapshotOf(moussa));
    }

    @Test
    void refusedWriteIsRolledBackWithTheServerReason() {
        AccordClient fatou = open("fatou", "fatou-phone");
        AccordClient awa = open("awa", "awa-phone");
        fatou.assign("dossier:7", "zone", "thies");
        fatou.sync();
        List<Refusal> refusals = new ArrayList<>();
        awa.onRefused(refusals::add);
        awa.assign("dossier:7", "client_name", "not mine");
        awa.sync();
        assertThat(refusals).containsExactly(new Refusal(
                "awa-phone:1", "dossier:7", "client_name", "out of scope: you may not write dossier:7"));
        assertThat(awa.read("dossier:7")).isEmpty();
    }

    @Test
    void recordReassignedAwayLeavesTheDeviceAndReachesTheNewAgent() {
        AccordClient awa = open("awa", "awa-phone");
        AccordClient fatou = open("fatou", "fatou-phone");
        awa.assign("dossier:3", "agent", "awa");
        awa.inc("dossier:3", "visits", 4);
        awa.sync();
        awa.assign("dossier:3", "agent", "fatou");
        awa.sync();
        assertThat(awa.read("dossier:3")).isEmpty();
        fatou.sync();
        assertThat(read(fatou, "dossier:3"))
                .isEqualTo(canonical(Map.of("agent", "fatou", "visits", 4, "docs", List.of())));
    }

    @Test
    void followsAChangeOfReadScopesAsADelta() {
        AccordClient fatou = open("fatou", "fatou-phone");
        fatou.assign("dossier:8", "zone", "thies");
        fatou.sync();
        AccordClient awa = open("awa", "awa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        awa.sync();
        tokens.put("awa", tokenFor("awa", List.of("dakar", "thies")));
        awa.inc("dossier:8", "visits", 1);
        List<Boolean> resyncs = new ArrayList<>();
        awa.onResync(() -> resyncs.add(true));
        awa.sync();
        assertThat(resyncs).isEmpty();
        assertThat(awa.records()).containsExactly("dossier:1", "dossier:8");
        assertThat(read(awa, "dossier:8"))
                .isEqualTo(canonical(Map.of("zone", "thies", "visits", 1, "docs", List.of())));
    }

    @Test
    void afterCompactionANewDeviceGetsTheSnapshot(@TempDir Path tmp) {
        AccordClient awa = open("awa", "awa-phone");
        awa.assign("dossier:1", "zone", "dakar");
        for (int i = 0; i < 4; i++) awa.inc("dossier:1", "visits", 1);
        awa.add("dossier:1", "docs", "a.pdf");
        awa.remove("dossier:1", "docs", "a.pdf");
        awa.assign("dossier:1", "status", "submitted");
        syncAll(awa);
        assertThat(compactServer()).isEqualTo(1);

        Path path = tmp.resolve("tablet.db");
        JdbcStorage storage = JdbcStorage.sqlite(path);
        AccordClient tablet = open("awa", "awa-tablet", storage);
        tablet.inc("dossier:1", "visits", 10);
        tablet.sync();
        JsonObject before = awa.read("dossier:1").orElseThrow();
        Map<String, Object> expected = new LinkedHashMap<>(before.members());
        expected.put("visits", 14);
        assertThat(read(tablet, "dossier:1")).isEqualTo(canonical(expected));
        assertThat(storage.load().snapshots()).extracting(RecordSnapshot::record).containsExactly("dossier:1");
        awa.sync();
        assertThat(snapshotOf(awa)).isEqualTo(snapshotOf(tablet));

        // Restart from the same file: the snapshot, the ops on top and the cursor are all restored.
        String snapshot = snapshotOf(tablet);
        long cursor = tablet.status().cursor();
        tablet.close();
        opened.remove(tablet);
        AccordClient again = open("awa", "ignored-id", JdbcStorage.sqlite(path));
        assertThat(again.deviceId()).isEqualTo("awa-tablet");
        assertThat(snapshotOf(again)).isEqualTo(snapshot);
        assertThat(again.status().cursor()).isEqualTo(cursor);
        assertThat(again.status().pending()).isZero();
        again.sync();
        assertThat(snapshotOf(again)).isEqualTo(snapshot);
    }

    @Test
    void reinstalledDeviceNeverReusesAnOpIdAfterCompaction() {
        AccordClient before = open("awa", "awa-tablet");
        before.assign("dossier:1", "zone", "dakar");
        before.inc("dossier:1", "visits", 2);
        before.sync();
        before.sync();
        compactServer();
        AccordClient after = open("awa", "awa-tablet");
        after.sync();
        assertThat(after.inc("dossier:1", "visits", 3).opId()).isEqualTo("awa-tablet:3");
        after.sync();
        assertThat(after.status().pending()).isZero();
        assertThat(after.read("dossier:1").orElseThrow().get("visits")).isEqualTo(Json.of(5));
    }

    @Test
    void reinstalledDeviceWritingBeforeFirstSyncGetsOpIdAlreadyUsed() {
        AccordClient before = open("awa", "awa-old");
        before.assign("dossier:1", "zone", "dakar");
        before.sync();
        AccordClient after = open("awa", "awa-old");
        List<Refusal> refusals = new ArrayList<>();
        after.onRefused(refusals::add);
        after.inc("dossier:1", "visits", 1);
        after.sync();
        assertThat(refusals).hasSize(1);
        assertThat(refusals.get(0).reason()).contains("op id already used");
    }

    @Test
    void httpErrorsCarryTheStatus() {
        try (AccordClient c = AccordClient.open(AccordClient.options()
                .schema(SCHEMA)
                .storage(new MemoryStorage())
                .deviceId("nobody")
                .transport(new HttpTransport(SERVER_URL, () -> "not-a-jwt")))) {
            assertThatThrownBy(c::sync).isInstanceOfSatisfying(HttpException.class, e -> assertThat(e.status()).isEqualTo(401));
        }
    }
}
