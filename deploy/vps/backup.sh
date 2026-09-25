#!/usr/bin/env bash
# Dumps the production database and uploads it to the external bucket (spec 009, contracts/vps-scripts.md).
# Run daily by vegelink-backup.timer and by deploy.sh before every deployment.
# Upload target: BACKUP_REMOTE in /opt/vegelink/.env (e.g. backup:vegelink-backups), an rclone remote in root's config
# whose key can write but not delete; expiry is a lifecycle rule on the bucket.
set -euo pipefail

readonly DIR=/opt/vegelink
readonly ENV_FILE=$DIR/.env

log() { echo "$(date -u +%H:%M:%SZ) $*"; }
env_get() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1; }

remote=$(env_get BACKUP_REMOTE)
version=$(env_get APP_VERSION)
[ -n "$remote" ] || { echo "BACKUP_REMOTE is not set in $ENV_FILE" >&2; exit 1; }

dump=$(mktemp)
trap 'rm -f "$dump"' EXIT

# Runs inside the db container over the local socket: no password needed.
log "dumping the database"
docker compose -f "$DIR/compose.yml" --env-file "$ENV_FILE" -p vegelink exec -T db \
    sh -c 'pg_dump -Fc -U "$POSTGRES_USER" -d "$POSTGRES_DB"' > "$dump"
[ -s "$dump" ] || { echo "empty dump" >&2; exit 1; }

name="vegelink-$(date -u +%Y%m%dT%H%M%SZ)-${version:-unknown}.dump"
log "uploading $name to $remote"
rclone copyto --s3-no-check-bucket "$dump" "$remote/$name"
log "backup done: $name"
