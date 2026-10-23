#!/bin/sh
# Restores the Lares database from a backup made by backup.sh. Replaces ALL current data.
#   deploy/scripts/restore.sh deploy/backups/lares-20261023-031500.dump
# Stops the server, recreates the database, restores the dump, starts a new sync epoch (every app resyncs fully,
# see docs/architecture/sync.md, reset rule 4) and starts the server again.
set -eu

if [ $# -ne 1 ]; then
    echo "usage: $0 <backup file>" >&2
    exit 2
fi
if [ ! -f "$1" ]; then
    echo "backup file not found: $1" >&2
    exit 2
fi
BACKUP=$(CDPATH='' cd -- "$(dirname -- "$1")" && pwd)/$(basename -- "$1")
DEPLOY_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
cd "$DEPLOY_DIR"

if [ -t 0 ]; then
    printf 'This replaces all data on the server with %s.\nType "yes" to continue: ' "$BACKUP"
    read -r answer
    [ "$answer" = "yes" ] || { echo "cancelled"; exit 1; }
fi

pg() { docker compose exec -T postgres "$@"; }

echo "checking the backup"
pg pg_restore --list <"$BACKUP" >/dev/null

echo "stopping the server"
docker compose stop server

echo "recreating the database"
pg dropdb -U lares --if-exists --force lares
pg createdb -U lares -O lares lares

echo "restoring $BACKUP"
pg pg_restore -U lares -d lares --no-owner --exit-on-error <"$BACKUP"
pg psql -U lares -d lares -v ON_ERROR_STOP=1 -q \
    -c "UPDATE household_sync_state SET sync_epoch = gen_random_uuid();"

echo "starting the server"
docker compose up -d server
echo "restore done"
