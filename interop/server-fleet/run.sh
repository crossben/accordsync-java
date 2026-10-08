#!/usr/bin/env bash
# The mixed-server fleet: one PostgreSQL, the TypeScript reference server (Accord workspace
# sources) and the Java server (tools/ConformanceServer.java) on it at once, Java and TypeScript
# devices on both.
#   interop/server-fleet/run.sh                          (needs Docker, Node 22+, pnpm install in the app)
#   ACCORD_FLEET_SEEDS=1,2,3,4,5 interop/server-fleet/run.sh
#   interop/server-fleet/run.sh -Dtest='MixedServerFleetTest#mixedServerFleet'   (extra Maven args)
# ACCORD_DATABASE_URL: a PostgreSQL to use instead of starting one (CI); the tests create and drop
#   the database `accord_fleet` on it.
# ACCORD_APP_DIR: the Accord workspace (default ../../../app; CI: the accordsync checkout).
# Per seed, the tests recreate the database, migrate it with one implementation (even seeds
# TypeScript, odd Java), check the other has nothing to do, start both servers (ports 8741/8742
# TypeScript, 8743/8744 Java), run the fleet and stop them (by PID).
set -euo pipefail
cd "$(dirname "$0")"
here="$(pwd)"
java_dir="$(cd ../.. && pwd)"
export ACCORD_APP_DIR="${ACCORD_APP_DIR:-$(cd "$java_dir/../app" && pwd)}"

container=""
mkdir -p "$here/.logs"
pidfile="$here/.logs/servers.pid"
cleanup() {
  # Servers the tests started and could not stop (Maven killed): by PID.
  if [[ -f "$pidfile" ]]; then
    while read -r pid; do kill "$pid" 2>/dev/null || true; done <"$pidfile"
    rm -f "$pidfile"
  fi
  if [[ -n "$container" ]]; then docker rm -f "$container" >/dev/null 2>&1 || true; fi
}
trap cleanup EXIT

if [[ -z "${ACCORD_DATABASE_URL:-}" ]]; then
  container=accord-jsf-pg
  port="${ACCORD_FLEET_PG_PORT:-55651}"
  docker rm -f "$container" >/dev/null 2>&1 || true
  docker run -d --name "$container" -e POSTGRES_USER=accord -e POSTGRES_PASSWORD=accord \
    -e POSTGRES_DB=accord -p "$port:5432" postgres:16-alpine >/dev/null
  for _ in $(seq 1 60); do
    docker exec "$container" pg_isready -U accord -h 127.0.0.1 >/dev/null 2>&1 && break
    sleep 0.5
  done
  export ACCORD_DATABASE_URL="postgres://accord:accord@127.0.0.1:$port/accord"
fi

[[ -d "$ACCORD_APP_DIR/conformance/node_modules" ]] || (cd "$ACCORD_APP_DIR" && pnpm install --frozen-lockfile)

# The library, then the Java server's classpath (as tools/conformance-server.sh builds it).
cd "$java_dir"
./mvnw -q -B -DskipTests install
./mvnw -q -B -pl accordsync-server dependency:build-classpath -Dmdep.includeScope=runtime \
  -Dmdep.outputFile=target/conformance-cp.txt
export ACCORD_SERVER_CP="$java_dir/accordsync-server/target/classes:$java_dir/accordsync-core/target/classes:$(cat accordsync-server/target/conformance-cp.txt)"

./mvnw -B -f interop/server-fleet/pom.xml test "$@"
