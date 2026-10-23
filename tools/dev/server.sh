#!/usr/bin/env bash
# Local server for manual checks (emulator walkthroughs, curl). Uses its own database `lares_e2e`
# in the dev PostgreSQL container, so it never touches the main dev database.
#
#   tools/dev/server.sh start     # dev DB up, build, start in background, wait until ready
#   tools/dev/server.sh restart   # stop + start (rebuilds; keeps data)
#   tools/dev/server.sh reset     # stop, drop the e2e database, start fresh (setup code DEVSETUP01)
#   tools/dev/server.sh stop | status | log
#
# Env: LARES_PORT (default 8080), LARES_E2E_DB (default lares_e2e).
# Log: build/dev/server.log. Setup code is fixed: DEVSETUP01 (shown in the app as DEVSE-TUP01).
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build/dev"
LOG="$OUT/server.log"
PIDF="$OUT/server.pid"
PORT="${LARES_PORT:-8080}"
DB="${LARES_E2E_DB:-lares_e2e}"
COMPOSE=(docker compose -f "$ROOT/deploy/dev/docker-compose.yml")
mkdir -p "$OUT"

java_home() {
  local h
  h="$(sed -n 's/^org.gradle.java.home=//p' "$HOME/.gradle/gradle.properties" 2>/dev/null | head -1)"
  [ -n "$h" ] || h="$(/usr/libexec/java_home -v 21 2>/dev/null)"
  echo "$h"
}

db_port() {
  # Port of the running dev DB container; start it if needed (5432, or 5434 when 5432 is taken).
  local p
  p="$("${COMPOSE[@]}" port postgres 5432 2>/dev/null | sed 's/.*://')"
  if [ -z "$p" ]; then
    p="${LARES_DEV_DB_PORT:-}"
    if [ -z "$p" ]; then
      if lsof -iTCP:5432 -sTCP:LISTEN >/dev/null 2>&1; then p=5434; else p=5432; fi
    fi
    LARES_DEV_DB_PORT=$p "${COMPOSE[@]}" up -d --wait >/dev/null 2>&1 || { echo "dev DB failed to start"; exit 1; }
  fi
  echo "$p"
}

psql_dev() { "${COMPOSE[@]}" exec -T postgres psql -U lares -d postgres -qAt -c "$1"; }

running_pid() {
  local pid
  pid="$(lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null | head -1)"
  echo "$pid"
}

stop() {
  local pid
  pid="$(running_pid)"
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null
    for _ in $(seq 1 20); do [ -z "$(running_pid)" ] && break; sleep 0.5; done
    echo "stopped server (pid $pid)"
  fi
  rm -f "$PIDF"
}

start() {
  if [ -n "$(running_pid)" ]; then
    echo "port $PORT is already in use (pid $(running_pid)); run: tools/dev/server.sh stop"
    exit 1
  fi
  local port
  port="$(db_port)"
  [ "$(psql_dev "select 1 from pg_database where datname='$DB'")" = "1" ] || psql_dev "create database $DB" >/dev/null
  (cd "$ROOT" && ./gradlew :server:installDist -q --console=plain 2>&1 | grep -E '^e: |FAILED|wrong' | head -20)
  JAVA_HOME="$(java_home)" LARES_PORT="$PORT" LARES_SETUP_CODE=DEVSETUP01 \
    LARES_PUBLIC_URL="http://localhost:$PORT" \
    LARES_DB_URL="jdbc:postgresql://localhost:$port/$DB" \
    nohup "$ROOT/server/build/install/server/bin/server" >"$LOG" 2>&1 &
  echo $! >"$PIDF"
  for _ in $(seq 1 60); do
    if curl -sf "localhost:$PORT/health/ready" >/dev/null; then
      echo "server ready: http://localhost:$PORT (db $DB on port $port, log build/dev/server.log)"
      return 0
    fi
    sleep 1
  done
  echo "server did not become ready; last log lines:"
  tail -20 "$LOG"
  exit 1
}

case "${1:-status}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  reset)
    stop
    db_port >/dev/null
    psql_dev "drop database if exists $DB with (force)" >/dev/null 2>&1
    start
    ;;
  status)
    if [ -n "$(running_pid)" ]; then
      echo "running on :$PORT (pid $(running_pid))"; curl -s "localhost:$PORT/api/v1/instance"; echo
    else
      echo "not running"
    fi
    ;;
  log) tail -n "${2:-40}" "$LOG" ;;
  *) sed -n '2,12p' "$0"; exit 2 ;;
esac
