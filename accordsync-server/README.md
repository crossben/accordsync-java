# accordsync-server

**The [Accord](https://accord.benhattab.pro) sync server for Java, on PostgreSQL.**

Framework-agnostic: push and pull, scopes, JWT auth, limits, rate limits, CORS and compaction behind
one `handle()` call per request. It speaks the same protocol, merges by the same rules and uses the
same PostgreSQL schema as
[`@accordsync/server`](https://www.npmjs.com/package/@accordsync/server), so the TypeScript, React
Native, Flutter, Python and Java clients sync with it unchanged.

With Spring Boot, use [`accordsync-spring-boot-starter`](../accordsync-spring-boot-starter/README.md):
it wires this module into the app. This page is for another framework, or none.

## Install

```xml
<dependency>
  <groupId>io.github.crossben</groupId>
  <artifactId>accordsync-server</artifactId>
  <version>0.3.2</version>
</dependency>
```

Gradle: `implementation("io.github.crossben:accordsync-server:0.3.2")`.

Java 17+ and PostgreSQL (the conformance suite and the example app use PostgreSQL 16). It brings
the PostgreSQL JDBC driver and Nimbus JOSE + JWT; serve it on a connection pool such as HikariCP.

## Define the server

`AccordServer.define()` takes the same parts as `defineServer` in TypeScript: the schema, a scope
function per record type, the access a user gets from their JWT claims, and how tokens are checked.
It checks at once that every record type has a scope function.

```java
import static io.github.crossben.accordsync.core.Strategy.conflict;
import static io.github.crossben.accordsync.core.Strategy.counter;
import static io.github.crossben.accordsync.core.Strategy.lww;
import static io.github.crossben.accordsync.core.Strategy.set;

import io.github.crossben.accordsync.core.JsonString;
import io.github.crossben.accordsync.core.Schema;
import io.github.crossben.accordsync.server.Access;
import io.github.crossben.accordsync.server.AccordServer;
import io.github.crossben.accordsync.server.Auth;
import io.github.crossben.accordsync.server.ServerDefinition;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public final class SyncDefinition implements Supplier<ServerDefinition> {
    @Override
    public ServerDefinition get() {
        Schema schema = Schema.define(Map.of(
                "dossier", Map.of("agent", lww(), "visits", counter(), "docs", set(), "status", conflict())));
        return AccordServer.define(d -> d
                .schema(schema)
                // The scope keys of a record, from its current fields.
                .scope("dossier", r -> r.string("agent") == null ? List.of() : List.of("agent:" + r.string("agent")))
                // The scope keys a user may read and write, from their verified JWT claims.
                .access(claims -> Access.readWrite("agent:" + ((JsonString) claims.get("sub")).value()))
                .auth(Auth.jwks("https://auth.example.com/.well-known/jwks.json")
                        .issuer("https://auth.example.com/")
                        .audience("accord")));
    }
}
```

Optional builder calls: `cors(origins)` (browser origins allowed to call the API),
`rateLimit(device, user)` (`RateLimit`s; default 600 requests a minute per device and 1 800 per
user; `noRateLimit()` turns it off), `compaction(...)` (a `Compaction`: device TTL, interval, minimum
ops) and `limits(...)` (`Limits.DEFAULT.withMaxBodyBytes(...)` and the like: body size, concurrent
pushes, ops per push, pull page size, scope delta size, clock skew). `Auth.hs256(secret)` is for
development and tests.

## Serve it

`new AccordServer(definition, dataSource)` serves `GET /health`, `POST /v1/push` and
`GET /v1/pull`. Translate your framework's request into an `HttpRequest(method, path, rawQuery,
headers, body)` and write back the `HttpResponse(status, headers, body)` that `server.handle(...)`
returns; it never throws. It is thread-safe: one instance serves every request.

```java
AccordServer server = new AccordServer(new SyncDefinition().get(), dataSource);
server.migrate(); // pending migrations; safe when several instances start at once

HttpResponse res = server.handle(new HttpRequest(method, path, rawQuery, headers, bodyBytes));
```

## Migrations and compaction

```sh
ACCORD_DATABASE_URL=postgresql://… java -cp <classpath> io.github.crossben.accordsync.server.Cli migrate
ACCORD_DATABASE_URL=postgresql://… java -cp <classpath> io.github.crossben.accordsync.server.Cli compact \
  --definition com.example.SyncDefinition
```

`ACCORD_DATABASE_URL` is a `postgresql://user:password@host:5432/db` or `jdbc:postgresql:` URL
(or `--database-url`). `--definition` (or `ACCORD_SERVER`) names a class with a public
no-argument constructor that implements `Supplier<ServerDefinition>`. Run `compact` at the definition's
compaction interval (default hourly). It takes PostgreSQL's exclusive advisory lock, so overlapping
runs, or several servers, do not conflict. In code: `Migrations.migrate(dataSource)` and
`server.compact()`.

## One database, any Accord server

The migrations are the TypeScript server's, recorded in the same ledger table (`kysely_migration`):
a database migrated by `@accordsync/server` is up to date here, and the other way round. One database
can be served by TypeScript and Java servers at the same time, with clients sent to either. The
repository's `interop/server-fleet` harness does exactly that: Java and TypeScript devices send every
request to a randomly chosen server, through a network that loses requests and responses, and must
end with identical data. This server, and the repository's Spring Boot example app, also pass
Accord's black-box HTTP conformance suite, the same one the TypeScript server passes.

## Security notes

- Requests authenticate with `Authorization: Bearer <jwt>`. In production use `Auth.jwks()` with an
  issuer and an audience.
- Rate limits are kept in memory, per instance: with several instances, each has its own buckets.
- Request bodies above `maxBodyBytes` (default 5 MiB) get 413.
- CORS for the sync API is the definition's `cors` list, answered by the server.
- What a user may read and write is decided only by your access function and scope functions.
  See [docs/security.md](https://github.com/crossben/accordsync/blob/main/docs/security.md).

Docs: [accord.benhattab.pro/docs/java](https://accord.benhattab.pro/docs/java/) ·
Source: [crossben/accordsync-java](https://github.com/crossben/accordsync-java) · Licence: Apache-2.0
