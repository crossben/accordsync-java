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

> **Status: in development.** Not published on Maven Central yet. Website and docs:
> [accord.benhattab.pro](https://accord.benhattab.pro).

## Modules (`io.github.crossben`)

| Artifact | What it does |
| --- | --- |
| `accordsync-core` | The merge core: hybrid logical clocks, operations, `lww`, `counter`, `set` and `conflict`. No dependencies. |
| `accordsync-client` | The client: local-first writes, background sync, conflicts and refusals; SQLite (JDBC) and memory storage. |
| `accordsync-server` | The sync server on PostgreSQL, framework-agnostic: push, pull, scopes, auth, compaction. |
| `accordsync-spring-boot-starter` | Spring Boot integration: auto-configuration, properties, endpoints, scheduled compaction. |

## Develop

Requires JDK 17 or newer (the wrapper fetches Maven).

```sh
./mvnw verify
```

`contract/` holds the golden vectors, protocol schemas and conformance profile from the Accord
repository; this implementation must pass them. Refresh it with `java tools/SyncContract.java`
(reads `../app`, or `ACCORD_APP_DIR`).

## Licence

[Apache-2.0](LICENSE).
