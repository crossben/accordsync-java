package io.github.crossben.accordsync.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.RecordSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.sqlite.SQLiteDataSource;

/** The storage adapter contract (port of storage.test.ts / storage_contract.dart / test_storage.py). */
class StorageContractTest {
    @TempDir
    static Path dir;

    static int count;

    record Made(StorageAdapter store, Supplier<StorageAdapter> reopen) {}

    static Stream<String> kinds() {
        return Stream.of("memory", "sqlite-file", "sqlite-connection", "sqlite-datasource");
    }

    static Made make(String kind) {
        if (kind.equals("memory")) return new Made(new MemoryStorage(), null);
        Path path = dir.resolve("accord-" + (++count) + ".db");
        Supplier<StorageAdapter> reopen = () -> JdbcStorage.sqlite(path);
        return switch (kind) {
            case "sqlite-file" -> new Made(JdbcStorage.sqlite(path), reopen);
            case "sqlite-connection" -> {
                try {
                    yield new Made(JdbcStorage.of(DriverManager.getConnection("jdbc:sqlite:" + path)), reopen);
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
            default -> {
                SQLiteDataSource ds = new SQLiteDataSource();
                ds.setUrl("jdbc:sqlite:" + path);
                yield new Made(JdbcStorage.of(ds), reopen);
            }
        };
    }

    static JsonObject op(int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("op_id", "d:" + n);
        m.put("record", "dossier:1");
        m.put("field", "visits");
        m.put("kind", "inc");
        m.put("by", n);
        m.put("hlc", (1000 + n) + ":00000:d");
        return (JsonObject) Json.of(m);
    }

    static StoredMeta meta(long cursor) {
        return new StoredMeta("d", cursor, "1000:00000:d", 3);
    }

    static RecordSnapshot snap(int n) {
        return new RecordSnapshot("dossier:1", Map.of("visits", (JsonObject) Json.of(Map.of("strategy", "counter", "total", n))));
    }

    static List<String> ids(List<JsonObject> ops) {
        List<String> out = new ArrayList<>();
        for (JsonObject o : ops) out.add(((JsonString) o.get("op_id")).value());
        out.sort(String::compareTo);
        return out;
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void startsEmpty(String kind) {
        StorageSnapshot s = make(kind).store().load();
        assertThat(s.meta()).isEmpty();
        assertThat(s.snapshots()).isEmpty();
        assertThat(s.ops()).isEmpty();
        assertThat(s.outbox()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void commitsAndAppliesDeletesClearsAndOutboxRemovals(String kind) {
        StorageAdapter store = make(kind).store();
        store.commit(StorageTx.builder().putOps(List.of(op(1), op(2), op(3))).outboxAdd(List.of("d:1", "d:2")).meta(meta(5)).build());
        store.commit(StorageTx.builder().deleteOps(List.of("d:3")).outboxDelete(List.of("d:1")).meta(meta(7)).build());
        StorageSnapshot s = store.load();
        assertThat(ids(s.ops())).containsExactly("d:1", "d:2");
        assertThat(s.outbox()).containsExactly("d:2");
        assertThat(s.meta()).contains(meta(7));
        store.commit(StorageTx.builder().clearOps().putOps(List.of(op(9))).build());
        assertThat(store.load().ops()).containsExactly(op(9));
        assertThat(store.load().meta()).contains(meta(7)); // a commit without meta keeps it
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void storesSnapshotsReplacedByRecordClearedWithOps(String kind) {
        StorageAdapter store = make(kind).store();
        store.commit(StorageTx.builder().putSnapshots(List.of(snap(1))).build());
        store.commit(StorageTx.builder().putSnapshots(List.of(snap(2))).build());
        assertThat(store.load().snapshots()).containsExactly(snap(2));
        store.commit(StorageTx.builder().deleteSnapshots(List.of("dossier:1")).build());
        assertThat(store.load().snapshots()).isEmpty();
        store.commit(StorageTx.builder().putSnapshots(List.of(snap(3))).putOps(List.of(op(1))).build());
        store.commit(StorageTx.builder().clearOps().build());
        assertThat(store.load().snapshots()).isEmpty();
        assertThat(store.load().ops()).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void outboxAddIsIdempotent(String kind) {
        StorageAdapter store = make(kind).store();
        store.commit(StorageTx.builder().outboxAdd(List.of("d:1")).build());
        store.commit(StorageTx.builder().outboxAdd(List.of("d:1")).build());
        assertThat(store.load().outbox()).containsExactly("d:1");
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void returnsCopies(String kind) {
        StorageAdapter store = make(kind).store();
        store.commit(StorageTx.builder().putOps(List.of(op(1))).putSnapshots(List.of(snap(1))).build());
        StorageSnapshot loaded = store.load();
        assertThatThrownBy(() -> loaded.ops().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.ops().get(0).members().put("by", Json.of(99))).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.snapshots().get(0).fields().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> loaded.outbox().add("x")).isInstanceOf(UnsupportedOperationException.class);
        store.commit(StorageTx.builder().putOps(List.of(op(2))).build());
        assertThat(loaded.ops()).containsExactly(op(1)); // a later commit does not change an earlier load
        assertThat(ids(store.load().ops())).containsExactly("d:1", "d:2");
    }

    @ParameterizedTest
    @MethodSource("kinds")
    void survivesARestart(String kind) {
        Made made = make(kind);
        Assumptions.assumeTrue(made.reopen() != null, "in-memory storage does not survive a restart");
        made.store().commit(StorageTx.builder().putOps(List.of(op(1))).outboxAdd(List.of("d:1"))
                .putSnapshots(List.of(snap(4))).meta(meta(2)).build());
        made.store().close();
        StorageAdapter reopened = made.reopen().get();
        StorageSnapshot again = reopened.load();
        assertThat(again.meta()).contains(meta(2));
        assertThat(again.ops()).containsExactly(op(1));
        assertThat(again.outbox()).containsExactly("d:1");
        assertThat(again.snapshots()).containsExactly(snap(4));
        reopened.close();
    }

    @Test
    void sqliteCommitIsAtomic() throws Exception {
        Path path = dir.resolve("atomic.db");
        JdbcStorage store = JdbcStorage.sqlite(path);
        store.commit(StorageTx.builder().putOps(List.of(op(1))).meta(meta(1)).build());
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + path); Statement st = raw.createStatement()) {
            // the outbox insert fails after the op insert succeeded
            st.execute("create trigger boom before insert on accord_outbox begin select raise(abort, 'disk full'); end");
        }
        assertThatThrownBy(() -> store.commit(StorageTx.builder().putOps(List.of(op(2))).deleteOps(List.of("d:1"))
                .outboxAdd(List.of("d:2")).meta(meta(9)).build())).isInstanceOf(StorageException.class).hasMessageContaining("disk full");
        StorageSnapshot s = store.load();
        assertThat(s.ops()).containsExactly(op(1));
        assertThat(s.outbox()).isEmpty();
        assertThat(s.meta()).contains(meta(1));
        store.commit(StorageTx.builder().putOps(List.of(op(3))).build()); // the connection is usable again
        assertThat(ids(store.load().ops())).containsExactly("d:1", "d:3");
        store.close();
    }

    @Test
    void sqliteUsesTheTypeScriptTablesAndJson() throws Exception {
        Path path = dir.resolve("tables.db");
        JdbcStorage store = JdbcStorage.sqlite(path);
        store.commit(StorageTx.builder().putOps(List.of(op(1))).outboxAdd(List.of("d:1")).putSnapshots(List.of(snap(2))).meta(meta(4)).build());
        store.close();
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + path); Statement st = raw.createStatement()) {
            Set<String> tables = new HashSet<>();
            try (ResultSet rs = st.executeQuery("select name from sqlite_master where type = 'table'")) {
                while (rs.next()) tables.add(rs.getString(1));
            }
            assertThat(tables).containsExactlyInAnyOrder("accord_ops", "accord_outbox", "accord_snapshots", "accord_meta");
            assertThat(one(st, "select v from accord_meta where k = 'meta'"))
                    .isEqualTo("{\"deviceId\":\"d\",\"cursor\":4,\"hlc\":\"1000:00000:d\",\"seq\":3}");
            String body = one(st, "select body from accord_ops where op_id = 'd:1'");
            assertThat(Json.parse(body)).isEqualTo(op(1));
            assertThat(body).doesNotContain(" "); // compact, like JSON.stringify
            assertThat(Json.parse(one(st, "select body from accord_snapshots"))).isEqualTo(snap(2).toJson());
            assertThat(one(st, "select op_id from accord_outbox")).isEqualTo("d:1");
        }
    }

    @Test
    void sqliteReadsRowsWrittenByTheTypeScriptAdapter() throws Exception {
        Path path = dir.resolve("ts.db");
        try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + path); Statement st = raw.createStatement()) {
            st.execute("create table accord_ops (op_id text primary key, body text not null)");
            st.execute("create table accord_outbox (op_id text primary key)");
            st.execute("create table accord_snapshots (record text primary key, body text not null)");
            st.execute("create table accord_meta (k text primary key, v text not null)");
            st.execute("insert into accord_meta values ('meta', '{\"deviceId\":\"d\",\"cursor\":2,\"hlc\":\"1000:00000:d\",\"seq\":3}')");
            st.execute("insert into accord_ops values ('d:1', '" + Json.stringify(op(1)) + "')");
        }
        JdbcStorage store = JdbcStorage.sqlite(path);
        assertThat(store.load().meta()).contains(meta(2));
        assertThat(store.load().ops()).containsExactly(op(1));
        store.close();
    }

    @Test
    void sqliteStoresLoneSurrogates() {
        JdbcStorage store = JdbcStorage.sqlite(dir.resolve("lone.db"));
        Map<String, Object> m = new LinkedHashMap<>(op(1).members());
        m.remove("by");
        m.put("kind", "assign");
        m.put("value", "\ud800x");
        m.put("deps", List.of());
        JsonObject o = (JsonObject) Json.of(m);
        store.commit(StorageTx.builder().putOps(List.of(o)).build());
        assertThat(store.load().ops()).containsExactly(o);
        store.close();
    }

    @Test
    void sqliteStorageIsThreadSafe() throws Exception {
        JdbcStorage store = JdbcStorage.sqlite(dir.resolve("threads.db"));
        List<Thread> threads = new ArrayList<>();
        for (int k = 0; k < 4; k++) {
            int base = k * 100 + 1;
            threads.add(new Thread(() -> {
                for (int i = 0; i < 25; i++) {
                    store.commit(StorageTx.builder().putOps(List.of(op(base + i))).outboxAdd(List.of("d:" + (base + i))).build());
                }
            }));
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();
        assertThat(store.load().ops()).hasSize(100);
        assertThat(store.load().outbox()).hasSize(100);
        store.close();
    }

    @Test
    void callerOwnedConnectionIsNotClosed() throws Exception {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("owned.db"));
        JdbcStorage store = JdbcStorage.of(c);
        store.commit(StorageTx.builder().meta(meta(1)).build());
        store.close();
        assertThat(c.isClosed()).isFalse();
        c.close();
    }

    private static String one(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}
