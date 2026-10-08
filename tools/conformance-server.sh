#!/usr/bin/env bash
# Builds the server and runs the conformance harness (tools/ConformanceServer.java) in the
# foreground. Needs ACCORD_DATABASE_URL; ACCORD_PORT / ACCORD_CONTROL_PORT default to 8701 / 8702.
set -euo pipefail
cd "$(dirname "$0")/.."
./mvnw -q -B -pl accordsync-server -am -DskipTests install
./mvnw -q -B -pl accordsync-server dependency:build-classpath -Dmdep.includeScope=runtime \
  -Dmdep.outputFile=target/conformance-cp.txt
CP="accordsync-server/target/classes:accordsync-core/target/classes:$(cat accordsync-server/target/conformance-cp.txt)"
exec java -cp "$CP" tools/ConformanceServer.java
