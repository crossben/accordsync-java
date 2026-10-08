# Client fleet

Java and TypeScript Accord clients against the real server (`@accordsync/server` from npm).

- `HttpTest`: the Java client (`HttpTransport`) against the server: conflicts and resolution,
  refusals with the server's exact reason, reassignment exits, scope deltas after a new token,
  compaction snapshots to a new device (with a restart from the same `JdbcStorage` SQLite file),
  `device_seq` after compaction on a reinstalled device, "op id already used", `HttpException` 401.
- `MixedFleetTest`: two Java devices and two TypeScript devices (`@accordsync/client`, driven by
  `node/ts-client.mjs` over stdin/stdout) edit three records, one of them `dossier:é`, with awkward
  values (`null`, `2.5`, `1e21`, `""`, `"😀"`, objects with index-like keys, sets mixing numbers and
  strings) through a network that loses requests and responses after the server applied them. Mid-run
  the fleet heals and settles, the server compacts (at least one record must fold), then the network
  goes lossy again. At the end every device must hold byte-identical canonical snapshots and
  nothing pending. One run per seed of `ACCORD_INTEROP_SEEDS` (default `1,2,3`).

The server and TypeScript client are the published npm packages (0.3.2), pinned in
`node/package.json`. The Java tests use `accordsync-client` 0.3.2 from the local Maven repository,
so install it first.

```sh
./mvnw -q install -DskipTests                          # at java/; needs Docker, Node 22+, JDK 17+
interop/client-fleet/run.sh
ACCORD_INTEROP_SEEDS=1,2,3,4,5 interop/client-fleet/run.sh
interop/client-fleet/run.sh -Dtest=HttpTest            # extra arguments go to Maven
```

`run.sh` starts PostgreSQL in Docker on host port 55631 (unless `ACCORD_DATABASE_URL` is set), the
server on port 8721, runs `../../mvnw -f interop/client-fleet/pom.xml test`, and stops both when it
exits (server output in `server.log`). `ACCORD_PORT` and `ACCORD_TEST_PORT` change the server ports.
This project is not a module of the root build, so `./mvnw verify` stays fast.

`node/server.mjs` also listens on a second port (8722) for test-only routes: sign a token, reset the
database, run compaction. It exists for these tests only; never expose it.
