# ADR-J12: the mixed-server fleet

**Status:** accepted (J5)

## Decisions

1. **`interop/server-fleet`** ports `python/server-interop` to JUnit: a Maven project outside the
   root build (`run.sh` installs the library, builds the Java server's classpath as
   `tools/conformance-server.sh` does, and runs `mvn -f interop/server-fleet test`).
2. **Servers**: the workspace TypeScript reference server (`app/conformance/reference-server.ts`
   via tsx, ports 8741/8742) and the Java harness (`java -cp $ACCORD_SERVER_CP
   tools/ConformanceServer.java`, ports 8743/8744) on one PostgreSQL database `accord_fleet`,
   recreated per seed. The tests start them per seed and stop them by `Process` handle; their PIDs
   go to `.logs/servers.pid` so `run.sh` kills leftovers by PID. No bare `wait`.
3. **Devices**: two Java clients (`accordsync-client`, `MemoryStorage`, a `Transport` that picks a
   server at random per request and loses 25% — requests, and responses after the server applied
   them) and two TypeScript clients (`node/ts-client.mts`, the workspace `@accordsync/client`, the
   same lossy random fetch). Same scenario as the Python fleet: 160 rounds, four devices at once,
   awkward values; migration by TypeScript on even seeds and Java on odd ones (the other must
   find nothing to do); mid-run compaction through a random server's control API (must fold, the
   folded probe op retried on both servers is acked, tampered is refused); scope-delta probes
   (identical items from both servers for records moving in/out and a token gaining/losing a
   zone), then the scope change on the lossy network; at the end byte-identical canonical
   snapshots, nothing pending, equal to what the database holds (and every `records` row agrees
   with its feed), both servers served pushes and pulls. Plus the lone-surrogate test and the
   lost-scope-delta test (ADR-0011, 2026-10-07) on each server, and the both-key-sets test
   (seed 3 found that a record moving into a scope the old and new read keys share during a token
   change never came with its history; fixed by judging the delta at the cursor, ADR-0011
   2026-10-07 b, in the reference and the Java server).
4. Seeds: `ACCORD_FLEET_SEEDS` (default `1,2,3`; CI `1,2,3,4,5`). Summaries go to
   `.logs/summary.jsonl`.
