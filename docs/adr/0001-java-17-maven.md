# ADR-J01: plain Java 17, built with Maven

**Status:** accepted

## Decision

The library is written in Java and compiled to Java 17 bytecode, built with Maven and its wrapper,
like the owner's other Java packages (Yoon). CI tests it on JDK 17, 21 and 25.

## Why

- Java code is consumed without friction from Java, Kotlin and Android. A Kotlin library would put
  the Kotlin standard library in every Java user's application.
- Java 17 is the baseline of Spring Boot 4 and runs on every supported Android version through
  desugaring; the client avoids APIs Android lacks (`java.net.http`, for one: the HTTP transport
  uses `HttpURLConnection`).
- Maven matches the existing publishing setup to Maven Central under `io.github.crossben`.

An Android-specific module (storage on Android's SQLite API, lifecycle) would need a Gradle build of
its own; it is out of scope for 0.3 (plan-java.md §2).
