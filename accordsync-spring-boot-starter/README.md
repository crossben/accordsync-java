# accordsync-spring-boot-starter

The Accord sync server for Spring Boot: `POST /v1/push`, `GET /v1/pull` and `GET /health` on the
app's own `DataSource`, migrations at startup, scheduled compaction and an Actuator health
indicator. All behaviour (auth, limits, CORS, rate limits, status codes, `Accord-Protocol` and
`Retry-After` headers) comes from [`accordsync-server`](../accordsync-server/README.md); this module
only wires it in. Part of [Accord](https://accord.benhattab.pro).

## Install

Spring Boot 4.1, Java 17+. Next to your web starter:

```xml
<dependency>
  <groupId>io.github.crossben</groupId>
  <artifactId>accordsync-spring-boot-starter</artifactId>
  <version>0.3.1</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

Gradle:

```kotlin
implementation("io.github.crossben:accordsync-spring-boot-starter:0.3.1")
implementation("org.springframework.boot:spring-boot-starter-webmvc")
```

The starter brings `spring-boot-starter-jdbc` (HikariCP) and the PostgreSQL driver.

## Use

Define one `ServerDefinition` bean; the starter does the rest.

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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SyncConfig {
    @Bean
    public ServerDefinition accordDefinition() {
        Schema schema = Schema.define(Map.of(
                "dossier", Map.of("agent", lww(), "visits", counter(), "docs", set(), "status", conflict())));
        return AccordServer.define(d -> d
                .schema(schema)
                .scope("dossier", r -> r.string("agent") == null ? List.of() : List.of("agent:" + r.string("agent")))
                .access(claims -> Access.readWrite("agent:" + ((JsonString) claims.get("sub")).value()))
                .auth(Auth.jwks("https://auth.example.com/.well-known/jwks.json")
                        .issuer("https://auth.example.com/")
                        .audience("accord")));
    }
}
```

```properties
spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/accord
spring.datasource.username=accord
spring.datasource.password=accord
```

The auto-configuration is active when the app has a `ServerDefinition` bean and a `DataSource`
bean. It creates an `AccordServer` on that `DataSource` and registers a plain servlet on
`<prefix>/v1/*` and `<prefix>/health`; Spring MVC never sees those requests, so CORS comes only from
the definition's `cors(origins)`. Every bean is `@ConditionalOnMissingBean`: supply your own
`AccordServer` to replace it.

## Properties

| Property | Default | |
| --- | --- | --- |
| `accord.enabled` | `true` | `false`: no Accord beans at all. |
| `accord.path-prefix` | `""` | `/sync` serves `/sync/v1/push`, `/sync/v1/pull`, `/sync/health`. Clients then use `https://your-app/sync`. |
| `accord.migrate-on-startup` | `true` | Run pending migrations when the server bean is created. |
| `accord.compaction.enabled` | `true` | Compact on a daemon thread every `compaction().intervalMs()` of the definition (default hourly; nothing when it is 0). |

Migrations use the TypeScript server's ledger and advisory lock, so several instances can start at
once, and a database migrated by any Accord server is up to date here. Compaction takes an exclusive
advisory lock, so instances compacting at the same time do not conflict. Rate limits are kept in
memory, per instance.

## Notes

- With Actuator (`spring-boot-starter-actuator`), an "accord" component in `/actuator/health`
  reports what `/health` answers: UP, or DOWN when the database is unreachable.
- With Spring Security, let the sync paths through your filter chain: the server checks the JWT
  itself. For example
  `http.authorizeHttpRequests(a -> a.requestMatchers("/v1/**", "/health").permitAll())` and
  `csrf(c -> c.ignoringRequestMatchers("/v1/**"))`.
- Size the Hikari pool above the definition's `maxConcurrentPushes` (default 8).
- `examples/spring-boot-app` in this repository serves the conformance profile and passes the
  shared server conformance suite in CI. Its control API is for tests only.

Docs: [accord.benhattab.pro/docs/java](https://accord.benhattab.pro/docs/java/) ·
Source: [crossben/accordsync-java](https://github.com/crossben/accordsync-java) · Licence: Apache-2.0
