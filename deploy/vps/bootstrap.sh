#!/usr/bin/env bash
# Prepares a fresh Debian 12 / Ubuntu 24.04 VPS for vegelink (spec 009, contracts/vps-scripts.md). Idempotent.
# Usage, from a copy of deploy/vps/ on the VPS:
#   sudo ./bootstrap.sh --admin-user <name> --deploy-key "<ssh public key of the GitHub deploy workflow>"
set -euo pipefail

readonly DIR=/opt/vegelink
HERE=$(cd "$(dirname "$0")" && pwd)
readonly HERE
# Installed from the folder bootstrap.sh runs from: copy the whole deploy/vps/ folder, not bootstrap.sh alone.
readonly SCRIPTS=(deploy.sh backup.sh restore.sh)
readonly UNITS=(vegelink-backup.service vegelink-backup.timer)

log() { echo "$(date -u +%H:%M:%SZ) $*"; }
die() { echo "ERROR: $*" >&2; exit 2; }

admin_user=""
deploy_key=""
while [ $# -gt 0 ]; do
    case $1 in
        --admin-user) admin_user=${2:-}; shift 2 ;;
        --deploy-key) deploy_key=${2:-}; shift 2 ;;
        *) die "unknown argument $1" ;;
    esac
done

[ "$(id -u)" -eq 0 ] || die "run as root (sudo)"
if [ -z "$admin_user" ] || [ -z "$deploy_key" ]; then
    die "usage: bootstrap.sh --admin-user <name> --deploy-key \"<pubkey>\""
fi
# Key-only SSH is enforced below: refuse to go on unless the admin can already log in with a key and use sudo.
admin_home=$(getent passwd "$admin_user" | cut -d: -f6) || die "user $admin_user does not exist"
[ -s "$admin_home/.ssh/authorized_keys" ] || die "$admin_home/.ssh/authorized_keys is empty: add your key first"
[[ " $(id -nG "$admin_user") " == *" sudo "* ]] || die "$admin_user is not in group sudo"
echo "$deploy_key" | ssh-keygen -l -f - >/dev/null 2>&1 || die "--deploy-key is not a valid SSH public key"
for file in "${SCRIPTS[@]}" "${UNITS[@]}"; do
    [ -f "$HERE/$file" ] || die "$HERE/$file is missing: copy the whole deploy/vps/ folder (scp -r deploy/vps …) and run bootstrap.sh from it"
done

# shellcheck source=/dev/null
. /etc/os-release
case $ID in debian|ubuntu) ;; *) die "unsupported distribution $ID" ;; esac

log "installing packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -q
apt-get install -y -q ca-certificates curl ufw unattended-upgrades rclone
if ! command -v docker >/dev/null; then
    install -m 0755 -d /etc/apt/keyrings
    curl -fsSL "https://download.docker.com/linux/$ID/gpg" -o /etc/apt/keyrings/docker.asc
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc]" \
        "https://download.docker.com/linux/$ID $VERSION_CODENAME stable" > /etc/apt/sources.list.d/docker.list
    apt-get update -q
    apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

log "firewall: web and SSH only (FR-010a)"
# Only the web service publishes ports: Docker-published ports bypass ufw, so nothing else may publish.
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

# Caddy needs ports 80 and 443: an old stack or a web server installed on the host would make the deployment fail.
busy=$(ss -ltnpH '( sport = :80 or sport = :443 )' | grep -v docker-proxy || true)
if [ -n "$busy" ]; then
    log "WARNING: ports 80/443 are already used by:"
    echo "$busy"
    log "stop them before deploying: 'sudo docker compose down' in an old stack's folder," \
        "or 'sudo systemctl disable --now nginx' (or apache2)"
fi

log "SSH: keys only, no root login (FR-010b)"
# 10- sorts before the 50-cloud-init.conf of cloud images: sshd keeps the first value it reads.
cat > /etc/ssh/sshd_config.d/10-vegelink.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
sshd -t
systemctl reload ssh || systemctl restart ssh

log "automatic security updates (FR-010c)"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'EOF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
EOF

log "deploy user for the GitHub workflow (FR-011c)"
if ! id deploy >/dev/null 2>&1; then
    useradd --system --create-home --shell /bin/bash deploy
fi
usermod -p '*' deploy # no password, but not locked: key login works
install -d -o deploy -g deploy -m 0700 /home/deploy/.ssh
# sshd expands SSH_ORIGINAL_COMMAND (the version) before sudo runs; sudo would drop it from the environment.
# shellcheck disable=SC2016
printf 'command="sudo %s/bin/deploy.sh \\"$SSH_ORIGINAL_COMMAND\\"",restrict %s\n' "$DIR" "$deploy_key" \
    > /home/deploy/.ssh/authorized_keys
chown deploy:deploy /home/deploy/.ssh/authorized_keys
chmod 0600 /home/deploy/.ssh/authorized_keys
echo "deploy ALL=(root) NOPASSWD: $DIR/bin/deploy.sh" > /etc/sudoers.d/vegelink-deploy.tmp
chmod 0440 /etc/sudoers.d/vegelink-deploy.tmp
visudo -cf /etc/sudoers.d/vegelink-deploy.tmp >/dev/null
mv /etc/sudoers.d/vegelink-deploy.tmp /etc/sudoers.d/vegelink-deploy

log "installing scripts into $DIR/bin"
install -d -o root -g root -m 0755 "$DIR" "$DIR/bin" "$DIR/state"
for script in "${SCRIPTS[@]}"; do
    install -o root -g root -m 0755 "$HERE/$script" "$DIR/bin/$script"
done

log "daily backup timer"
for unit in "${UNITS[@]}"; do
    install -o root -g root -m 0644 "$HERE/$unit" "/etc/systemd/system/$unit"
done
systemctl daemon-reload
systemctl enable --now vegelink-backup.timer

[ -f "$DIR/.env" ] || log "next: create $DIR/.env from .env.example (chmod 600), then run the deploy workflow"
log "bootstrap done: $(cd "$DIR/bin" && echo *) installed in $DIR/bin"
