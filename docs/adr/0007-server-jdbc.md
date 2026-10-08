# ADR-J07: the server on JDBC

**Status:** accepted (J4)

## Decisions

1. **A `javax.sql.DataSource`**, so applications bring their pool (HikariCP under Spring Boot).
   The module ships no pool; `Databases.fromUrl` makes an unpooled `PGSimpleDataSource` for the CLI
   and tests.
2. **Explicit transactions**: autocommit is turned off for a push, a pull or a compaction and
   restored afterwards, with a rollback on any failure, so a pooled connection never returns with
   an open transaction. Pulls run `set transaction isolation level repeatable read` as their first
   statement (it applies to that transaction only, so no isolation level leaks into the pool) and
   are retried on SQLSTATE 40001 up to 20 attempts with `random * 10 * attempt` ms of jitter.
3. **Same SQL as ADR-0010**: `pg_advisory_xact_lock_shared(0x4acc0d)` for pushes and the exclusive
   lock for compaction; missing record rows inserted, then locked with `... order by record for
   update`; records processed in UTF-16 order (`String.compareTo`); `accord_horizon()` bounds every
   pull; `op_hash` is the core's `OpHash.of`; the pending scope delta of ADR-0011 (2026-10-07) is
   kept in `devices.delta_keys`/`delta_cursor` exactly as `sync.ts` does, and the delta is judged at
   the device's cursor (ADR-0011, 2026-10-07 b: scopes at the cursor from the first scope row
   above it, history bounded to `pos <= cursor`), in the same SQL as `sync.ts`.
4. **Parameters**: `text[]` through `Connection.createArrayOf("text", ...)` with an explicit
   `::text[]` cast, `jsonb` written as canonical JSON text with `::jsonb`, read back as text and
   parsed by the core (so numbers keep JavaScript semantics), `bigint` as `long` (every cursor and
   position is a safe integer).
5. **Concurrent pushes per server instance** are bounded by a fair `Semaphore`
   (`maxConcurrentPushes`, default 8), the `Slots` of `sync.ts`.
6. **Migrations** are the TypeScript SQL, statement for statement, in the Kysely ledger under
   Kysely's session advisory lock (ADR-J02). Verified against the TypeScript migrator in both
   directions and half-and-half (`LedgerInteropTest`, identical `pg_dump --schema-only`).
7. **CLI**: `java -cp ... io.github.crossben.accordsync.server.Cli migrate|compact`; `compact` takes
   `--definition <class>` naming a `Supplier<ServerDefinition>` (the Python port's
   `--definition module:attribute`).
