package io.github.crossben.accordsync.client;

import io.github.crossben.accordsync.core.AddOp;
import io.github.crossben.accordsync.core.ApplyResult;
import io.github.crossben.accordsync.core.AssignOp;
import io.github.crossben.accordsync.core.FieldRef;
import io.github.crossben.accordsync.core.Hlc;
import io.github.crossben.accordsync.core.IncOp;
import io.github.crossben.accordsync.core.Json;
import io.github.crossben.accordsync.core.JsonArray;
import io.github.crossben.accordsync.core.JsonNumber;
import io.github.crossben.accordsync.core.JsonObject;
import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.JsonValue;
import io.github.crossben.accordsync.core.LocalWriter;
import io.github.crossben.accordsync.core.Op;
import io.github.crossben.accordsync.core.OpId;
import io.github.crossben.accordsync.core.Protocol;
import io.github.crossben.accordsync.core.RecordSnapshot;
import io.github.crossben.accordsync.core.RemoveOp;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.core.Wire;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An Accord device. Writes apply locally at once and are saved to storage; sync pushes them and
 * pulls everyone else's, in the background ({@link #start()}) or on demand ({@link #sync()}).
 *
 * <p>Thread-safe. One lock guards the client's state and every storage commit; it is never held
 * during a transport call, so a write made while a push or pull is in flight is never lost or
 * blocked by the network (ADR-J05). Listeners run on the thread that caused the event, without the
 * lock held; an exception in a listener is logged and ignored.
 */
public final class AccordClient implements AutoCloseable {
    /** The protocol version this module speaks. */
    public static final int PROTOCOL_VERSION = Protocol.VERSION;

    static final Logger LOG = Logger.getLogger("io.github.crossben.accordsync");

    private final String deviceId;
    private final Schema schema;
    private final StorageAdapter storage;
    private final Transport transport;
    private final LongSupplier clock;
    private final DoubleSupplier random;
    private final int pushBatch;
    private final int pullLimit;
    private final long syncIntervalNanos;
    private final long minBackoffNanos;
    private final long maxBackoffNanos;

    // Guarded by `lock`, never held during network I/O.
    private final Object lock = new Object();
    private LocalWriter writer;
    private long cursor;
    private final LinkedHashMap<String, Op> outbox = new LinkedHashMap<>(); // unacknowledged, in write order
    private Long lastSyncAt;
    private Throwable lastError;
    private int failures;

    // One round at a time; concurrent callers share it.
    private final Object roundGuard = new Object();
    private CompletableFuture<Void> round;
    private Thread roundThread;

    // Background sync, guarded by `cond`.
    private final Object cond = new Object();
    private boolean running;
    private int generation;
    private long nextAt;
    private Thread thread;

    private final Listeners<List<String>> changeListeners = new Listeners<>("change");
    private final Listeners<Refusal> refusedListeners = new Listeners<>("refused");
    private final Listeners<Long> syncedListeners = new Listeners<>("synced");
    private final Listeners<Void> resyncListeners = new Listeners<>("resync");
    private final Listeners<Throwable> errorListeners = new Listeners<>("error");

    private AccordClient(Options o, String deviceId, Optional<StoredMeta> meta) {
        this.deviceId = deviceId;
        this.schema = o.schema;
        this.storage = o.storage;
        this.transport = o.transport;
        this.clock = o.clock;
        this.random = o.random;
        this.pushBatch = o.pushBatch;
        this.pullLimit = o.pullLimit;
        this.syncIntervalNanos = o.syncInterval.toNanos();
        this.minBackoffNanos = o.minBackoff.toNanos();
        this.maxBackoffNanos = o.maxBackoff.toNanos();
        this.cursor = meta.map(StoredMeta::cursor).orElse(0L);
        this.writer = newWriter(meta.map(m -> new LocalWriter.Resume(Hlc.decode(m.hlc()), m.seq())).orElse(null));
    }

    /** @return a new options builder */
    public static Options options() {
        return new Options();
    }

    /**
     * Opens the device: loads its stored snapshots, ops, outbox and cursor. The options' device id
     * is used only the first time; afterwards the stored id is kept.
     *
     * @param options the options (schema, storage and transport are required)
     * @return the client
     */
    public static AccordClient open(Options options) {
        Objects.requireNonNull(options.schema, "schema");
        Objects.requireNonNull(options.storage, "storage");
        Objects.requireNonNull(options.transport, "transport");
        StorageSnapshot snap = options.storage.load();
        Optional<StoredMeta> meta = snap.meta();
        String id = meta.map(StoredMeta::deviceId)
                .orElseGet(() -> options.deviceId != null ? options.deviceId : randomDeviceId());
        AccordClient client = new AccordClient(options, id, meta);
        synchronized (client.lock) {
            for (RecordSnapshot base : snap.snapshots()) client.writer.replica().loadSnapshot(base);
            Map<String, Op> byId = new HashMap<>();
            for (JsonObject raw : snap.ops()) {
                Op op = Wire.decode(raw);
                byId.put(op.opId(), op);
                client.writer.receive(op);
            }
            List<String> ids = new ArrayList<>(snap.outbox());
            ids.sort(Comparator.comparingLong(i -> OpId.parse(i).seq()));
            for (String oid : ids) {
                Op found = byId.get(oid);
                if (found != null) client.outbox.put(oid, found);
            }
            if (meta.isEmpty()) client.persist(StorageTx.builder());
        }
        return client;
    }

    /**
     * A random device id from {@link SecureRandom}: {@code "d"} and 32 hex digits. Device ids must
     * never collide.
     *
     * @return the id
     */
    public static String randomDeviceId() {
        return randomDeviceId(new SecureRandom());
    }

    static String randomDeviceId(Random source) {
        byte[] bytes = new byte[16];
        source.nextBytes(bytes);
        StringBuilder out = new StringBuilder("d");
        for (byte b : bytes) out.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        return out.toString();
    }

    /** @return this device's id */
    public String deviceId() {
        return deviceId;
    }

    // ── events ──────────────────────────────────────────────────────────

    /**
     * Records whose local state changed (a write, a pulled page, a rollback, a resync).
     *
     * @param listener receives the record ids
     * @return the unsubscribe handle
     */
    public Subscription onChange(Consumer<List<String>> listener) {
        return changeListeners.add(listener);
    }

    /**
     * A local write the server refused, already rolled back.
     *
     * @param listener receives the refusal
     * @return the unsubscribe handle
     */
    public Subscription onRefused(Consumer<Refusal> listener) {
        return refusedListeners.add(listener);
    }

    /**
     * A full round ended.
     *
     * @param listener receives the cursor
     * @return the unsubscribe handle
     */
    public Subscription onSynced(LongConsumer listener) {
        Objects.requireNonNull(listener, "listener");
        return syncedListeners.add(listener::accept);
    }

    /**
     * The server asked for a resync and local data was reloaded.
     *
     * @param listener the listener
     * @return the unsubscribe handle
     */
    public Subscription onResync(Runnable listener) {
        Objects.requireNonNull(listener, "listener");
        return resyncListeners.add(x -> listener.run());
    }

    /**
     * A background round failed (it is retried with backoff).
     *
     * @param listener receives the error
     * @return the unsubscribe handle
     */
    public Subscription onError(Consumer<Throwable> listener) {
        return errorListeners.add(listener);
    }

    // ── reading ─────────────────────────────────────────────────────────

    /**
     * A record's fields; fields with no value are left out.
     *
     * @param record the record id
     * @return the fields, or empty if this device has never seen the record
     */
    public Optional<JsonObject> read(String record) {
        synchronized (lock) {
            return writer.replica().read(record);
        }
    }

    /** @return the ids of every record this device holds, sorted */
    public List<String> records() {
        synchronized (lock) {
            return writer.replica().records();
        }
    }

    /**
     * The records of one type.
     *
     * @param type the record type
     * @return the record ids, sorted
     */
    public List<String> records(String type) {
        List<String> out = new ArrayList<>();
        for (String r : records()) if (r.startsWith(type + ":")) out.add(r);
        return out;
    }

    /** @return every {@code conflict} field holding more than one value, with the values */
    public List<ConflictInfo> conflicts() {
        synchronized (lock) {
            List<ConflictInfo> out = new ArrayList<>();
            for (FieldRef ref : writer.replica().conflicts()) {
                JsonObject fields = writer.replica().read(ref.record()).orElse(JsonObject.EMPTY);
                List<ConflictInfo.Value> values = new ArrayList<>();
                if (fields.get(ref.field()) instanceof JsonObject shown && shown.get("conflicted") instanceof JsonArray a) {
                    for (JsonValue v : a.items()) {
                        JsonObject o = (JsonObject) v;
                        values.add(new ConflictInfo.Value(o.get("value"), ((JsonString) o.get("opId")).value()));
                    }
                }
                out.add(new ConflictInfo(ref.record(), ref.field(), values));
            }
            return out;
        }
    }

    /** @return pending ops, cursor, last sync time and last error */
    public SyncStatus status() {
        synchronized (lock) {
            return new SyncStatus(outbox.size(), cursor,
                    lastSyncAt == null ? OptionalLong.empty() : OptionalLong.of(lastSyncAt), Optional.ofNullable(lastError));
        }
    }

    // ── writing (local-first) ───────────────────────────────────────────

    /**
     * Sets an {@code lww} or {@code conflict} field. Returns once the write is saved on this device.
     *
     * @param record the record id
     * @param field the field
     * @param value a {@link JsonValue} or a plain Java value ({@link Json#of(Object)}); null is JSON null
     * @return the op
     */
    public AssignOp assign(String record, String field, Object value) {
        JsonValue v = Json.of(value);
        return write(w -> w.assign(record, field, v));
    }

    /**
     * Resolves a conflicted field: writes {@code value}, superseding every value currently shown.
     *
     * @param record the record id
     * @param field the field
     * @param value the value
     * @return the op
     */
    public AssignOp resolve(String record, String field, Object value) {
        return assign(record, field, value);
    }

    /**
     * Increments a counter.
     *
     * @param record the record id
     * @param field the field
     * @param by an integer within ±(2^53 - 1)
     * @return the op
     */
    public IncOp inc(String record, String field, long by) {
        return write(w -> w.inc(record, field, by));
    }

    /**
     * Adds an element to a set.
     *
     * @param record the record id
     * @param field the field
     * @param element a string or finite number (or its {@link JsonValue})
     * @return the op
     */
    public AddOp add(String record, String field, Object element) {
        JsonValue e = Json.of(element);
        return write(w -> w.add(record, field, e));
    }

    /**
     * Removes an element from a set (the adds this device has seen).
     *
     * @param record the record id
     * @param field the field
     * @param element a string or finite number (or its {@link JsonValue})
     * @return the op
     */
    public RemoveOp remove(String record, String field, Object element) {
        JsonValue e = Json.of(element);
        return write(w -> w.remove(record, field, e));
    }

    private <T extends Op> T write(Function<LocalWriter, T> make) {
        T op;
        RuntimeException error = null;
        synchronized (lock) {
            op = make.apply(writer);
            outbox.put(op.opId(), op);
            try {
                persist(StorageTx.builder().putOps(List.of(Wire.encode(op))).outboxAdd(List.of(op.opId())));
            } catch (RuntimeException e) { // reported after the change event, like client.ts
                error = e;
            }
        }
        changeListeners.emit(List.of(op.record()));
        if (error != null) throw error;
        soon();
        return op;
    }

    // ── sync ────────────────────────────────────────────────────────────

    /**
     * One full round: push the outbox, then pull every page. A call made while a round is running
     * waits for that round and shares its outcome.
     *
     * @throws IllegalStateException when called from inside its own round (from a listener)
     */
    public void sync() {
        CompletableFuture<Void> mine = null;
        CompletableFuture<Void> current;
        synchronized (roundGuard) {
            current = round;
            if (current == null) {
                mine = new CompletableFuture<>();
                round = mine;
                roundThread = Thread.currentThread();
            } else if (roundThread == Thread.currentThread()) {
                throw new IllegalStateException("sync() called from inside its own round (from a listener?)");
            }
        }
        if (current != null) {
            await(current);
            return;
        }
        try {
            runRound();
        } catch (RuntimeException | Error e) {
            finishRound(mine, e);
            throw e;
        }
        finishRound(mine, null);
    }

    private static void await(CompletableFuture<Void> f) {
        try {
            f.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException r) throw r;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }

    private void finishRound(CompletableFuture<Void> f, Throwable error) {
        synchronized (roundGuard) {
            round = null;
            roundThread = null;
        }
        if (error == null) f.complete(null);
        else f.completeExceptionally(error);
    }

    private void runRound() {
        pushAll();
        while (true) {
            long from;
            synchronized (lock) {
                from = cursor;
            }
            PullResult result = transport.pull(deviceId, from, pullLimit);
            if (!(result instanceof PullResult.PullPage page)) {
                pushAll();
                resync();
                continue;
            }
            List<String> changed;
            synchronized (lock) {
                // The server's count of this device's ops: never reuse an op id, even after lost storage.
                page.deviceSeq().ifPresent(writer::advanceSeq);
                changed = applyPage(page.items(), page.cursor());
            }
            if (!changed.isEmpty()) changeListeners.emit(changed);
            if (!page.hasMore()) break;
        }
        long at;
        synchronized (lock) {
            lastSyncAt = clock.getAsLong();
            lastError = null;
            failures = 0;
            at = cursor;
        }
        syncedListeners.emit(at);
    }

    private void pushAll() {
        while (true) {
            List<JsonObject> batch = new ArrayList<>();
            synchronized (lock) {
                if (outbox.isEmpty()) return;
                for (Op o : outbox.values()) {
                    if (batch.size() >= pushBatch) break;
                    batch.add(Wire.encode(o));
                }
            }
            PushResult res = transport.push(deviceId, batch);
            if (res.acked().isEmpty() && res.refused().isEmpty()) {
                throw new IllegalStateException("server neither acknowledged nor refused a non-empty push");
            }
            List<Refusal> refusals = new ArrayList<>();
            synchronized (lock) {
                for (String id : res.acked()) outbox.remove(id);
                for (PushResult.Refused r : res.refused()) {
                    Op op = outbox.remove(r.opId());
                    if (op != null) refusals.add(new Refusal(op.opId(), op.record(), op.field(), r.reason()));
                }
                List<String> refusedIds = new ArrayList<>();
                for (Refusal r : refusals) refusedIds.add(r.opId());
                if (!refusals.isEmpty()) writer.discard(refusedIds);
                List<String> done = new ArrayList<>(res.acked());
                for (PushResult.Refused r : res.refused()) done.add(r.opId());
                persist(StorageTx.builder().outboxDelete(done).deleteOps(refusedIds));
            }
            if (!refusals.isEmpty()) {
                Set<String> records = new LinkedHashSet<>();
                for (Refusal r : refusals) records.add(r.record());
                changeListeners.emit(List.copyOf(records));
            }
            for (Refusal r : refusals) refusedListeners.emit(r);
        }
    }

    /** Applies one pull page and saves it. Caller holds the lock. Returns changed records. */
    private List<String> applyPage(List<PullItem> items, long pageCursor) {
        Map<String, Op> put = new LinkedHashMap<>();
        List<String> forgotten = new ArrayList<>();
        List<RecordSnapshot> snapshots = new ArrayList<>();
        List<String> dropSnapshots = new ArrayList<>();
        Set<String> changed = new LinkedHashSet<>();
        Set<String> pending = new HashSet<>(outbox.keySet());
        for (PullItem item : items) {
            if (item instanceof PullItem.OpItem oi) {
                Op op = Wire.decode(oi.op());
                if (writer.receive(op) == ApplyResult.APPLIED) {
                    put.put(op.opId(), op);
                    changed.add(op.record());
                }
            } else if (item instanceof PullItem.SnapshotItem si) {
                // A compacted record: its snapshot replaces the ops it folded; our unpushed edits stay
                // on top. The server sends a snapshot before any later op of that record.
                RecordSnapshot snap = si.snapshot();
                for (String id : notPending(snap.record(), pending)) {
                    put.remove(id);
                    forgotten.add(id);
                }
                writer.replica().loadSnapshot(snap, pending);
                snapshots.add(snap);
                changed.add(snap.record());
            } else if (item instanceof PullItem.ExitItem ei) {
                // The record left our scope: forget it, except our own unpushed edits, which will be
                // pushed, refused and rolled back like any other refused write.
                List<String> ids = notPending(ei.record(), pending);
                for (String id : ids) put.remove(id);
                writer.forget(ei.record(), pending);
                forgotten.addAll(ids);
                dropSnapshots.add(ei.record());
                changed.add(ei.record());
            }
        }
        cursor = Math.max(cursor, pageCursor);
        Set<String> snapshotted = new HashSet<>();
        for (RecordSnapshot s : snapshots) snapshotted.add(s.record());
        List<String> deleteSnaps = new ArrayList<>();
        for (String r : dropSnapshots) if (!snapshotted.contains(r)) deleteSnaps.add(r);
        List<RecordSnapshot> putSnaps = new ArrayList<>();
        for (RecordSnapshot s : snapshots) if (!dropSnapshots.contains(s.record())) putSnaps.add(s);
        List<JsonObject> putOps = new ArrayList<>();
        for (Op o : put.values()) putOps.add(Wire.encode(o));
        persist(StorageTx.builder().deleteOps(forgotten).deleteSnapshots(deleteSnaps).putSnapshots(putSnaps).putOps(putOps));
        return List.copyOf(changed);
    }

    private List<String> notPending(String record, Set<String> pending) {
        List<String> out = new ArrayList<>();
        for (Op o : writer.replica().ops()) if (o.record().equals(record) && !pending.contains(o.opId())) out.add(o.opId());
        return out;
    }

    /** Read scopes changed: keep only unpushed local ops and pull everything again from 0. */
    private void resync() {
        List<String> before;
        synchronized (lock) {
            List<Op> keep = new ArrayList<>(outbox.values());
            before = writer.replica().records();
            writer = newWriter(new LocalWriter.Resume(writer.clock(), writer.seq()));
            List<JsonObject> wire = new ArrayList<>();
            for (Op op : keep) {
                writer.receive(op);
                wire.add(Wire.encode(op));
            }
            cursor = 0;
            persist(StorageTx.builder().clearOps().putOps(wire));
        }
        resyncListeners.emit(null);
        changeListeners.emit(before);
    }

    // ── background sync ─────────────────────────────────────────────────

    /** Syncs in the background on a daemon thread: soon after each write, every sync interval, and with backoff on errors. */
    public void start() {
        synchronized (cond) {
            if (running) return;
            running = true;
            generation += 1;
            nextAt = System.nanoTime();
            int gen = generation;
            thread = new Thread(() -> loop(gen), "accord-sync");
            thread.setDaemon(true);
            thread.start();
        }
    }

    /** Stops background sync. A round in flight finishes. */
    public void stop() {
        synchronized (cond) {
            running = false;
            cond.notifyAll();
        }
    }

    /** Waits for a local save in progress on another thread; saves are synchronous. */
    public void flush() {
        synchronized (lock) {
            // nothing: holding the lock means no commit is in progress
        }
    }

    /** Stops background sync, waits for a round in flight, and closes the storage. */
    @Override
    public void close() {
        stop();
        CompletableFuture<Void> current;
        Thread owner;
        synchronized (roundGuard) {
            current = round;
            owner = roundThread;
        }
        if (current != null && owner != Thread.currentThread()) {
            try {
                current.join(); // its error, if any, was reported to its caller
            } catch (RuntimeException ignored) {
                // reported elsewhere
            }
        }
        Thread t;
        synchronized (cond) {
            t = thread;
        }
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        flush();
        storage.close();
    }

    private void schedule(long delayNanos) {
        synchronized (cond) {
            nextAt = System.nanoTime() + delayNanos;
            cond.notifyAll();
        }
    }

    /** After a write, sync shortly (writes in a burst share one round). */
    private void soon() {
        int f;
        synchronized (lock) {
            f = failures;
        }
        boolean on;
        synchronized (cond) {
            on = running;
        }
        if (on && f == 0) schedule(TimeUnit.MILLISECONDS.toNanos(50));
    }

    private void loop(int gen) {
        while (true) {
            synchronized (cond) {
                while (true) {
                    if (!running || generation != gen) return;
                    long wait = nextAt == Long.MAX_VALUE ? Long.MAX_VALUE : nextAt - System.nanoTime();
                    if (wait <= 0) break;
                    try {
                        if (wait == Long.MAX_VALUE) cond.wait();
                        else TimeUnit.NANOSECONDS.timedWait(cond, wait);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                nextAt = Long.MAX_VALUE;
            }
            try {
                sync();
                schedule(syncIntervalNanos);
            } catch (RuntimeException error) {
                int f;
                synchronized (lock) {
                    failures += 1;
                    lastError = error;
                    f = failures;
                }
                errorListeners.emit(error);
                double base = Math.min((double) maxBackoffNanos, minBackoffNanos * Math.pow(2, f - 1));
                // Jitter: devices don't retry in lockstep.
                schedule((long) (base * (0.5 + random.getAsDouble() / 2)));
            }
        }
    }

    // ── internals ───────────────────────────────────────────────────────

    private LocalWriter newWriter(LocalWriter.Resume resume) {
        // The server already refused ops with absurd clocks; a device with a wrong clock of its own
        // must still accept everything the server sends.
        return new LocalWriter(schema, deviceId, clock, JsonNumber.MAX_SAFE_INTEGER, resume);
    }

    /** Commits with the current meta. Caller holds the lock. */
    private void persist(StorageTx.Builder tx) {
        tx.meta(new StoredMeta(deviceId, cursor, writer.clock().encode(), writer.seq()));
        storage.commit(tx.build());
    }

    private static final class Listeners<T> {
        private final String event;
        private final CopyOnWriteArrayList<Consumer<? super T>> list = new CopyOnWriteArrayList<>();

        Listeners(String event) {
            this.event = event;
        }

        Subscription add(Consumer<? super T> listener) {
            Objects.requireNonNull(listener, "listener");
            Consumer<? super T> wrapped = listener::accept; // a fresh identity per registration
            list.add(wrapped);
            return () -> list.remove(wrapped);
        }

        void emit(T payload) {
            for (Consumer<? super T> l : list) {
                try {
                    l.accept(payload);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "accord: a \"" + event + "\" listener threw", e);
                }
            }
        }
    }

    /** Options for {@link #open(Options)}. */
    public static final class Options {
        private Schema schema;
        private StorageAdapter storage;
        private Transport transport;
        private String deviceId;
        private LongSupplier clock = System::currentTimeMillis;
        private DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();
        private int pushBatch = 200;
        private int pullLimit = 500;
        private Duration syncInterval = Duration.ofSeconds(30);
        private Duration minBackoff = Duration.ofSeconds(1);
        private Duration maxBackoff = Duration.ofSeconds(60);

        private Options() {}

        /**
         * @param s the schema (required)
         * @return these options
         */
        public Options schema(Schema s) {
            schema = s;
            return this;
        }

        /**
         * @param s the storage (required)
         * @return these options
         */
        public Options storage(StorageAdapter s) {
            storage = s;
            return this;
        }

        /**
         * @param t the transport (required)
         * @return these options
         */
        public Options transport(Transport t) {
            transport = t;
            return this;
        }

        /**
         * @param id the device id used the first time (generated when absent)
         * @return these options
         */
        public Options deviceId(String id) {
            deviceId = id;
            return this;
        }

        /**
         * @param c physical time in ms
         * @return these options
         */
        public Options clock(LongSupplier c) {
            clock = Objects.requireNonNull(c, "clock");
            return this;
        }

        /**
         * @param r uniform in [0, 1), for backoff jitter
         * @return these options
         */
        public Options random(DoubleSupplier r) {
            random = Objects.requireNonNull(r, "random");
            return this;
        }

        /**
         * @param n ops per push request (default 200)
         * @return these options
         */
        public Options pushBatch(int n) {
            if (n < 1) throw new IllegalArgumentException("pushBatch must be positive");
            pushBatch = n;
            return this;
        }

        /**
         * @param n items per pull page (default 500)
         * @return these options
         */
        public Options pullLimit(int n) {
            if (n < 1) throw new IllegalArgumentException("pullLimit must be positive");
            pullLimit = n;
            return this;
        }

        /**
         * @param d pause between good background rounds (default 30 s)
         * @return these options
         */
        public Options syncInterval(Duration d) {
            syncInterval = Objects.requireNonNull(d);
            return this;
        }

        /**
         * @param d first backoff after a failed round (default 1 s)
         * @return these options
         */
        public Options minBackoff(Duration d) {
            minBackoff = Objects.requireNonNull(d);
            return this;
        }

        /**
         * @param d longest backoff (default 60 s)
         * @return these options
         */
        public Options maxBackoff(Duration d) {
            maxBackoff = Objects.requireNonNull(d);
            return this;
        }
    }
}
