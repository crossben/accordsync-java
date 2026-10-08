package io.github.crossben.accordsync.server;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * ADR-J02: same schema and same Kysely ledger as the TypeScript server, in both directions. Needs
 * ACCORD_TEST_DATABASE_URL; the TypeScript half also needs ACCORD_APP_DIR (an installed Accord
 * workspace). Skipped otherwise.
 */
class LedgerInteropTest {
    static final String ADMIN = System.getenv("ACCORD_TEST_DATABASE_URL");
    static final List<String> created = new ArrayList<>();

    static String fresh() throws SQLException {
        assumeTrue(ADMIN != null && !ADMIN.isEmpty(), "ACCORD_TEST_DATABASE_URL is not set");
        String name = "accord_jledger_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = Databases.fromUrl(ADMIN).getConnection(); Statement st = c.createStatement()) {
            st.execute("create database " + name);
        }
        created.add(name);
        return name;
    }

    @AfterAll
    static void drop() throws SQLException {
        for (String name : created) {
            try (Connection c = Databases.fromUrl(ADMIN).getConnection(); Statement st = c.createStatement()) {
                st.execute("drop database if exists " + name + " with (force)");
            }
        }
    }

    /** postgres://... URL of database {@code name} on the admin server. */
    static String url(String name) {
        int slash = ADMIN.indexOf('/', ADMIN.indexOf("//") + 2);
        int q = ADMIN.indexOf('?', slash);
        return ADMIN.substring(0, slash + 1) + name + (q < 0 ? "" : ADMIN.substring(q));
    }

    static PGSimpleDataSource ds(String name) {
        return Databases.fromUrl(url(name));
    }

    static List<String> rows(String name, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = ds(name).getConnection(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                StringBuilder row = new StringBuilder();
                for (int i = 1; i <= n; i++) row.append(i > 1 ? "|" : "").append(rs.getString(i));
                out.add(row.toString());
            }
        }
        return out;
    }

    /** Tables, columns, constraints, indexes, functions and triggers. */
    static List<String> schema(String name) throws SQLException {
        List<String> out = new ArrayList<>();
        out.addAll(rows(name, "select table_name, column_name, data_type, is_nullable, coalesce(column_default, '')"
                + " from information_schema.columns where table_schema = 'public' order by 1, 2"));
        out.addAll(rows(name, "select conrelid::regclass::text, conname, pg_get_constraintdef(oid) from pg_constraint"
                + " where connamespace = 'public'::regnamespace order by 1, 2"));
        out.addAll(rows(name, "select indexname, indexdef from pg_indexes where schemaname = 'public' order by 1"));
        out.addAll(rows(name, "select proname, regexp_replace(pg_get_functiondef(oid), 'select \\d+::bigint', 'select N::bigint')"
                + " from pg_proc where pronamespace = 'public'::regnamespace order by 1"));
        out.addAll(rows(name, "select tgname, pg_get_triggerdef(oid) from pg_trigger where not tgisinternal order by 1"));
        return out;
    }

    static void tsMigrate(String name, String target) throws Exception {
        String app = System.getenv("ACCORD_APP_DIR");
        assumeTrue(app != null && Files.isDirectory(Path.of(app, "packages/server/node_modules")),
                "ACCORD_APP_DIR (an installed Accord workspace) is not set");
        Path script = Path.of(System.getProperty("accord.contract", "../contract")).getParent().resolve("tools/ts-migrate.mts");
        Process p = new ProcessBuilder("node", "--import", "tsx", "--conditions=@accordsync/source",
                script.toAbsolutePath().toString(), url(name), target == null ? "" : target)
                .directory(new File(app, "packages/server")).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(out).isZero();
    }

    @Test
    void upgradesStepByStepKeepingDataLikeTheTypeScriptMigration() throws Exception {
        String db = fresh();
        assertThat(Migrations.migrate(ds(db), "0004_record_state"))
                .containsExactly("0001_meta", "0002_sync", "0003_compaction", "0004_record_state");
        try (Connection c = ds(db).getConnection(); Statement st = c.createStatement()) {
            st.execute("insert into feed (kind, record, op_id, op, scopes) values ('op', 'dossier:1', 'dev:4', '{}', '{a}'),"
                    + " ('op', 'dossier:1', 'dev:9', '{}', '{a}')");
            st.execute("insert into devices (device_id, sub) values ('dev', 'alice')");
            st.execute("insert into compacted_ops (op_id) values ('dev:2')");
        }
        assertThat(Migrations.migrate(ds(db)))
                .containsExactly("0005_concurrent_pushes", "0006_compacted_op_hash", "0007_pending_scope_delta");
        assertThat(rows(db, "select accord_xid_offset()")).containsExactly("3");
        assertThat(rows(db, "select pos from feed order by seq")).containsExactly("1", "2");
        assertThat(rows(db, "select max_op_seq, needs_resync, cursor from devices")).containsExactly("9|t|0");
        assertThat(rows(db, "select device, op_seq, op_hash from compacted_ops")).containsExactly("dev|2|null");
    }

    @Test
    void sameSchemaAsTheTypeScriptServerInBothDirections() throws Exception {
        String ts = fresh();
        String java = fresh();
        String mixed = fresh();
        tsMigrate(ts, null);
        Migrations.migrate(ds(java));

        // TypeScript then Java: nothing left to do, same ledger.
        assertThat(Migrations.migrate(ds(ts))).isEmpty();
        assertThat(rows(ts, "select name from kysely_migration order by name")).isEqualTo(Migrations.names());
        // Java then TypeScript: Kysely accepts the ledger and finds nothing to do.
        List<String> before = rows(java, "select name, timestamp from kysely_migration order by name");
        tsMigrate(java, null);
        assertThat(rows(java, "select name, timestamp from kysely_migration order by name")).isEqualTo(before);
        // Half and half: TypeScript up to 0005, Java the rest, TypeScript again.
        tsMigrate(mixed, "0005_concurrent_pushes");
        assertThat(Migrations.migrate(ds(mixed))).containsExactly("0006_compacted_op_hash", "0007_pending_scope_delta");
        tsMigrate(mixed, null);

        assertThat(schema(java)).isEqualTo(schema(ts));
        assertThat(schema(mixed)).isEqualTo(schema(ts));
    }
}
