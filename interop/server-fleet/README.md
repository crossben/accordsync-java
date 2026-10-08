# Mixed-server fleet

plan-java.md J5 (ADR-J12), a port of `python/server-interop`: one PostgreSQL database with the
TypeScript reference server (`app/conformance/reference-server.ts`, Accord workspace sources) and
the Java server (`tools/ConformanceServer.java`) running on it at once, both with the conformance
profile. Per seed (`MixedServerFleetTest#mixedServerFleet`): the database is migrated by one
implementation (even seeds TypeScript, odd Java) and the other must find nothing to do; two Java
devices (`accordsync-client`) and two TypeScript devices (`node/ts-client.mts`) send every request
to a random server through a network that loses 25%; mid-run compaction through a random
server's control API (must fold; the folded probe op is acked on retry and refused when tampered,
by both servers); scope-event probes answered identically by both servers, then a scope change on
the lossy network; at the end identical canonical snapshots, nothing pending, equal to the
database (whose `records` rows agree with the feed), and both servers served pushes and pulls.
Also: a lone surrogate answered alike, a lost scope delta sent again (ADR-0011), and a record
moving into both key sets during a token change arriving with its history, per server.

```sh
interop/server-fleet/run.sh                          # Docker, Node 22+, pnpm install done in app/
ACCORD_FLEET_SEEDS=1,2,3,4,5 interop/server-fleet/run.sh
```

`run.sh` starts PostgreSQL in Docker (`accord-jsf-pg`, host port 55651) unless
`ACCORD_DATABASE_URL` is set, installs the library, builds the Java server's classpath and runs the
tests; it kills leftover servers by PID. Ports: TypeScript 8741/8742, Java 8743/8744. Logs and
`summary.jsonl` go to `.logs/`. Diagnosis: `ACCORD_FLEET_ONLY=ts|java` routes devices to one
server; `ACCORD_FLEET_DEBUG=<record>` logs the Java devices' pulls of that record and its feed.

Seed 3 found a scope-delta bug shared by every server (a record a device could not see at its
cursor, moving into a scope its old *and* new read keys cover while its keys change, never reached
it with its history). Fixed in the reference and the Java server by judging the delta at the
device's cursor (ADR-0011, 2026-10-07 b); `aRecordMovingIntoBothKeySetsDuringATokenChangeComesWithItsHistory`
guards it on both servers.
