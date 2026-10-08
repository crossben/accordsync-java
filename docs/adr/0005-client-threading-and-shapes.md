# ADR-J05: the Java client's threading model, storage and Java shapes

**Status:** accepted (J2)

## Decisions

1. **One state lock, never held during network I/O** (as the Python client, ADR-Y05). Every read
   and mutation of client state (writer, replica, outbox, cursor, status) and every storage commit
   runs under one monitor. A round takes the outbox batch (or the cursor) under the lock, releases
   it for `Transport.push`/`pull`, and takes it again to apply the answer. So a write made during a
   push is pushed by the same round's push loop, and a write made during a pull stays in the
   outbox and survives a snapshot of its record (`pending` is computed when the page is applied).
   Commits are synchronous under the lock, in state-change order; `flush()` only waits for a
   commit in progress on another thread.
2. **One round at a time.** `sync()` called while a round runs waits on that round's
   `CompletableFuture` and gets its outcome (the same exception instance on failure). `sync()`
   from the round's own thread (a listener) throws `IllegalStateException` instead of deadlocking.
3. **Background sync** is one daemon thread (`accord-sync`) waiting on a monitor, not a
   `ScheduledExecutorService`: first round at once, 50 ms after a write (while no failure is
   pending), `syncInterval` after a good round, `min(maxBackoff, minBackoff * 2^(failures-1)) *
   (0.5 + random/2)` after a bad one. `stop()` does not wait; `close()` stops, waits for a round in
   flight and the thread, then closes storage. Durations are `java.time.Duration`; the clock is a
   `LongSupplier` of milliseconds, as in the core.
4. **Events** are typed methods (`onChange(Consumer<List<String>>)`, `onRefused`, `onSynced(LongConsumer)`,
   `onResync(Runnable)`, `onError`) returning a `Subscription`. Each registration is its own
   subscription (registering one lambda twice gives two). Listeners run on the thread that caused
   the event, without the lock; their exceptions are logged on the
   `io.github.crossben.accordsync` `java.util.logging` logger (WARNING) and ignored. For a write
   whose commit fails, `change` is emitted, then the commit's exception is thrown (client.ts).
5. **Received ops are applied with an effectively infinite skew** (`maxSkewMs = 2^53 - 1`): the
   server already refused absurd clocks; a device with a wrong clock must still accept the feed.
6. **Errors are unchecked.** `Transport` methods throw `HttpException(status, message)` or
   `UncheckedIOException`; storage failures are `StorageException`; malformed JSON is the core's
   `AccordException`. `sync()` rethrows the round's exception.
7. **Shapes.** `PullResult` is a sealed interface (`PullPage`, `ResyncRequired`), `PullItem` a sealed
   interface (`OpItem`, `SnapshotItem`, `ExitItem`); `PullPage.deviceSeq` is an `OptionalLong`.
   `Refusal`, `ConflictInfo`/`ConflictInfo.Value`, `SyncStatus`, `PushResult`/`PushResult.Refused`,
   `StoredMeta`, `StorageSnapshot` and `StorageTx` (with a builder) are records with value
   equality. Ops travel and are stored as the core's immutable `JsonObject` wire form, so storage
   "returns copies" by construction. `assign`/`add`/`remove` take an `Object` converted with
   `Json.of` (a `JsonValue` passes through).
8. **JdbcStorage** runs the exact statements of `storage/sqlite.ts` (same four `accord_` tables,
   `insert or replace` / `insert or ignore`, compact `JSON.stringify` bodies, meta keys
   `deviceId/cursor/hlc/seq`), but the transaction is JDBC's (`setAutoCommit(false)` ... `commit()`
   / `rollback()`) instead of a literal `begin immediate`, which the driver would not track. Lone
   surrogates are stored escaped (`\ud800`), which `JSON.parse` reads. Three ways to open it:
   `JdbcStorage.sqlite(path)` (one owned connection), `JdbcStorage.of(Connection)` (caller-owned,
   never closed), and `JdbcStorage.of(DataSource)` / `of(ConnectionSource)` (a connection borrowed
   per load or commit). Loads and commits are serialised by the storage's monitor. `sqlite-jdbc`
   is an optional Maven dependency; tests run with `--enable-native-access=ALL-UNNAMED`.
9. **HttpTransport** uses `HttpURLConnection` (Android-safe), calls the token `Supplier` before every
   request, sends `Authorization: Bearer`, `Accord-Device`, and UTF-8 JSON from the core's `Json`.
