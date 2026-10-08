package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.AccordException;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.Op;
import io.github.crossben.accordsync.core.OpId;
import io.github.crossben.accordsync.core.RecordSnapshot;
import io.github.crossben.accordsync.core.Replica;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.core.Wire;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * An in-memory Accord server, enough to exercise every client path without PostgreSQL (port of the
 * Python tests/fake_server.py and the Dart fake_server.dart): pushes applied in order and idempotent,
 * refusals (out of scope, op id already used, schema), device_seq, paged pulls with has_more, exits
 * when a record leaves the reader's scope and its whole history when it enters, snapshots after
 * compaction (folded ops leave empty slots, so stored cursors keep their meaning), resync on demand,
 * and injected failures.
 */
final class FakeServer {
    record Access(List<String> read, List<String> write) {}

    private final Schema schema;
    private final BiFunction<String, JsonObject, List<String>> scopes;
    private final Function<String, Access> access;

    /** Fails the next n requests, as a dropped connection would. */
    int failNext;
    int requests;

    // Feed entries: a wire op (JsonObject), a RecordSnapshot, or null (folded away).
    private final List<Object> feed = new ArrayList<>();
    private final Map<String, String> applied = new HashMap<>();
    private final Map<String, String> deviceUser = new HashMap<>();
    private final Map<String, Long> deviceSeq = new HashMap<>();
    private final Map<String, Set<String>> sent = new HashMap<>();
    private final Set<String> resync = new HashSet<>();
    private Replica state;

    FakeServer(Schema schema, BiFunction<String, JsonObject, List<String>> scopes, Function<String, Access> access) {
        this.schema = schema;
        this.scopes = scopes;
        this.access = access;
        this.state = new Replica(schema);
    }

    Transport transportFor(String user) {
        return new FakeTransport(this, user);
    }

    /** The next pull of the device answers resync_required. */
    synchronized void requireResync(String deviceId) {
        resync.add(deviceId);
    }

    /** Folds a record's history into a snapshot: its ops leave the feed, a snapshot entry is appended. */
    synchronized void compact(String record) {
        for (int i = 0; i < feed.size(); i++) if (record.equals(recordOf(feed.get(i)))) feed.set(i, null);
        feed.add(state.snapshotRecord(record));
    }

    private void check(String user, String deviceId) {
        requests++;
        if (failNext > 0) {
            failNext--;
            throw new HttpException(503, "network down");
        }
        String owner = deviceUser.computeIfAbsent(deviceId, d -> user);
        if (!owner.equals(user)) throw new HttpException(403, "device " + deviceId + " belongs to another user");
    }

    private List<String> keys(Replica r, String record) {
        Optional<JsonObject> fields = r.read(record);
        return fields.isEmpty() ? List.of() : scopes.apply(record, fields.get());
    }

    synchronized PushResult push(String user, String deviceId, List<JsonObject> ops) {
        check(user, deviceId);
        List<String> acked = new ArrayList<>();
        List<PushResult.Refused> refused = new ArrayList<>();
        for (JsonObject raw : ops) {
            String oid = ((JsonString) raw.get("op_id")).value();
            String canonical = Json.canonical(raw);
            String seen = applied.get(oid);
            if (seen != null) {
                if (seen.equals(canonical)) acked.add(oid);
                else refused.add(new PushResult.Refused(oid, "op id already used"));
                continue;
            }
            Op op;
            try {
                op = Wire.decode(raw);
                if (!OpId.parse(oid).device().equals(deviceId)) throw new AccordException("belongs to another device");
                state.validate(op);
            } catch (AccordException e) {
                refused.add(new PushResult.Refused(oid, e.getMessage()));
                continue;
            }
            List<String> write = access.apply(user).write();
            boolean existing = state.read(op.record()).isPresent();
            Replica after = state.without(Set.of());
            after.apply(op);
            List<String> k = keys(existing ? state : after, op.record());
            if (!overlap(k, write)) {
                refused.add(new PushResult.Refused(oid, "out of scope: you may not write " + op.record()));
                continue;
            }
            state = after;
            applied.put(oid, canonical);
            feed.add(raw);
            long seq = OpId.parse(oid).seq();
            if (seq > deviceSeq.getOrDefault(deviceId, 0L)) deviceSeq.put(deviceId, seq);
            acked.add(oid);
        }
        return new PushResult(acked, refused);
    }

    synchronized PullResult pull(String user, String deviceId, long cursor, int limit) {
        check(user, deviceId);
        if (resync.remove(deviceId)) {
            sent.put(deviceId, new HashSet<>());
            return new PullResult.ResyncRequired();
        }
        if (cursor == 0) sent.put(deviceId, new HashSet<>());
        Set<String> s = sent.computeIfAbsent(deviceId, d -> new HashSet<>());
        List<String> read = access.apply(user).read();
        Function<String, Boolean> visible = r -> overlap(keys(state, r), read);
        List<PullItem> items = new ArrayList<>();
        // Scope changes since the last pull: exits, then the whole history of records that entered.
        for (String r : new TreeSet<>(s)) {
            if (!visible.apply(r)) {
                s.remove(r);
                items.add(new PullItem.ExitItem(r));
            }
        }
        for (String r : state.records()) {
            if (visible.apply(r) && !s.contains(r) && recordBefore(r, cursor)) {
                s.add(r);
                for (int i = 0; i < cursor; i++) {
                    Object e = feed.get(i);
                    if (r.equals(recordOf(e))) items.add(item(e));
                }
            }
        }
        int pos = (int) cursor;
        while (pos < feed.size() && items.size() < limit) {
            Object e = feed.get(pos++);
            String record = recordOf(e);
            if (record == null || !visible.apply(record)) continue;
            s.add(record);
            items.add(item(e));
        }
        Long seq = deviceSeq.get(deviceId);
        return new PullResult.PullPage(items, pos, pos < feed.size(), seq == null ? OptionalLong.empty() : OptionalLong.of(seq));
    }

    private boolean recordBefore(String record, long cursor) {
        for (int i = 0; i < cursor && i < feed.size(); i++) if (record.equals(recordOf(feed.get(i)))) return true;
        return false;
    }

    private static PullItem item(Object e) {
        return e instanceof RecordSnapshot snap ? new PullItem.SnapshotItem(snap) : new PullItem.OpItem((JsonObject) e);
    }

    private static boolean overlap(List<String> a, List<String> b) {
        for (String k : a) if (b.contains(k)) return true;
        return false;
    }

    private static String recordOf(Object e) {
        if (e instanceof RecordSnapshot s) return s.record();
        if (e instanceof JsonObject o) return ((JsonString) o.get("record")).value();
        return null;
    }

    /** Through JSON text both ways, like the network. */
    static final class FakeTransport implements Transport {
        final FakeServer server;
        final String user;

        FakeTransport(FakeServer server, String user) {
            this.server = server;
            this.user = user;
        }

        @Override
        public PushResult push(String deviceId, List<JsonObject> ops) {
            List<JsonObject> wire = new ArrayList<>();
            for (JsonObject o : ops) wire.add((JsonObject) Json.parse(Json.stringify(o)));
            return server.push(user, deviceId, wire);
        }

        @Override
        public PullResult pull(String deviceId, long cursor, int limit) {
            PullResult r = server.pull(user, deviceId, cursor, limit);
            if (!(r instanceof PullResult.PullPage page)) return r;
            List<PullItem> items = new ArrayList<>();
            for (PullItem i : page.items()) {
                if (i instanceof PullItem.OpItem oi) items.add(new PullItem.OpItem((JsonObject) Json.parse(Json.stringify(oi.op()))));
                else if (i instanceof PullItem.SnapshotItem si) {
                    items.add(new PullItem.SnapshotItem(RecordSnapshot.fromJson(Json.parse(Json.stringify(si.snapshot().toJson())))));
                } else items.add(i);
            }
            return new PullResult.PullPage(items, page.cursor(), page.hasMore(), page.deviceSeq());
        }
    }

    Schema schema() {
        return schema;
    }
}
