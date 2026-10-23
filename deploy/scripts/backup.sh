#!/bin/sh
# Backs up the FamilyHub database to deploy/backups/familyhub-YYYYMMDD-HHMMSS.dump (pg_dump custom format)
# and keeps the newest 14 backups. Run on the server host, from any directory:
#   deploy/scripts/backup.sh
# FAMILYHUB_BACKUP_DIR overrides the backup directory. Copy backups off the server regularly.
set -eu

DEPLOY_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
BACKUP_DIR=${FAMILYHUB_BACKUP_DIR:-$DEPLOY_DIR/backups}
KEEP=14

umask 077
mkdir -p "$BACKUP_DIR"
cd "$DEPLOY_DIR"

name="familyhub-$(date +%Y%m%d-%H%M%S).dump"
partial="$BACKUP_DIR/.$name.partial"
trap 'rm -f "$partial"' EXIT

docker compose exec -T postgres pg_dump -U familyhub -d familyhub -Fc >"$partial"
# A custom-format dump starts with "PGDMP"; anything else means pg_dump wrote no dump.
if [ "$(head -c 5 "$partial")" != "PGDMP" ]; then
    echo "backup failed: pg_dump produced no dump" >&2
    exit 1
fi
mv "$partial" "$BACKUP_DIR/$name"
echo "backup written: $BACKUP_DIR/$name"

# Keep the newest $KEEP backups: names sort by time, so the glob lists them oldest first.
set -- "$BACKUP_DIR"/familyhub-*.dump
while [ $# -gt "$KEEP" ]; do
    rm -f "$1"
    echo "old backup removed: $(basename -- "$1")"
    shift
done
