#!/usr/bin/env bash
# The Java client fleet: PostgreSQL in Docker, the real Accord server from npm, then the JUnit tests
# (Java devices alone over HTTP, and Java devices next to TypeScript devices on a lossy network).
#   interop/client-fleet/run.sh                      (from anywhere)
#   ACCORD_INTEROP_SEEDS=1,2,3,4,5 interop/client-fleet/run.sh
#   interop/client-fleet/run.sh -Dtest=HttpTest      (extra arguments go to Maven)
# Needs accordsync-client 0.3.2 in the local Maven repository: `./mvnw -q install -DskipTests` at java/.
# Set ACCORD_DATABASE_URL to use a PostgreSQL that is already running (CI does).
# ACCORD_PORT / ACCORD_TEST_PORT pick the server ports (default 8721 / 8722).
set -euo pipefail
cd "$(dirname "$0")"
here="$(pwd)"

export ACCORD_PORT="${ACCORD_PORT:-8721}"
export ACCORD_TEST_PORT="${ACCORD_TEST_PORT:-8722}"
export ACCORD_URL="http://localhost:$ACCORD_PORT"
export ACCORD_TEST_URL="http://localhost:$ACCORD_TEST_PORT"

server=""
compose=""
cleanup() {
  if [[ -n "$server" ]]; then
    kill "$server" 2>/dev/null || true
    for _ in $(seq 1 50); do kill -0 "$server" 2>/dev/null || break; sleep 0.1; done
  fi
  if [[ -n "$compose" ]]; then docker compose -f "$here/docker-compose.yml" down >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

if [[ -z "${ACCORD_DATABASE_URL:-}" ]]; then
  compose=1
  docker compose up -d --wait
  export ACCORD_DATABASE_URL=postgres://accord:accord@localhost:55631/accord
fi

(cd node && npm ci --no-audit --no-fund)
node node/server.mjs > "$here/server.log" 2>&1 &
server=$!
for _ in $(seq 1 60); do curl -sf "$ACCORD_URL/health" >/dev/null && break; sleep 0.5; done
curl -sf "$ACCORD_URL/health" >/dev/null || { echo "server did not start" >&2; cat "$here/server.log" >&2; exit 1; }

../../mvnw -B -f "$here/pom.xml" test "$@"
