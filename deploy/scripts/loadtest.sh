#!/bin/sh
# Load test of the production stack on this machine (plan M0-11, ADR-0013, ADR-0015). Builds the server image from
# `installDist`, starts the full stack (with ntfy) as https://localhost, measures the cold start, runs loadsim (a
# simulated family), samples container memory every 10 s, restarts the server once more for a warm-data cold start,
# removes everything (compose project "familyhub-loadtest") and prints a summary. Needs Docker and free ports 80/443.
#
#   ./gradlew :server:installDist :tools:loadsim:installDist
#   deploy/scripts/loadtest.sh                     # the 30-minute "family" scenario
#   deploy/scripts/loadtest.sh --duration 5m       # short acceptance run; other loadsim options pass through
#
# Environment:
#   LOADTEST_OUT        results directory (default build/loadtest/<date-time>)
#   LOADTEST_JAVA_OPTS  server JVM options instead of the image default (for tuning runs)
#   LOADTEST_SERVER_ENV extra server environment, space-separated NAME=value pairs (for tuning runs)
# Results: latency.csv, summary.json, phases.csv (loadsim), memory.csv, server-rss.csv (RSS, threads), server.log,
# environment.txt, summary.txt.
set -eu

SCRIPTS_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
DEPLOY_DIR=$(dirname -- "$SCRIPTS_DIR")
REPO_DIR=$(dirname -- "$DEPLOY_DIR")
SETUP_CODE=LDT7E5T1AB
BASE=https://localhost
LOADSIM="$REPO_DIR/tools/loadsim/build/install/loadsim/bin/loadsim"

for binary in "$REPO_DIR/server/build/install/server/bin/server" "$LOADSIM"; do
    if [ ! -x "$binary" ]; then
        echo "not built: run ./gradlew :server:installDist :tools:loadsim:installDist first" >&2
        exit 1
    fi
done
# loadsim needs Java 21; on a dev machine the shell's java may be older, Gradle's JDK is not.
if [ -z "${JAVA_HOME:-}" ] && [ -f "$HOME/.gradle/gradle.properties" ]; then
    gradle_jdk=$(sed -n 's/^org.gradle.java.home=//p' "$HOME/.gradle/gradle.properties")
    [ -z "$gradle_jdk" ] || export JAVA_HOME="$gradle_jdk"
fi

OUT=${LOADTEST_OUT:-$REPO_DIR/build/loadtest/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$OUT"
WORK=$(mktemp -d)
password=$(od -An -tx1 -N16 /dev/urandom | tr -d ' \n')
grep -v -E '^(FAMILYHUB_DOMAIN|POSTGRES_PASSWORD|FAMILYHUB_DB_PASSWORD)=' "$DEPLOY_DIR/.env.example" >"$WORK/env"
cat >>"$WORK/env" <<ENV
FAMILYHUB_DOMAIN=localhost
POSTGRES_PASSWORD=$password
FAMILYHUB_DB_PASSWORD=$password
FAMILYHUB_SETUP_CODE=$SETUP_CODE
FAMILYHUB_CADDYFILE=Caddyfile.push
FAMILYHUB_NTFY_DOMAIN=ntfy.localhost
ENV

# Tuning settings go into an extra compose file; production files stay unchanged.
server_env=$(
    [ -z "${LOADTEST_JAVA_OPTS:-}" ] || echo "      JAVA_OPTS: \"$LOADTEST_JAVA_OPTS\""
    for pair in ${LOADTEST_SERVER_ENV:-}; do echo "      ${pair%%=*}: \"${pair#*=}\""; done
)
if [ -n "$server_env" ]; then
    printf 'services:\n  server:\n    environment:\n%s\n' "$server_env" >"$WORK/override.yml"
else
    echo "services: {}" >"$WORK/override.yml"
fi

export COMPOSE_PROJECT_NAME=familyhub-loadtest
export COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml:$DEPLOY_DIR/docker-compose.build.yml:$WORK/override.yml"
export COMPOSE_ENV_FILES="$WORK/env"
export COMPOSE_PROFILES=push

sampler=""
cleanup() {
    [ -z "$sampler" ] || kill "$sampler" 2>/dev/null || true
    docker compose logs --no-color server >"$OUT/server.log" 2>&1 || true
    docker compose down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

step() { echo "==> $*"; }
fail() {
    echo "FAIL: $*" >&2
    docker compose logs --no-color --tail 40 server >&2 || true
    exit 1
}
now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }

# Milliseconds from `docker compose up/start server` until /health/ready answers 200 through Caddy.
cold_start() { # up|start
    t0=$(now_ms)
    if [ "$1" = up ]; then docker compose up -d --no-deps server >/dev/null 2>&1; else docker compose start server >/dev/null 2>&1; fi
    i=0
    until curl -fsk -o /dev/null "$BASE/health/ready" 2>/dev/null; do
        i=$((i + 1))
        [ $i -le 600 ] || fail "server not ready after 120 s"
        sleep 0.2
    done
    echo $(($(now_ms) - t0))
}

# "123.4MiB" -> 123.4 (MiB)
to_mib() { awk -v u="$1" 'BEGIN { n = u + 0; if (u ~ /G/) n *= 1024; else if (u ~ /M/) n += 0; else if (u ~ /[kK]/) n /= 1024; else n /= 1048576; printf "%.1f", n }'; }

sample_memory() {
    echo "epoch_s,container,mem_mib" >"$OUT/memory.csv"
    echo "epoch_s,rss_mib,threads" >"$OUT/server-rss.csv"
    while :; do
        now=$(date +%s)
        # shellcheck disable=SC2046 # container ids are words
        docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' $(docker compose ps -q) 2>/dev/null |
            while read -r name usage _; do
                service=$(echo "$name" | sed -E "s/^$COMPOSE_PROJECT_NAME-//; s/-[0-9]+$//")
                echo "$now,$service,$(to_mib "$usage")"
            done >>"$OUT/memory.csv"
        docker compose exec -T server cat /proc/1/status 2>/dev/null |
            awk -v t="$now" '/^VmRSS:/ { rss = $2 } /^Threads:/ { th = $2 } END { if (rss) printf "%s,%.1f,%s\n", t, rss / 1024, th }' \
                >>"$OUT/server-rss.csv"
        sleep 10
    done
}

{
    echo "date: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "host: $(uname -sm); cpus: $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo ?)"
    echo "docker: $(docker version --format '{{.Server.Version}}'); docker VM: $(docker info --format '{{.NCPU}} CPUs, {{.MemTotal}} bytes')"
    echo "LOADTEST_JAVA_OPTS: ${LOADTEST_JAVA_OPTS:-(image default)}"
    echo "LOADTEST_SERVER_ENV: ${LOADTEST_SERVER_ENV:-}"
    echo "loadsim options: $*"
} >"$OUT/environment.txt"

step "building the server image and starting PostgreSQL, Caddy and ntfy"
docker compose build --quiet server
docker compose up -d --quiet-pull --wait --wait-timeout 120 postgres >/dev/null 2>&1 || fail "PostgreSQL did not start"
docker compose up -d --no-deps caddy ntfy >/dev/null 2>&1

step "cold start (empty database, migrations included)"
cold_first=$(cold_start up)
echo "    ${cold_first} ms"

sample_memory &
sampler=$!

step "running loadsim; results in $OUT"
set +e
"$LOADSIM" --server "$BASE" --insecure --setup-code "$SETUP_CODE" --out "$OUT" "$@"
loadsim_status=$?
set -e
kill "$sampler" 2>/dev/null || true
sampler=""

step "cold start with data (server restart)"
docker compose stop server >/dev/null 2>&1
cold_restart=$(cold_start start)
echo "    ${cold_restart} ms"

# Steady state = the measured phases (steady and burst) from loadsim's phases.csv.
window=$(awk -F, 'NR > 1 && ($1 == "steady" || $1 == "burst") { if (s == "" || $2 < s) s = $2; if ($3 > e) e = $3 } END { printf "%d %d\n", s / 1000, e / 1000 }' "$OUT/phases.csv" 2>/dev/null || echo "0 0")
start_s=${window% *}
end_s=${window#* }

{
    echo "Cold start: ${cold_first} ms (empty database), ${cold_restart} ms (restart with data)"
    echo
    echo "Memory in steady state (MiB, docker stats every 10 s):"
    awk -F, -v s="$start_s" -v e="$end_s" '
        NR > 1 && $1 >= s && $1 <= e {
            k = $2; v = $3; n[k]++; sum[k] += v; tot[$1] += v
            if (!(k in mn) || v < mn[k]) mn[k] = v
            if (v > mx[k]) mx[k] = v
        }
        END {
            printf "%-10s %8s %8s %8s %8s\n", "container", "min", "avg", "max", "samples"
            for (k in n) printf "%-10s %8.1f %8.1f %8.1f %8d\n", k, mn[k], sum[k] / n[k], mx[k], n[k]
            for (t in tot) { c++; ts += tot[t]; if (c == 1 || tot[t] < tm) tm = tot[t]; if (tot[t] > tx) tx = tot[t] }
            if (c > 0) printf "%-10s %8.1f %8.1f %8.1f %8d\n", "stack", tm, ts / c, tx, c
        }' "$OUT/memory.csv"
    awk -F, -v s="$start_s" -v e="$end_s" '
        NR > 1 && $1 >= s && $1 <= e { n++; sum += $2; if (n == 1 || $2 < mn) mn = $2; if ($2 > mx) mx = $2 }
        NR > 1 { if ($2 > all) all = $2 }
        END { if (n > 0) printf "%-10s %8.1f %8.1f %8.1f %8d   (JVM process RSS; max over the whole run %.1f)\n", "server RSS", mn, sum / n, mx, n, all }' \
        "$OUT/server-rss.csv"
    echo
    echo "loadsim exit code: $loadsim_status (0 = error rate within limit)"
} | tee "$OUT/summary.txt"

[ "$loadsim_status" -eq 0 ] || exit "$loadsim_status"
step "load test finished"
