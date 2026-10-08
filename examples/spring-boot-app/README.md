# Spring Boot example app

A minimal app on `accordsync-spring-boot-starter`: one `ServerDefinition` bean (built here from the
conformance profile, `contract/conformance/profile.json`), Spring's HikariCP `DataSource`, and the
starter serves `/v1/push`, `/v1/pull` and `/health` (plus the "accord" component in
`/actuator/health`). With `ACCORD_CONTROL_ENABLED=true` it also runs the conformance suite's
**test-only** control API on a second port (ADR-J11). Not part of the root build (ADR-J11).

```sh
./mvnw -B install -DskipTests                       # from java/: the library
./mvnw -B -f examples/spring-boot-app verify          # the app (target/*.jar)
SPRING_DATASOURCE_URL=jdbc:postgresql://127.0.0.1:5432/accord \
SPRING_DATASOURCE_USERNAME=accord SPRING_DATASOURCE_PASSWORD=accord \
ACCORD_CONTROL_ENABLED=true ACCORD_PROFILE=contract/conformance/profile.json \
  java -jar examples/spring-boot-app/target/accordsync-example-spring-boot-app-0.3.1.jar
# then, from the Accord repository's app/:
ACCORD_URL=http://127.0.0.1:8731 ACCORD_CONTROL_URL=http://127.0.0.1:8732 \
  pnpm --filter @accordsync/conformance test
```

Ports: sync 8731 (`ACCORD_PORT`), control 8732 (`ACCORD_CONTROL_PORT`).
