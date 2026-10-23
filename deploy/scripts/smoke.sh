#!/bin/sh
# End-to-end smoke test of the production stack on this machine (CI job deploy-smoke, or locally with Docker):
# builds the server image from `./gradlew :server:installDist`, starts the full stack (with ntfy) as
# https://localhost, performs setup, backs up, writes a tag, restores, and checks that the tag is gone.
# Needs free ports 80 and 443. Everything is removed at the end (separate compose project "familyhub-smoke").
#   ./gradlew :server:installDist && deploy/scripts/smoke.sh
set -eu

SCRIPTS_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
DEPLOY_DIR=$(dirname -- "$SCRIPTS_DIR")
REPO_DIR=$(dirname -- "$DEPLOY_DIR")
SETUP_CODE=SMK7E5T1AB
BASE=https://localhost
NTFY=https://ntfy.localhost

if [ ! -x "$REPO_DIR/server/build/install/server/bin/server" ]; then
    echo "server not built: run ./gradlew :server:installDist first" >&2
    exit 1
fi

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

export COMPOSE_PROJECT_NAME=familyhub-smoke
export COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml:$DEPLOY_DIR/docker-compose.build.yml"
export COMPOSE_ENV_FILES="$WORK/env"
export COMPOSE_PROFILES=push
export FAMILYHUB_BACKUP_DIR="$WORK/backups"

passed=no
cleanup() {
    if [ "$passed" != yes ]; then
        echo "--- smoke test FAILED; last server log lines:" >&2
        docker compose logs --no-color --tail 40 server >&2 || true
    fi
    docker compose down -v --remove-orphans >/dev/null 2>&1 || true
    rm -rf "$WORK"
}
trap cleanup EXIT
trap 'exit 1' INT TERM

step() { echo "==> $*"; }
fail() {
    echo "FAIL: $*" >&2
    exit 1
}

# curl against the stack; Caddy serves localhost with its own CA, hence -k.
api() { # METHOD PATH [BODY] [TOKEN]
    set -- "$1" "$2" "${3:-}" "${4:-}"
    if [ -n "$4" ]; then
        curl -fsSk -X "$1" -H 'Content-Type: application/json' -H 'X-FamilyHub-Protocol: 1' \
            -H "Authorization: Bearer $4" ${3:+--data "$3"} "$BASE/api/v1$2"
    else
        curl -fsSk -X "$1" -H 'Content-Type: application/json' -H 'X-FamilyHub-Protocol: 1' \
            ${3:+--data "$3"} "$BASE/api/v1$2"
    fi
}
json_string() { sed -n "s/.*\"$1\":\"\\([^\"]*\\)\".*/\\1/p"; }
uuid() { od -An -tx1 -N16 /dev/urandom | tr -d ' \n' | sed -E 's/^(.{8})(.{4}).(.{3}).(.{3})(.{12})$/\1-\2-4\3-a\4-\5/'; }

wait_ready() {
    i=0
    until curl -fsk -o /dev/null "$BASE/health/ready" 2>/dev/null; do
        i=$((i + 1))
        [ $i -le 90 ] || fail "server not ready at $BASE/health/ready"
        sleep 2
    done
}

step "starting the stack (building the server image from installDist)"
docker compose up -d --build --quiet-pull --wait --wait-timeout 240

step "HTTPS health check"
wait_ready

step "instance info before setup"
api GET /instance | grep -q '"initialized":false' || fail "instance should not be initialized"

step "setup with the known setup code"
setup_body='{"setupCode":"'$SETUP_CODE'","householdName":"Smoke","timeZone":"Europe/Berlin","currency":"EUR",
"defaultLanguage":"en","displayName":"Smoke Admin","login":"smoke","password":"smoke-password-1",
"device":{"name":"smoke test","platform":"cli"}}'
token=$(api POST /setup "$setup_body" | json_string accessToken)
[ -n "$token" ] || fail "setup returned no access token"
api GET /instance | grep -q '"initialized":true' || fail "instance should be initialized after setup"
epoch_before=$(api GET '/sync/changes?after=0' '' "$token" | json_string epoch)

step "backup"
"$SCRIPTS_DIR/backup.sh"
for backup in "$FAMILYHUB_BACKUP_DIR"/familyhub-*.dump; do :; done
[ -f "$backup" ] || fail "no backup file written"

step "creating a tag through the sync API"
tag_id=$(uuid)
mutation='{"mutations":[{"mutationId":"'$(uuid)'","type":"tag","id":"'$tag_id'","op":"upsert",
"fields":{"name":"Smoke tag","color":"blue"},"createdAt":"'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"}]}'
api POST /sync/mutations "$mutation" "$token" | grep -q '"status":"applied"' || fail "tag mutation not applied"
api GET '/sync/changes?after=0' '' "$token" | grep -q "$tag_id" || fail "tag not visible after push"

step "ntfy: anonymous access only to UnifiedPush topics"
docker compose exec -T ntfy ntfy access '*' 'up*' read-write >/dev/null
ntfy_status() { curl -sk -o /dev/null -w '%{http_code}' --resolve ntfy.localhost:443:127.0.0.1 -d smoke "$NTFY/$1"; }
[ "$(ntfy_status upSmokeTest1)" = 200 ] || fail "ntfy should accept anonymous publish to an up* topic"
[ "$(ntfy_status smoketopic)" = 403 ] || fail "ntfy should deny other topics"

step "server memory (limit 384 MiB)"
docker stats --no-stream --format '{{.Name}}: {{.MemUsage}} ({{.MemPerc}})' "$(docker compose ps -q server)"

step "restore from the backup"
"$SCRIPTS_DIR/restore.sh" "$backup"
wait_ready

step "after restore: household exists, tag is gone, new sync epoch"
changes=$(api GET '/sync/changes?after=0' '' "$token")
echo "$changes" | grep -q '"type":"household"' || fail "household missing after restore"
if echo "$changes" | grep -q "$tag_id"; then fail "tag still present after restore"; fi
epoch_after=$(echo "$changes" | json_string epoch)
[ -n "$epoch_after" ] && [ "$epoch_after" != "$epoch_before" ] || fail "sync epoch did not change on restore"

passed=yes
step "smoke test passed"
