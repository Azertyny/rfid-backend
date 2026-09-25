#!/usr/bin/env bash
# Deploys (or rolls back to) a published version on the VPS (spec 009, contracts/vps-scripts.md).
# Usage: sudo /opt/vegelink/bin/deploy.sh main-<7-char sha>
# The GitHub deploy workflow reaches it through the deploy user's forced SSH command, which passes the version as $1.
set -euo pipefail

readonly DIR=/opt/vegelink
readonly ENV_FILE=$DIR/.env
readonly COMPOSE_FILE=$DIR/compose.yml
readonly STATE=$DIR/state
readonly REGISTRY=ghcr.io/azertyny
readonly HEALTH_TIMEOUT=120
readonly REQUIRED_VARS=(SITE_ADDRESS ACME_EMAIL POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD
    APP_BOOTSTRAP_ADMIN_USERNAME APP_BOOTSTRAP_ADMIN_PASSWORD BACKUP_REMOTE)

log() { echo "$(date -u +%H:%M:%SZ) $*"; }
fail() { echo "FAILED $1: $2"; exit "$1"; }
env_get() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -n 1; }
compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" -p vegelink "$@"; }
db_running() {
    [ -n "$(docker ps -q --filter label=com.docker.compose.project=vegelink \
        --filter label=com.docker.compose.service=db --filter status=running)" ]
}
# No "| grep -q" under pipefail: grep exits early and the writer's SIGPIPE would fail the check.
app_healthy() {
    local body
    body=$(compose exec -T web wget -qO- http://app:8080/actuator/health 2>/dev/null) || return 1
    [[ $body == *'"UP"'* ]]
}
site_healthy() {
    local body
    body=$(curl -fsS "https://$1/actuator/health" 2>/dev/null) || return 1
    [[ $body == *'"UP"'* ]]
}

version=${1:-}
[[ $version =~ ^main-[0-9a-f]{7}$ ]] || fail 2 "invalid version '${version}', expected main-<7-char sha>"

exec 9>/run/vegelink-deploy.lock
flock -n 9 || fail 3 "another deployment is running"

[ -f "$ENV_FILE" ] || fail 5 "$ENV_FILE is missing (copy deploy/.env.example)"
for var in "${REQUIRED_VARS[@]}"; do
    [ -n "$(env_get "$var")" ] || fail 5 "$var is not set in $ENV_FILE"
done
[ "$(env_get APP_BOOTSTRAP_ADMIN_PASSWORD)" != change_me ] || fail 5 "APP_BOOTSTRAP_ADMIN_PASSWORD is still change_me"

log "pulling $version"
docker pull -q "$REGISTRY/rfid-backend:$version" >/dev/null || fail 4 "cannot pull rfid-backend:$version"
docker pull -q "$REGISTRY/rfid-web:$version" >/dev/null || fail 4 "cannot pull rfid-web:$version"

# A deployment may alter the schema (ddl-auto: update): back up first so it can always be undone.
if db_running; then
    log "backing up the database before switching"
    "$DIR/bin/backup.sh" || fail 7 "pre-deploy backup failed; nothing changed"
fi

log "extracting the compose file of $version"
container=$(docker create "$REGISTRY/rfid-web:$version")
docker cp "$container:/opt/vegelink/compose.yml" "$COMPOSE_FILE.new"
docker rm "$container" >/dev/null
mv "$COMPOSE_FILE.new" "$COMPOSE_FILE"

mkdir -p "$STATE"
previous=$(cat "$STATE/current" 2>/dev/null || echo none)
if grep -q '^APP_VERSION=' "$ENV_FILE"; then
    sed -i "s/^APP_VERSION=.*/APP_VERSION=$version/" "$ENV_FILE"
else
    echo "APP_VERSION=$version" >> "$ENV_FILE"
fi

log "starting $version (previous: $previous)"
up_ok=true
compose up -d --remove-orphans || up_ok=false
# Containers are (at least partly) replaced from here on: record it even on failure, so previous stays the last good one.
echo "$previous" > "$STATE/previous"
echo "$version" > "$STATE/current"
if [ "$up_ok" = false ]; then
    compose logs --tail 50 web
    compose logs --tail 50 app
    fail 8 "containers could not start (port 80/443 already in use? see sudo ss -ltnp); redeploy $previous if needed"
fi

log "waiting for the application to be healthy (up to ${HEALTH_TIMEOUT}s)"
site=$(env_get SITE_ADDRESS)
deadline=$((SECONDS + HEALTH_TIMEOUT))
until app_healthy && site_healthy "$site"; do
    if [ "$SECONDS" -ge "$deadline" ]; then
        if ! app_healthy; then
            compose logs --tail 100 app
            fail 6 "application not healthy after ${HEALTH_TIMEOUT}s; redeploy $previous if needed"
        fi
        # The app answers inside the network: the public side (DNS, ports, certificate) is what fails.
        compose logs --tail 100 web
        fail 6 "public HTTPS check on https://$site failed: check that the DNS record points to this VPS and that ports 80/443 are open (certificate not obtained yet?); redeploy $previous if needed"
    fi
    sleep 3
done

docker image prune -af --filter until=720h >/dev/null
echo "DEPLOYED $version (previous: $previous)"
