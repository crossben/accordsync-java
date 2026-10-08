package io.github.crossben.accordsync.server;

import static io.github.crossben.accordsync.server.Sql.arr;

import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonBool;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.Op;
import io.github.crossben.accordsync.core.OpHash;
import io.github.crossben.accordsync.core.OpId;
import io.github.crossben.accordsync.core.RecordSnapshot;
import io.github.crossben.accordsync.core.Replica;
import io.github.crossben.accordsync.core.Wire;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/** Push, pull and compaction, ported line by line from {@code sync.ts} and {@code compact.ts} (ADR-0010). */
final class Sync {
    /** Pushes take this advisory lock shared, compaction exclusive (ADR-0010). */
    static final long FEED_LOCK = 0x4acc0dL;

    private final DataSource db;
    private final ServerDefinition def;
    private final LongSupplier now;
    private final Semaphore slots;

    Sync(DataSource db, ServerDefinition def, LongSupplier now) {
        this.db = db;
        this.def = def;
        this.now = now;
        this.slots = new Semaphore(def.limits().maxConcurrentPushes(), true);
    }

    /** Who is calling: verified user, their device, and the scope keys their claims grant. */
    record Caller(String sub, String deviceId, List<String> read, List<String> write) {}

    /** A transaction body. */
    interface Tx<T> {
        T run(Connection c) throws SQLException;
    }

    static <T> T inTransaction(DataSource db, String isolation, Tx<T> body) throws SQLException {
        try (Connection c = db.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            boolean ok = false;
            try {
                if (isolation != null) Sql.update(c, "set transaction isolation level " + isolation);
                T out = body.run(c);
                c.commit();
                ok = true;
                return out;
            } finally {
                if (!ok) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                        // the connection is broken; the pool discards it
                    }
                }
                c.setAutoCommit(auto);
            }
        }
    }

    // ------------------------------------------------------------------ devices

    /**
     * Registers the device to this user on first sight; refuses a device id owned by someone else.
     * A device seen again after the retirement TTL is flagged: its next pull starts at zero.
     */
    void touchDevice(Caller caller) throws SQLException {
        try (Connection c = db.getConnection()) {
            Object[] row = Sql.one(c,
                    "insert into devices (device_id, sub, read_keys) values (?, ?, null)"
                            + " on conflict (device_id) do update set last_seen = now(),"
                            + " needs_resync = devices.needs_resync"
                            + " or devices.last_seen < now() - make_interval(secs => ?)"
                            + " where devices.sub = ? returning sub",
                    caller.deviceId(), caller.sub(), def.deviceTtlMs() / 1000, caller.sub());
            if (row == null) throw AccordHttpException.forbidden("device " + caller.deviceId() + " belongs to another user");
        }
    }

    // ------------------------------------------------------------------ push

    JsonObject push(Caller caller, List<JsonValue> raw) throws SQLException, InterruptedException {
        int maxOps = def.limits().maxPushOps();
        if (raw.size() > maxOps) throw AccordHttpException.badRequest("at most " + maxOps + " ops per push");

        List<String> acked = new ArrayList<>();
        List<JsonValue> refused = new ArrayList<>();
        Map<String, List<Op>> byRecord = new HashMap<>();

        for (JsonValue item : raw) {
            Op op;
            try {
                op = Wire.decode(item);
                // PostgreSQL cannot store a lone surrogate (jsonb refuses it, text replaces it):
                // refuse the op instead of failing the whole push.
                String bad = lonePath(item, "op");
                if (bad != null) throw new AccordException("lone surrogate in " + bad);
            } catch (RuntimeException e) {
                JsonValue id = item instanceof JsonObject o ? o.get("op_id") : null;
                if (!(id instanceof JsonString s)) throw AccordHttpException.badRequest("malformed op: " + e.getMessage());
                refused.add(refusal(s.value(), "malformed op: " + e.getMessage()));
                continue;
            }
            if (!op.hlc().node().equals(caller.deviceId())) {
                refused.add(refusal(op.opId(), "op belongs to device " + op.hlc().node()));
                continue;
            }
            byRecord.computeIfAbsent(op.record(), k -> new ArrayList<>()).add(op);
        }

        slots.acquire();
        try {
            inTransaction(db, null, c -> {
                pushTx(c, caller, raw, byRecord, acked, refused);
                return null;
            });
        } finally {
            slots.release();
        }
        Map<String, JsonValue> out = new LinkedHashMap<>();
        List<JsonValue> ackedJson = new ArrayList<>();
        for (String a : acked) ackedJson.add(new JsonString(a));
        out.put("acked", new JsonArray(ackedJson));
        out.put("refused", new JsonArray(refused));
        return new JsonObject(out);
    }

    private static JsonObject refusal(String opId, String reason) {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("op_id", new JsonString(opId));
        m.put("reason", new JsonString(reason));
        return new JsonObject(m);
    }

    private void pushTx(Connection c, Caller caller, List<JsonValue> raw, Map<String, List<Op>> byRecord,
            List<String> acked, List<JsonValue> refused) throws SQLException {
        long maxSkewMs = def.limits().maxSkewMs();
        Sql.query(c, "select pg_advisory_xact_lock_shared(?)", FEED_LOCK);
        // Op numbers this device already used (read once: within a batch, ops apply out of order).
        Object[] dev = Sql.one(c, "select max_op_seq from devices where device_id = ?", caller.deviceId());
        long usedUpTo = dev == null ? 0 : Sql.num(dev[0]);
        long maxApplied = 0;
        long nowMs = now.getAsLong();
        List<String> records = new ArrayList<>(byRecord.keySet());
        records.sort(String::compareTo);

        // Create missing record rows, so every record can be locked; a row created here and left
        // unused (every op refused) is removed again below.
        Set<String> created = new HashSet<>();
        if (!records.isEmpty()) {
            StringBuilder sql = new StringBuilder("insert into records (record, scopes, state) values ");
            for (int i = 0; i < records.size(); i++) sql.append(i == 0 ? "" : ", ").append("(?, '{}'::text[], null)");
            sql.append(" on conflict (record) do nothing returning record");
            for (Object[] r : Sql.query(c, sql.toString(), records.toArray())) created.add((String) r[0]);
        }
        Map<String, Object[]> locked = new HashMap<>();
        for (Object[] r : Sql.query(c,
                "select record, scopes, state from records where record = any(?::text[]) order by record for update",
                arr(records.isEmpty() ? List.of("") : records))) {
            locked.put((String) r[0], r);
        }

        for (String record : records) {
            boolean isNew = created.contains(record);
            Object[] existing = locked.get(record);
            Replica replica;
            if (existing[2] != null) {
                replica = new Replica(def.schema());
                replica.loadSnapshot(RecordSnapshot.fromJson(Json.parse((String) existing[2])));
            } else {
                // A new record, or one written before migration 0004: rebuild from the feed.
                replica = loadRecord(c, def, record);
            }
            List<Op> pending = byRecord.get(record);
            List<String> ids = new ArrayList<>();
            for (Op o : pending) ids.add(o.opId());
            // Already applied (in the feed) or folded by compaction: acknowledge, never apply twice.
            // An id already in the feed is a retry only if it is the same op.
            Map<String, String> stored = new HashMap<>();
            for (Object[] r : Sql.query(c, "select op_id, op from feed where op_id = any(?::text[])", arr(ids))) {
                stored.put((String) r[0], Json.canonical(Json.parse((String) r[1])));
            }
            Map<String, String> compacted = new HashMap<>();
            for (Object[] r : Sql.query(c, "select op_id, op_hash from compacted_ops where op_id = any(?::text[])", arr(ids))) {
                compacted.put((String) r[0], (String) r[1]);
            }
            List<String> scopes = isNew ? null : List.of(Sql.strings(existing[1]));
            boolean changed = false;
            List<Object[]> rows = new ArrayList<>();

            for (Op op : pending) {
                JsonObject wire = Wire.encode(op);
                String previous = stored.get(op.opId());
                if (previous != null && !previous.equals(Json.canonical(wire))) {
                    refused.add(refusal(op.opId(), "op id already used: " + op.opId() + " names another op"));
                    continue;
                }
                String folded = compacted.get(op.opId());
                if (folded != null && !folded.equals(OpHash.of(op))) {
                    refused.add(refusal(op.opId(), "op id already used: " + op.opId() + " names another op"));
                    continue;
                }
                if (previous != null || compacted.containsKey(op.opId()) || replica.has(op.opId())) {
                    acked.add(op.opId()); // a retried push: already applied
                    continue;
                }
                long opSeq = OpId.parse(op.opId()).seq();
                if (opSeq <= usedUpTo) {
                    // Not a retry (that would be in the feed): the device reused an op id.
                    refused.add(refusal(op.opId(),
                            "op id already used: this device's ops are numbered above " + usedUpTo));
                    continue;
                }
                String reason = check(caller, replica, scopes, op, nowMs, maxSkewMs);
                if (reason != null) {
                    refused.add(refusal(op.opId(), reason));
                    continue;
                }
                replica.apply(op);
                List<String> next;
                try {
                    next = scopesOf(def, replica, record);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("scope function failed for " + record + ": " + e.getMessage(), e);
                }
                // A scope change goes in before the op that caused it: a device the record is entering
                // receives the history first, then this op on top.
                if (scopes == null || !sameKeys(scopes, next)) {
                    rows.add(new Object[] {"scope", null, null, next, scopes == null ? List.of() : scopes});
                }
                rows.add(new Object[] {"op", op.opId(), Json.canonical(wire), next, null});
                scopes = next;
                changed = true;
                acked.add(op.opId());
                maxApplied = Math.max(maxApplied, opSeq);
            }
            if (changed) {
                // One insert per record: rows keep this order (same transaction, increasing seq).
                StringBuilder sql = new StringBuilder("insert into feed (kind, record, op_id, op, scopes, scopes_before) values ");
                List<Object> params = new ArrayList<>();
                for (int i = 0; i < rows.size(); i++) {
                    Object[] r = rows.get(i);
                    sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?::jsonb, ?::text[], ?::text[])");
                    params.add(r[0]);
                    params.add(record);
                    params.add(r[1]);
                    params.add(r[2]);
                    params.add(arr(castList(r[3])));
                    params.add(r[4] == null ? new Sql.Null(Types.ARRAY) : arr(castList(r[4])));
                }
                Sql.update(c, sql.toString(), params.toArray());
                Sql.update(c, "update records set scopes = ?::text[], state = ?::jsonb where record = ?",
                        arr(scopes), Json.canonical(replica.snapshotRecord(record).toJson()), record);
            } else if (isNew) {
                Sql.update(c, "delete from records where record = ?", record);
            }
        }

        // The device has its answers for every op below the first one it sent now (clients push their
        // outbox in order): compacted-op entries below that can be forgotten.
        String prefix = caller.deviceId() + ":";
        Long min = null;
        for (JsonValue item : raw) {
            if (item instanceof JsonObject o && o.get("op_id") instanceof JsonString s && s.value().startsWith(prefix)) {
                Long n = JsNumber.safeInteger(s.value().substring(prefix.length()));
                if (n != null && (min == null || n < min)) min = n;
            }
        }
        if (min != null) {
            Sql.update(c, "update devices set push_floor = greatest(push_floor, ?::bigint),"
                    + " max_op_seq = greatest(max_op_seq, ?::bigint) where device_id = ?",
                    min, maxApplied, caller.deviceId());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> castList(Object o) {
        return (List<String>) o;
    }

    /** Why {@code op} must be refused, or null to accept it. */
    private String check(Caller caller, Replica replica, List<String> scopes, Op op, long nowMs, long maxSkewMs) {
        try {
            replica.validate(op);
        } catch (AccordException e) {
            return e.getMessage();
        }
        long ahead = op.hlc().wall() - nowMs;
        if (ahead > maxSkewMs) return "clock is " + ahead + " ms ahead of the server (limit " + maxSkewMs + " ms)";
        // An existing record: the caller must be allowed to write where it is now. A new record: where
        // the write puts it.
        List<String> where = scopes;
        if (where == null) {
            Replica fresh = new Replica(def.schema());
            fresh.apply(op);
            try {
                where = scopesOf(def, fresh, op.record());
            } catch (RuntimeException e) {
                return "scope function failed: " + e.getMessage();
            }
        }
        if (!overlaps(where, caller.write())) return "out of scope: you may not write " + op.record();
        return null;
    }

    // ------------------------------------------------------------------ pull

    JsonObject pull(Caller caller, long cursor, long requested) throws SQLException {
        long limit = Math.max(1, Math.min(requested, def.limits().maxPullLimit()));
        List<String> read = normalize(caller.read());
        // Two pulls from one device at once can collide on its `devices` row under repeatable read
        // (40001). The pull only reads the feed, so running it again is always safe.
        for (int attempt = 1; ; attempt++) {
            try {
                return inTransaction(db, "repeatable read", c -> pullOnce(c, caller, cursor, limit, read));
            } catch (SQLException e) {
                if (!"40001".equals(e.getSQLState()) || attempt >= 20) throw e;
                try {
                    Thread.sleep((long) (ThreadLocalRandom.current().nextDouble() * 10 * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static final String VISIBLE = "((kind in ('op', 'snapshot') and scopes && ?::text[])"
            + " or (kind = 'scope' and (scopes_before && ?::text[]) <> (scopes && ?::text[])))";
    private static final String COLS = "select seq, pos, kind, record, op, scopes, scopes_before from feed";

    private JsonObject pullOnce(Connection c, Caller caller, long cursor, long limit, List<String> read) throws SQLException {
        Object[] device = Sql.one(c,
                "select read_keys, needs_resync, max_op_seq, delta_keys, delta_cursor from devices where device_id = ?",
                caller.deviceId());
        if (device == null) throw new IllegalStateException("no device row for " + caller.deviceId());
        if (cursor > 0 && (Boolean) device[1]) return resyncRequired();
        // Read scopes changed (new claims): send what entered and what left, instead of everything.
        // A delta stays pending until the device pulls from a cursor above the one it was sent from
        // (it then has the answer). A pull at or below that cursor is a retry of a lost answer: the
        // delta is computed again from the keys the device had before it (ADR-0011, 2026-10-07).
        boolean hasPending = device[3] != null && device[4] != null;
        List<String> pendingKeys = hasPending ? List.of(Sql.strings(device[3])) : null;
        long pendingCursor = hasPending ? Sql.num(device[4]) : 0;
        boolean retry = cursor > 0 && hasPending && cursor <= pendingCursor;
        List<String> before = retry ? pendingKeys : device[0] == null ? List.of() : List.of(Sql.strings(device[0]));
        boolean keysChanged = cursor > 0 && !sameKeys(before, read);
        Delta delta = null;
        if (keysChanged) {
            delta = scopeDelta(c, cursor, before, read, def.limits().maxScopeDelta());
            if (delta == null) return resyncRequired();
        }
        // The device has applied everything up to `cursor`: compaction may fold ops below it.
        if (cursor == 0) {
            Sql.update(c, "update devices set read_keys = ?::text[], needs_resync = false, cursor = 0,"
                    + " delta_keys = null, delta_cursor = null where device_id = ?", arr(read), caller.deviceId());
        } else if (keysChanged) {
            Sql.update(c, "update devices set cursor = greatest(cursor, ?::bigint), read_keys = ?::text[],"
                    + " delta_keys = ?::text[], delta_cursor = ?::bigint where device_id = ?",
                    cursor, arr(read), arr(before), retry ? pendingCursor : cursor, caller.deviceId());
        } else if (hasPending) {
            Sql.update(c, "update devices set cursor = greatest(cursor, ?::bigint), read_keys = ?::text[],"
                    + " delta_keys = null, delta_cursor = null where device_id = ?", cursor, arr(read), caller.deviceId());
        } else {
            Sql.update(c, "update devices set cursor = greatest(cursor, ?::bigint) where device_id = ?",
                    cursor, caller.deviceId());
        }

        // Rows from transactions older than every transaction still running are final (ADR-0010).
        long horizon = Sql.num(Sql.one(c, "select accord_horizon()")[0]);
        Sql.TextArray r = arr(read);
        List<Object[]> fetched = Sql.query(c, COLS + " where pos > ? and pos < ? and " + VISIBLE
                + " order by pos, seq limit ?", cursor, horizon, r, r, r, limit + 1);

        // A page ends on a transaction boundary: the cursor is a transaction position. A transaction
        // bigger than a page is sent whole.
        List<Object[]> rows = fetched;
        boolean full = false;
        if (fetched.size() > limit) {
            full = true;
            long lastPos = Sql.num(fetched.get((int) limit)[1]);
            rows = new ArrayList<>();
            for (Object[] row : fetched.subList(0, (int) limit)) if (Sql.num(row[1]) != lastPos) rows.add(row);
            if (rows.isEmpty()) {
                rows = Sql.query(c, COLS + " where pos = ? and " + VISIBLE + " order by seq", lastPos, r, r, r);
            }
        }

        List<JsonValue> items = new ArrayList<>();
        Set<String> sent = new HashSet<>();
        if (delta != null) {
            for (Object[] h : delta.history) send(items, sent, (String) h[0], (String) h[1]);
            for (String record : delta.exits) items.add(exit(record));
        }
        for (Object[] row : rows) {
            String kind = (String) row[2];
            if (kind.equals("op") || kind.equals("snapshot")) {
                send(items, sent, kind, (String) row[4]);
                continue;
            }
            if (overlaps(List.of(Sql.strings(row[5])), read)) {
                // The record entered the caller's scope: send its whole history up to this point.
                for (Object[] h : Sql.query(c, "select kind, op from feed where record = ? and kind in ('op', 'snapshot')"
                        + " and (pos, seq) < (?::bigint, ?::bigint) order by pos, seq",
                        row[3], Sql.num(row[1]), Sql.num(row[0]))) {
                    send(items, sent, (String) h[0], (String) h[1]);
                }
            } else {
                items.add(exit((String) row[3]));
            }
        }

        long next = full ? Sql.num(rows.get(rows.size() - 1)[1]) : horizon - 1;
        Map<String, JsonValue> out = new LinkedHashMap<>();
        out.put("items", new JsonArray(items));
        out.put("cursor", JsonNumber.of(Math.max(cursor, next)));
        out.put("has_more", JsonBool.of(full));
        out.put("device_seq", JsonNumber.of(Sql.num(device[2])));
        return new JsonObject(out);
    }

    private static JsonObject resyncRequired() {
        return new JsonObject(Map.of("resync_required", JsonBool.of(true)));
    }

    private static JsonObject exit(String record) {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("type", new JsonString("exit"));
        m.put("record", new JsonString(record));
        return new JsonObject(m);
    }

    private static void send(List<JsonValue> items, Set<String> sent, String kind, String opJson) {
        JsonValue op = Json.parse(opJson);
        Map<String, JsonValue> m = new LinkedHashMap<>();
        if (kind.equals("snapshot")) {
            m.put("type", new JsonString("snapshot"));
            m.put("snapshot", op);
            items.add(new JsonObject(m));
            return;
        }
        String id = ((JsonString) ((JsonObject) op).get("op_id")).value();
        if (!sent.add(id)) return;
        m.put("type", new JsonString("op"));
        m.put("op", op);
        items.add(new JsonObject(m));
    }

    private record Delta(List<Object[]> history, List<String> exits) {}

    /**
     * What a change of read keys means for a device at {@code cursor}: the history of every record
     * visible now (under {@code after}) that it did not have, and an exit for every record it had
     * that it may no longer see. "Had" is judged at the cursor: a record's scopes as of the cursor
     * (the {@code scopes_before} of its first scope row above the cursor, or its current scopes if
     * none) against the keys the device had ({@code before}). The feed from the cursor is then read
     * with the new keys, so later moves come from its scope rows (ADR-0011, 2026-10-07 b); the history
     * sent here stops at the cursor, the feed carries the rest (a record that left since leaks
     * nothing written after it left). Null when
     * more than {@code max} records change (a full resync is cheaper then).
     */
    private static Delta scopeDelta(Connection c, long cursor, List<String> before, List<String> after, int max)
            throws SQLException {
        Set<String> both = new LinkedHashSet<>(before);
        both.addAll(after);
        List<Object[]> rows = Sql.query(c,
                "with moved as ("
                        + " select distinct on (record) record, scopes_before as scopes from feed"
                        + " where kind = 'scope' and pos > ? order by record, pos, seq"
                        + "), at_cursor as ("
                        + " select r.record, r.scopes from records r where r.scopes && ?::text[]"
                        + " and not exists (select 1 from moved m where m.record = r.record)"
                        + " union all select m.record, m.scopes from moved m"
                        + ") select record, scopes && ?::text[] as entering from at_cursor"
                        + " where (scopes && ?::text[]) <> (scopes && ?::text[]) order by record limit ?",
                cursor, arr(new ArrayList<>(both)), arr(after), arr(after), arr(before), max + 1);
        if (rows.size() > max) return null;
        List<String> names = new ArrayList<>();
        List<String> exits = new ArrayList<>();
        for (Object[] r : rows) (Boolean.TRUE.equals(r[1]) ? names : exits).add((String) r[0]);
        List<Object[]> history = List.of();
        if (!names.isEmpty()) {
            history = Sql.query(c, "select kind, op from feed where record = any(?::text[])"
                    + " and kind in ('op', 'snapshot') and pos <= ? order by record, pos, seq", arr(names), cursor);
        }
        return new Delta(history, exits);
    }

    // ------------------------------------------------------------------ compaction

    JsonObject compact() throws SQLException {
        int minOps = def.compaction().minOps();
        double ttlSecs = def.deviceTtlMs() / 1000;
        return inTransaction(db, null, c -> {
            // Exclusive: waits for running pushes (they hold the lock shared) and holds new ones back.
            Sql.query(c, "select pg_advisory_xact_lock(?)", FEED_LOCK);
            Sql.query(c, "select set_config('accord.compaction', 'on', true)");
            long watermark = Sql.num(Sql.one(c, "select coalesce((select min(cursor) from devices"
                    + " where last_seen > now() - make_interval(secs => ?)), accord_horizon() - 1) as w", ttlSecs)[0]);
            List<Object[]> candidates = Sql.query(c, "select record, max(pos) as last from feed where kind in ('op', 'snapshot')"
                    + " group by record having max(pos) <= ? and count(*) filter (where kind = 'op') >= ?",
                    watermark, minOps);
            long opsFolded = 0;
            for (Object[] cand : candidates) {
                String record = (String) cand[0];
                long last = Sql.num(cand[1]);
                Replica replica = loadRecord(c, def, record);
                List<Op> ops = replica.ops();
                Object[] row = Sql.one(c, "select scopes from records where record = ?", record);
                if (row == null) throw new IllegalStateException("no records row for " + record);
                if (!ops.isEmpty()) {
                    StringBuilder sql = new StringBuilder("insert into compacted_ops (op_id, device, op_seq, op_hash) values ");
                    List<Object> params = new ArrayList<>();
                    for (int i = 0; i < ops.size(); i++) {
                        Op op = ops.get(i);
                        OpId id = OpId.parse(op.opId());
                        sql.append(i == 0 ? "" : ", ").append("(?, ?, ?, ?)");
                        params.add(op.opId());
                        params.add(id.device());
                        params.add(id.seq());
                        params.add(OpHash.of(op));
                    }
                    sql.append(" on conflict do nothing");
                    Sql.update(c, sql.toString(), params.toArray());
                }
                Sql.update(c, "delete from feed where record = ? and pos <= ?", record, last);
                Sql.update(c, "insert into feed (pos, kind, record, op, scopes) values (?, 'snapshot', ?, ?::jsonb, ?::text[])",
                        last, record, Json.canonical(replica.snapshotRecord(record).toJson()),
                        arr(List.of(Sql.strings(row[0]))));
                opsFolded += ops.size();
            }
            // Entries a device can no longer retry (it has pushed past them) are no longer needed.
            int pruned = Sql.update(c, "delete from compacted_ops c using devices d"
                    + " where c.device = d.device_id and c.op_seq < d.push_floor");
            Map<String, JsonValue> out = new LinkedHashMap<>();
            out.put("watermark", JsonNumber.of(watermark));
            out.put("records", JsonNumber.of(candidates.size()));
            out.put("opsFolded", JsonNumber.of(opsFolded));
            out.put("tombstonesPruned", JsonNumber.of(Math.max(pruned, 0)));
            return new JsonObject(out);
        });
    }

    // ------------------------------------------------------------------ helpers

    /** A record's state on the server: its latest snapshot, then the ops after it. */
    static Replica loadRecord(Connection c, ServerDefinition def, String record) throws SQLException {
        Replica replica = new Replica(def.schema());
        for (Object[] r : Sql.query(c, "select kind, op from feed where record = ? and kind in ('op', 'snapshot')"
                + " order by pos, seq", record)) {
            JsonValue v = Json.parse((String) r[1]);
            if (r[0].equals("snapshot")) replica.loadSnapshot(RecordSnapshot.fromJson(v));
            else replica.apply(Wire.decode(v));
        }
        return replica;
    }

    static List<String> scopesOf(ServerDefinition def, Replica replica, String record) {
        String type = OpId.recordType(record);
        Function<ScopedRecord, ? extends Collection<String>> fn = def.scopes().get(type);
        if (fn == null) throw new AccordException("no scope function for " + type);
        return normalize(fn.apply(new ScopedRecord(record, replica.read(record).orElse(JsonObject.EMPTY))));
    }

    /** Distinct keys in JavaScript's default sort order (UTF-16 code units). */
    static List<String> normalize(Collection<String> keys) {
        return new ArrayList<>(new TreeSet<>(keys));
    }

    static boolean overlaps(Collection<String> a, Collection<String> b) {
        Set<String> s = new HashSet<>(b);
        for (String k : a) if (s.contains(k)) return true;
        return false;
    }

    static boolean sameKeys(Collection<String> a, Collection<String> b) {
        return normalize(a).equals(normalize(b));
    }

    /** Where a lone surrogate hides in a JSON value (a key or a string), or null if nowhere. */
    static String lonePath(JsonValue value, String path) {
        if (value instanceof JsonString s) return hasLone(s.value()) ? path : null;
        if (value instanceof JsonArray a) {
            for (int i = 0; i < a.items().size(); i++) {
                String found = lonePath(a.items().get(i), path + "[" + i + "]");
                if (found != null) return found;
            }
        } else if (value instanceof JsonObject o) {
            for (String k : jsKeyOrder(o.members().keySet())) {
                if (hasLone(k)) return path + " (a key)";
                String found = lonePath(o.members().get(k), path + "." + k);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** {@code Object.entries} order: array-index keys ascending, then insertion order. */
    private static List<String> jsKeyOrder(Collection<String> keys) {
        List<String> out = new ArrayList<>(keys);
        out.sort((x, y) -> Long.compare(indexOrMax(x), indexOrMax(y)));
        return out;
    }

    private static long indexOrMax(String key) {
        int n = key.length();
        if (n == 0 || n > 10 || (n > 1 && key.charAt(0) == '0')) return Long.MAX_VALUE;
        long v = 0;
        for (int k = 0; k < n; k++) {
            char ch = key.charAt(k);
            if (ch < '0' || ch > '9') return Long.MAX_VALUE;
            v = v * 10 + (ch - '0');
        }
        return v <= 4294967294L ? v : Long.MAX_VALUE;
    }

    static boolean hasLone(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) i++;
                else return true;
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }
}
