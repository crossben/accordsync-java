# Accord for Java

**Offline-first sync that stays correct when the network lies**, for Java and Kotlin programs and
for Spring Boot backends.

Apps keep working with no connection. When it comes back, every device ends up with the same data:
changes merge by rules you declare per field, counters never lose an increment, and conflicting
decisions are kept for your app to settle instead of being guessed.

Two things live here:

- a **client**, so Java, Kotlin and Android programs write offline and sync like any other Accord
  device;
- a **server** for Spring Boot, with the same protocol, merge rules and PostgreSQL schema as
  [`@accordsync/server`](https://github.com/crossben/accordsync), so every existing client
  (TypeScript, React Native, Flutter, Python) syncs with it unchanged.

> **Status: v0.3.1.** Pre-1.0: the API may still change between minor versions. Website and docs:
> [accord.benhattab.pro](https://accord.benhattab.pro/docs/java/).

Maven (`io.github.crossben`, Java 17+):

```xml
<dependency>
  <groupId>io.github.crossben</groupId>
  <artifactId>accordsync-client</artifactId>                <!-- the client -->
  <version>0.3.1</version>
</dependency>
<dependency>
  <groupId>io.github.crossben</groupId>
  <artifactId>accordsync-spring-boot-starter</artifactId>   <!-- the server, for Spring Boot -->
  <version>0.3.1</version>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.crossben:accordsync-client:0.3.1")              // the client
implementation("io.github.crossben:accordsync-spring-boot-starter:0.3.1") // the server, for Spring Boot
```

The client's SQLite storage needs `org.xerial:sqlite-jdbc` on the classpath (an optional
dependency); see [accordsync-client](accordsync-client/README.md).

## Quick start

The client: declare the same schema as your server, open it, write, sync.

```java
Schema schema = Schema.define(Map.of(
        "dossier", Map.of("agent", lww(), "visits", counter(), "docs", set(), "status", conflict())));

try (AccordClient accord = AccordClient.open(AccordClient.options()
        .schema(schema)
        .storage(JdbcStorage.sqlite("accord.db"))
        .transport(new HttpTransport("https://sync.example.com", () -> auth.currentJwt())))) {
    accord.assign("dossier:91", "agent", "awa"); // saved on the device, online or not
    accord.inc("dossier:91", "visits", 1);
    accord.sync();                               // or accord.start() for background sync
}
```

The server, in a Spring Boot app (with `spring-boot-starter-webmvc` and a PostgreSQL
`spring.datasource.url`): one `ServerDefinition` bean. The starter migrates the database and serves
`/v1/push`, `/v1/pull` and `/health`.

```java
@Bean
public ServerDefinition accordDefinition() {
    return AccordServer.define(d -> d
            .schema(schema)
            .scope("dossier", r -> r.string("agent") == null ? List.of() : List.of("agent:" + r.string("agent")))
            .access(claims -> Access.readWrite("agent:" + ((JsonString) claims.get("sub")).value()))
            .auth(Auth.jwks("https://auth.example.com/.well-known/jwks.json")
                    .issuer("https://auth.example.com/")
                    .audience("accord")));
}
```

Imports, events, conflicts and configuration: [accordsync-client](accordsync-client/README.md) and
[accordsync-spring-boot-starter](accordsync-spring-boot-starter/README.md).

## Modules (`io.github.crossben`)

| Artifact | What it does |
| --- | --- |
| `accordsync-core` | The merge core: hybrid logical clocks, operations, `lww`, `counter`, `set` and `conflict`. No dependencies. |
| `accordsync-client` | The client: local-first writes, background sync, conflicts and refusals; SQLite (JDBC) and memory storage. |
| `accordsync-server` | The sync server on PostgreSQL, framework-agnostic: push, pull, scopes, auth, compaction. |
| `accordsync-spring-boot-starter` | Spring Boot integration: auto-configuration, properties, endpoints, scheduled compaction. |

## How compatibility is proven

- `contract/` holds the golden vectors, protocol schemas and conformance profile from the Accord
  repository: the core passes every vector in every delivery order, and the random vectors generated
  by the TypeScript core.
- CI runs Accord's black-box HTTP conformance suite (`conformance/` in the Accord repository)
  against the Java server and against the Spring Boot example app (`examples/spring-boot-app`).
- `interop/client-fleet` runs the Java client against the TypeScript server, alone and with
  TypeScript devices; `interop/server-fleet` runs a mixed-server fleet: the TypeScript and Java
  servers on one PostgreSQL database at the same time, with Java and TypeScript devices sending every
  request to either, over a network that loses requests and responses. Every device must end with
  identical data.

## Develop

Requires JDK 17 or newer (the wrapper fetches Maven).

```sh
./mvnw verify
```

`contract/` holds the golden vectors, protocol schemas and conformance profile from the Accord
repository; this implementation must pass them. Refresh it with `java tools/SyncContract.java`
(reads `../app`, or `ACCORD_APP_DIR`).

`interop/client-fleet/run.sh` and `interop/server-fleet/run.sh` run the fleets above (Docker and
Node 22+); see their READMEs. Design decisions are in [`docs/adr/`](docs/adr/).

## Licence

[Apache-2.0](LICENSE).
