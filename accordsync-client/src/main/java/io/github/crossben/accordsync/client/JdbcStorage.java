package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.RecordSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Durable storage in SQLite through JDBC, with the tables and SQL of the TypeScript
 * {@code SqliteStorage}. Tables are prefixed {@code accord_}, so it can share an app's database.
 * Every commit is one transaction (autocommit off, rolled back on any error); commits and loads
 * are serialised by this object's monitor. JSON bodies are compact, like {@code JSON.stringify}.
 *
 * <p>The {@code org.xerial:sqlite-jdbc} driver is an optional dependency: add it (or another
 * SQLite JDBC driver) to use {@link #sqlite(Path)}.
 */
public final class JdbcStorage implements StorageAdapter {
    private static final String[] SCHEMA = {
        "create table if not exists accord_ops (op_id text primary key, body text not null)",
        "create table if not exists accord_outbox (op_id text primary key)",
        "create table if not exists accord_snapshots (record text primary key, body text not null)",
        "create table if not exists accord_meta (k text primary key, v text not null)",
    };

    /** Where connections come from. */
    @FunctionalInterface
    public interface ConnectionSource {
        /**
         * A connection.
         *
         * @return the connection
         * @throws SQLException when none can be opened
         */
        Connection get() throws SQLException;
    }

    private final ConnectionSource source;
    private final boolean perOperation;
    private final boolean owned;
    private Connection held;
    private boolean ready;

    private JdbcStorage(ConnectionSource source, boolean perOperation, boolean owned) {
        this.source = source;
        this.perOperation = perOperation;
        this.owned = owned;
    }

    /**
     * Storage in a SQLite file, on one connection owned (and closed) by this storage.
     *
     * @param path the database file
     * @return the storage
     */
    public static JdbcStorage sqlite(Path path) {
        String url = "jdbc:sqlite:" + path.toAbsolutePath();
        return new JdbcStorage(() -> DriverManager.getConnection(url), false, true);
    }

    /**
     * Storage in a SQLite file.
     *
     * @param path the database file
     * @return the storage
     */
    public static JdbcStorage sqlite(String path) {
        return sqlite(Path.of(path));
    }

    /**
     * Storage on a connection the caller owns: it is used for every operation and never closed here.
     *
     * @param connection the connection
     * @return the storage
     */
    public static JdbcStorage of(Connection connection) {
        return new JdbcStorage(() -> connection, false, false);
    }

    /**
     * Storage borrowing a connection from {@code dataSource} for each load or commit (and closing
     * it, which returns it to a pool).
     *
     * @param dataSource the data source
     * @return the storage
     */
    public static JdbcStorage of(DataSource dataSource) {
        return new JdbcStorage(dataSource::getConnection, true, true);
    }

    /**
     * Storage borrowing a connection from {@code source} for each load or commit, closed afterwards.
     *
     * @param source the connection source
     * @return the storage
     */
    public static JdbcStorage of(ConnectionSource source) {
        return new JdbcStorage(source, true, true);
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private synchronized <T> T with(String what, Work<T> work) {
        Connection c = null;
        try {
            c = connection();
            init(c);
            return work.run(c);
        } catch (SQLException e) {
            throw new StorageException("accord storage: " + what + " failed: " + e.getMessage(), e);
        } finally {
            if (perOperation && c != null) {
                try {
                    c.close();
                } catch (SQLException ignored) {
                    // the work's outcome stands
                }
            }
        }
    }

    private Connection connection() throws SQLException {
        if (perOperation) return source.get();
        if (held == null) held = source.get();
        return held;
    }

    private void init(Connection c) throws SQLException {
        if (ready && !perOperation) return;
        boolean auto = c.getAutoCommit();
        try (Statement st = c.createStatement()) {
            for (String sql : SCHEMA) st.execute(sql);
        }
        if (!auto) c.commit();
        ready = true;
    }

    @Override
    public StorageSnapshot load() {
        return with("load", c -> {
            Optional<StoredMeta> meta = Optional.empty();
            for (String v : column(c, "select v from accord_meta where k = 'meta'")) {
                meta = Optional.of(StoredMeta.fromJson(Json.parse(v)));
            }
            List<RecordSnapshot> snaps = new ArrayList<>();
            for (String b : column(c, "select body from accord_snapshots")) snaps.add(RecordSnapshot.fromJson(Json.parse(b)));
            List<JsonObject> ops = new ArrayList<>();
            for (String b : column(c, "select body from accord_ops")) {
                if (!(Json.parse(b) instanceof JsonObject o)) throw new AccordException("stored op must be an object");
                ops.add(o);
            }
            List<String> outbox = column(c, "select op_id from accord_outbox");
            return new StorageSnapshot(meta, snaps, ops, outbox);
        });
    }

    private static List<String> column(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    @Override
    public void commit(StorageTx tx) {
        with("commit", c -> {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                if (tx.clearOps()) {
                    run(c, "delete from accord_ops");
                    run(c, "delete from accord_snapshots");
                }
                for (String r : tx.deleteSnapshots()) run(c, "delete from accord_snapshots where record = ?", r);
                for (RecordSnapshot s : tx.putSnapshots()) {
                    run(c, "insert or replace into accord_snapshots (record, body) values (?, ?)", s.record(),
                            Json.stringify(s.toJson()));
                }
                for (String id : tx.deleteOps()) run(c, "delete from accord_ops where op_id = ?", id);
                for (JsonObject op : tx.putOps()) {
                    run(c, "insert or replace into accord_ops (op_id, body) values (?, ?)", opId(op), Json.stringify(op));
                }
                for (String id : tx.outboxAdd()) run(c, "insert or ignore into accord_outbox (op_id) values (?)", id);
                for (String id : tx.outboxDelete()) run(c, "delete from accord_outbox where op_id = ?", id);
                if (tx.meta().isPresent()) {
                    run(c, "insert or replace into accord_meta (k, v) values ('meta', ?)",
                            Json.stringify(tx.meta().get().toJson()));
                }
                c.commit();
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException r) {
                    e.addSuppressed(r);
                }
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
            return null;
        });
    }

    private static void run(Connection c, String sql, String... params) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int k = 0; k < params.length; k++) st.setString(k + 1, params[k]);
            st.executeUpdate();
        }
    }

    static String opId(JsonObject op) {
        if (!(op.get("op_id") instanceof JsonString s)) throw new AccordException("stored op needs an op_id");
        return s.value();
    }

    @Override
    public synchronized void close() {
        if (held != null && owned) {
            try {
                held.close();
            } catch (SQLException e) {
                throw new StorageException("accord storage: close failed: " + e.getMessage(), e);
            }
        }
        held = null;
    }
}
