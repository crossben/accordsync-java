# ADR-J09: the conformance harness

**Status:** accepted (J4)

## Decisions

1. **`tools/ConformanceServer.java`**, a single-file program run with the server's classpath
   (`tools/conformance-server.sh` builds it and starts it). It reads the profile from
   `contract/conformance/profile.json` (or `ACCORD_PROFILE`), serves the sync API on `ACCORD_PORT`
   (8701) and the control API of `app/conformance/PROFILE.md` on `ACCORD_CONTROL_PORT` (8702).
2. **`com.sun.net.httpserver.HttpServer`** with a cached thread pool and a backlog of 1024, so the
   suite's concurrent requests (150 at once for the rate-limit test, overlapping pushes and pulls)
   really run concurrently.
3. **A 20-connection pool written for the harness** (a dynamic proxy that returns connections on
   `close()`, rolling back and restoring autocommit), so 150 simultaneous requests never exceed
   PostgreSQL's 100 connections; production uses HikariCP.
4. **`/hold-record`** opens a connection of its own (outside the pool), locks the row `for update`
   in an open transaction and keeps it across requests; `/release` and `/reset` roll it back.
5. CI runs it in a `conformance` job: the server's JUnit tests on PostgreSQL (including the ledger
   interop with the TypeScript migrator), then the harness in the background (never `wait`ed on),
   a `/health` poll, the suite, and the harness log on failure.
