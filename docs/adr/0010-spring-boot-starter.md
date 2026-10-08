# ADR-J10: the Spring Boot starter

**Status:** accepted (J5)

## Decisions

1. **One module, `accordsync-spring-boot-starter`**, holding the auto-configuration (no separate
   `-autoconfigure` artifact: there is nothing to reuse without the starter). It is registered in
   `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`, runs after
   `DataSourceAutoConfiguration` (by name), and is active only when the app defines a
   `ServerDefinition` bean **and** has a `DataSource` bean, and `accord.enabled` is not `false`.
   The starter brings `spring-boot-starter-jdbc` (HikariCP) and the PostgreSQL driver; the web
   server comes from the app (`spring-boot-starter-webmvc`).
2. **A plain servlet, not a controller.** `AccordServlet` is registered with a
   `ServletRegistrationBean` on `<prefix>/v1/*` and `<prefix>/health` and only translates to
   `AccordServer.handle(HttpRequest)` (ADR-J06): raw body bytes (read up to one byte past
   `maxBodyBytes`, so the server answers 413 itself), every header, the raw query. Spring MVC
   never sees these requests: an MVC handler would have answered CORS preflights through Spring's
   own CORS processor, unknown `/v1/*` paths with Spring's error page and bodies through message
   converters. CORS therefore comes only from the definition (`cors(origins)`).
3. **`accord.path-prefix` defaults to `""`**: the same URLs as every other Accord server
   (`/v1/push`, `/v1/pull`, `/health`), so clients and the conformance suite need no change.
   `accord.path-prefix=/sync` serves `/sync/v1/push` etc. (it must start with `/`; a trailing
   slash is dropped). The app's own `server.servlet.context-path` applies on top.
4. **Migrations run when the `AccordServer` bean is created** (`accord.migrate-on-startup`, default
   `true`), so the app fails fast on a database it cannot migrate, and the servlet never serves an
   unmigrated database. The Kysely ledger and advisory lock (ADR-J07) make concurrent startups of
   several instances safe.
5. **Background compaction** (`AccordCompactionScheduler`, a `SmartLifecycle`): a single daemon
   thread with a fixed delay of the definition's `compaction().intervalMs()`; nothing when that is 0
   or less (the conformance profile) or when `accord.compaction.enabled=false`. It does not use
   `@EnableScheduling`, so it never turns on scheduling for the app.
6. **Rate limits stay in memory** (the server's token bucket, ADR-J06 point 7): per instance.
7. **Actuator**: when `spring-boot-health` is on the classpath, an `accordHealthIndicator`
   ("accord") reports what the server's own `/health` answers (UP, or DOWN when the database is
   unreachable). It is optional; the starter's `/health` works without Actuator.
8. **No Spring Security assumptions.** With Spring Security the app must let the sync paths
   through its filter chain (the server authenticates with its own JWT check), e.g.
   `http.authorizeHttpRequests(a -> a.requestMatchers("/v1/**", "/health").permitAll())` and
   `csrf(c -> c.ignoringRequestMatchers("/v1/**"))`.
9. Every bean is `@ConditionalOnMissingBean`: an app can supply its own `AccordServer` (e.g. with
   a clock) or its own `accordServlet`.

## Tests

`ApplicationContextRunner` tests for the conditions, the properties and the wiring (no definition,
no `DataSource`, disabled, prefix, invalid prefix, compaction off and interval 0, migration at
startup, the app's own server, no servlet outside a servlet app, no health indicator without the
health module), and a `@SpringBootTest(RANDOM_PORT)` slice in Tomcat against a stub definition and
a database that is down (503 health, 401, 413 before auth, JSON 404, CORS from the definition,
paths outside the prefix not answered by Accord). MockMvc cannot drive a servlet that is not the
`DispatcherServlet`, hence the real container.
