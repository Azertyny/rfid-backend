#!/usr/bin/env bash
# Restores a backup from the external bucket into the running database (spec 009, contracts/vps-scripts.md).
# Usage: sudo /opt/vegelink/bin/restore.sh <object name | latest> [--yes]
# On a new VPS: bootstrap.sh, deploy the version named in the dump, then run this script.
set -euo pipefail

readonly DIR=/opt/vegelink
readonly ENV_FILE=$DIR/.env
readonly HEALTH_TIMEOUT=120

log() { echo "$(date -u +%H:%M:%SZ) $*"; }
fail() { echo "FAILED: $*" >&2; exit 1; }
env_get() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1; }
compose() { docker compose -f "$DIR/compose.yml" --env-file "$ENV_FILE" -p vegelink "$@"; }

object=${1:-}
confirmed=${2:-}
[ -n "$object" ] || fail "usage: restore.sh <object name | latest> [--yes]"
remote=$(env_get BACKUP_REMOTE)
[ -n "$remote" ] || fail "BACKUP_REMOTE is not set in $ENV_FILE"

if [ "$object" = latest ]; then
    # Names start with a UTC timestamp, so the lexical order is the chronological order.
    object=$(rclone lsf --include 'vegelink-*.dump' "$remote" | sort | tail -n 1)
    [ -n "$object" ] || fail "no backup found in $remote"
fi

if [ "$confirmed" != --yes ]; then
    echo "This replaces ALL production data with $object."
    read -r -p "Type the backup name to confirm: " answer
    [ "$answer" = "$object" ] || fail "not confirmed"
fi

dump=$(mktemp)
trap 'rm -f "$dump"' EXIT
log "downloading $object"
rclone copyto "$remote/$object" "$dump" || fail "download of $object"

log "stopping the application"
compose stop app || fail "stop app"

log "restoring into the database"
# shellcheck disable=SC2016 # the variables expand inside the db container
compose exec -T db sh -c 'pg_restore --clean --if-exists --no-owner -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
    < "$dump" || fail "pg_restore"

log "starting the application"
compose start app || fail "start app"

deadline=$((SECONDS + HEALTH_TIMEOUT))
until compose exec -T web wget -qO- http://app:8080/actuator/health 2>/dev/null | grep -q '"UP"'; do
    [ "$SECONDS" -lt "$deadline" ] || fail "application not healthy after ${HEALTH_TIMEOUT}s"
    sleep 3
done
log "restored $object"
