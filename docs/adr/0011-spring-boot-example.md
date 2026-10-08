# ADR-J11: the Spring Boot example app and its control API

**Status:** accepted (J5)

## Decisions

1. **`examples/spring-boot-app` is not a module of the root build.** It is an application
   (repackaged by `spring-boot-maven-plugin`) and its real test is the shared conformance suite,
   which needs PostgreSQL and Node; keeping it out keeps `./mvnw verify` fast and dependency-free.
   It inherits `accordsync-parent` (`relativePath ../../pom.xml`), so the library must be installed
   first (`./mvnw install -DskipTests`, then `./mvnw -f examples/spring-boot-app verify`). CI's
   `spring-boot` job builds it and runs the suite against it.
2. **The whole integration is one `ServerDefinition` bean**; this app builds it from
   `contract/conformance/profile.json` (`accord.example.profile`, env `ACCORD_PROFILE`). The
   database is Spring's own `spring.datasource.*` (HikariCP, pool of 20: the suite sends 150
   requests at once).
3. **The control API runs on a JDK `HttpServer` on a second port** (127.0.0.1,
   `accord.example.control.port`, default 8732), and exists only when
   `accord.example.control.enabled=true` (env `ACCORD_CONTROL_ENABLED=true`). A second Tomcat
   connector would have exposed the control paths on the sync port too unless filtered by port;
   a separate server cannot leak. Endpoints as `tools/ConformanceServer.java` (ADR-J09):
   `/token`, `/reset` (truncates, clears rate limits, releases a held record), `/compact`,
   `/age-device`, `/hold-record` (a pooled connection kept in an open transaction), `/held`,
   `/release`.
4. Ports for local runs: sync 8731 (`ACCORD_PORT` or `server.port`), control 8732.
