package io.github.crossben.accordsync.server;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * The TypeScript server's migrations, as SQL, in its Kysely ledger (ADR-J02). Migrations are
 * append-only and identical to {@code app/packages/server/src/migrations/*.ts}: a database migrated
 * by either server is recognised by the other. Like Kysely's {@code Migrator}, the ledger tables are
 * created first, then pending migrations run in one transaction under Kysely's session advisory
 * lock, each recorded in {@code kysely_migration} with an ISO timestamp.
 */
public final class Migrations {
    /** Kysely's ledger table. */
    public static final String MIGRATION_TABLE = "kysely_migration";
    /** Kysely's lock table. */
    public static final String LOCK_TABLE = "kysely_migration_lock";
    /** Kysely's {@code PostgresAdapter} migration lock (a session-level advisory lock). */
    public static final long KYSELY_LOCK_ID = 3853314791062309107L;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private Migrations() {}

    private record Migration(String name, List<String> statements) {}

    private static final List<Migration> ALL = List.of(
            new Migration("0001_meta", List.of(
                    "create table \"accord_meta\" (\"key\" text primary key, \"value\" text not null)",
                    "insert into accord_meta (key, value) values ('schema_created_at', now()::text)")),
            new Migration("0002_sync", List.of(
                    "create table \"feed\" (\"seq\" bigserial primary key,"
                            + " \"kind\" text not null check (kind in ('op', 'scope')),"
                            + " \"record\" text not null, \"op_id\" text unique, \"op\" jsonb,"
                            + " \"scopes\" text[] not null, \"scopes_before\" text[],"
                            + " constraint \"feed_shape\" check ("
                            + "(kind = 'op' and op_id is not null and op is not null and scopes_before is null)\n"
                            + "       or (kind = 'scope' and op_id is null and op is null"
                            + " and scopes_before is not null)))",
                    "create index \"feed_record_seq\" on \"feed\" (\"record\", \"seq\")",
                    "create index \"feed_scopes\" on \"feed\" using gin (\"scopes\")",
                    "\n    create function accord_feed_guard() returns trigger language plpgsql as $$\n"
                            + "    begin\n"
                            + "      if tg_op = 'DELETE' and current_setting('accord.compaction', true) = 'on' then\n"
                            + "        return old;\n"
                            + "      end if;\n"
                            + "      raise exception 'accord: the feed is append-only (% refused)', tg_op;\n"
                            + "    end $$",
                    "\n    create trigger accord_feed_append_only before update or delete on feed\n"
                            + "    for each row execute function accord_feed_guard()",
                    "create table \"records\" (\"record\" text primary key, \"scopes\" text[] not null)",
                    "create table \"devices\" (\"device_id\" text primary key, \"sub\" text not null,"
                            + " \"read_keys\" text[], \"first_seen\" timestamptz default now() not null,"
                            + " \"last_seen\" timestamptz default now() not null)")),
            new Migration("0003_compaction", List.of(
                    "alter table feed drop constraint feed_kind_check",
                    "alter table feed drop constraint feed_shape",
                    "alter table feed add constraint feed_kind_check check (kind in ('op', 'scope', 'snapshot'))",
                    "alter table feed add constraint feed_shape check (\n"
                            + "       (kind = 'op' and op_id is not null and op is not null and scopes_before is null)\n"
                            + "    or (kind = 'scope' and op_id is null and op is null and scopes_before is not null)\n"
                            + "    or (kind = 'snapshot' and op_id is null and op is not null and scopes_before is null))",
                    "create table \"compacted_ops\" (\"op_id\" text primary key)",
                    "alter table \"devices\" add column \"cursor\" bigint default 0 not null,"
                            + " add column \"needs_resync\" boolean default false not null")),
            new Migration("0004_record_state", List.of("alter table \"records\" add column \"state\" jsonb")),
            new Migration("0005_concurrent_pushes", List.of(
                    "@offset",
                    "create function accord_pos() returns bigint language sql volatile as $$\n"
                            + "      select pg_current_xact_id()::text::bigint + accord_xid_offset() $$",
                    "create function accord_horizon() returns bigint language sql volatile as $$\n"
                            + "      select pg_snapshot_xmin(pg_current_snapshot())::text::bigint + accord_xid_offset() $$",
                    "alter table feed add column pos bigint",
                    "select set_config('accord.compaction', 'on', true)",
                    "alter table feed disable trigger accord_feed_append_only",
                    "update feed set pos = seq",
                    "alter table feed enable trigger accord_feed_append_only",
                    "alter table feed alter column pos set not null",
                    "alter table feed alter column pos set default accord_pos()",
                    "create index \"feed_pos_seq\" on \"feed\" (\"pos\", \"seq\")",
                    "update devices set cursor = 0, needs_resync = true",
                    "alter table devices add column push_floor bigint not null default 0",
                    "alter table devices add column max_op_seq bigint not null default 0",
                    "update devices d set max_op_seq = coalesce((select max(split_part(op_id, ':', 2)::bigint)\n"
                            + "      from feed f where f.kind = 'op' and split_part(f.op_id, ':', 1) = d.device_id), 0)",
                    "alter table compacted_ops add column device text",
                    "alter table compacted_ops add column op_seq bigint",
                    "update compacted_ops set device = split_part(op_id, ':', 1), op_seq = split_part(op_id, ':', 2)::bigint",
                    "alter table compacted_ops alter column device set not null",
                    "alter table compacted_ops alter column op_seq set not null",
                    "create index \"compacted_ops_device\" on \"compacted_ops\" (\"device\", \"op_seq\")")),
            new Migration("0006_compacted_op_hash", List.of("alter table \"compacted_ops\" add column \"op_hash\" text")),
            // A scope delta stays pending until the device shows it received it (ADR-0011, 2026-10-07).
            new Migration("0007_pending_scope_delta", List.of(
                    "alter table devices add column delta_keys text[], add column delta_cursor bigint")));

    /** @return every migration name, in order */
    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Migration m : ALL) out.add(m.name());
        return out;
    }

    /**
     * Runs pending migrations up to the latest.
     *
     * @param db the data source
     * @return the names run (empty when up to date)
     * @throws SQLException on a database error or a corrupted ledger
     */
    public static List<String> migrate(DataSource db) throws SQLException {
        return migrate(db, null);
    }

    /**
     * Runs pending migrations up to {@code target}.
     *
     * @param db the data source
     * @param target a migration name, or null for the latest
     * @return the names run (empty when up to date)
     * @throws SQLException on a database error or a corrupted ledger
     */
    public static List<String> migrate(DataSource db, String target) throws SQLException {
        try (Connection c = db.getConnection()) {
            return migrate(c, target);
        }
    }

    /**
     * Runs pending migrations up to {@code target} on a connection (left in autocommit mode).
     *
     * @param c the connection
     * @param target a migration name, or null for the latest
     * @return the names run
     * @throws SQLException on a database error or a corrupted ledger
     */
    public static List<String> migrate(Connection c, String target) throws SQLException {
        List<String> names = names();
        if (target != null && !names.contains(target)) throw new IllegalArgumentException("unknown migration " + target);
        c.setAutoCommit(true);
        try (Statement st = c.createStatement()) {
            st.execute("create table if not exists \"" + MIGRATION_TABLE + "\""
                    + " (\"name\" varchar(255) not null primary key, \"timestamp\" varchar(255) not null)");
            st.execute("create table if not exists \"" + LOCK_TABLE + "\""
                    + " (\"id\" varchar(255) not null primary key, \"is_locked\" integer default 0 not null)");
            st.execute("insert into \"" + LOCK_TABLE + "\" (\"id\", \"is_locked\") values ('migration_lock', 0)"
                    + " on conflict do nothing");
            st.execute("select pg_advisory_lock(" + KYSELY_LOCK_ID + ")");
        }
        try {
            List<String> ran = new ArrayList<>();
            c.setAutoCommit(false);
            boolean ok = false;
            try (Statement st = c.createStatement()) {
                List<String> done = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("select \"name\" from \"" + MIGRATION_TABLE + "\" order by \"timestamp\", \"name\"")) {
                    while (rs.next()) done.add(rs.getString(1));
                }
                for (String name : done) {
                    if (!names.contains(name)) {
                        throw new SQLException("corrupted migrations: previously executed migration " + name + " is missing");
                    }
                }
                if (!done.equals(names.subList(0, done.size()))) {
                    throw new SQLException("corrupted migrations: executed out of order: " + done);
                }
                int end = target == null ? names.size() : names.indexOf(target) + 1;
                for (Migration m : ALL.subList(Math.min(done.size(), end), end)) {
                    for (String sql : m.statements()) {
                        if (sql.equals("@offset")) {
                            long offset;
                            try (ResultSet rs = st.executeQuery("select coalesce(max(seq), 0) + 1 as next from feed")) {
                                rs.next();
                                offset = rs.getLong(1);
                            }
                            // A constant, so the column default and every query agree on it forever.
                            st.execute("create function accord_xid_offset() returns bigint language sql immutable as"
                                    + " $$ select " + offset + "::bigint $$");
                        } else {
                            st.execute(sql);
                        }
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "insert into \"" + MIGRATION_TABLE + "\" (\"name\", \"timestamp\") values (?, ?)")) {
                        ps.setString(1, m.name());
                        ps.setString(2, ISO.format(Instant.now()));
                        ps.executeUpdate();
                    }
                    ran.add(m.name());
                }
                c.commit();
                ok = true;
            } finally {
                if (!ok) c.rollback();
                c.setAutoCommit(true);
            }
            return ran;
        } finally {
            try (Statement st = c.createStatement()) {
                st.execute("select pg_advisory_unlock(" + KYSELY_LOCK_ID + ")");
            }
        }
    }
}
