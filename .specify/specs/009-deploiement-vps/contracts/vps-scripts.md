# Contract: scripts on the VPS

Installed by `deploy/vps/bootstrap.sh` into `/opt/vegelink/bin/`, owned by root, mode 0755. Sources in
`deploy/vps/`.

## `bootstrap.sh`: prepare a fresh VPS (run once as root, idempotent)

| | |
|---|---|
| Usage | `sudo ./bootstrap.sh --admin-user <name> --deploy-key "<ssh public key>"` |
| Preconditions | Debian 12 / Ubuntu 24.04; `<name>` exists and has at least one key in `~/.ssh/authorized_keys` (else exit 2 before touching sshd) |
| Effects | Docker Engine + Compose; ufw 22/80/443; sshd key-only, no root login; unattended security upgrades; user `deploy` with the restricted key; sudoers rule; `/opt/vegelink/{bin,state}`; scripts; `vegelink-backup.{service,timer}` enabled |
| Re-run | safe; converges to the same state |

## `deploy.sh <version>`: deploy or roll back

Called through the `deploy` user's forced command `sudo /opt/vegelink/bin/deploy.sh "$SSH_ORIGINAL_COMMAND"` (the
version sent by the workflow becomes `$1`), or directly by an admin: `sudo /opt/vegelink/bin/deploy.sh main-3f2a9c1`.

| Exit | Meaning |
|---|---|
| 0 | version running and healthy (internal + public HTTPS health check) |
| 2 | invalid argument (not `^main-[0-9a-f]{7}$`) |
| 3 | another deploy holds the lock (should not happen behind `deploy.yml` concurrency) |
| 4 | pull failed (version missing, registry unreachable); running version untouched |
| 5 | `/opt/vegelink/.env` missing or lacks a required variable |
| 6 | containers started but health not OK within 120 s; last 100 lines of `app` logs printed |
| 7 | pre-deploy backup failed (run only when `db` is running); nothing changed |

Output: one line per step, prefixed with a UTC time; final line `DEPLOYED <version> (previous: <version>)` or
`FAILED <exit code>: <reason>`. State files: `/opt/vegelink/state/current`, `/opt/vegelink/state/previous`, written
as soon as the containers are switched (also when the health check then fails), so `previous` stays the last good one.

## `backup.sh`: daily backup (systemd timer, 03:00 Europe/Paris)

| | |
|---|---|
| Effect | `pg_dump -Fc` of the `db` service → uploaded as `vegelink-<YYYYMMDDTHHMMSSZ>-<version>.dump` to `BACKUP_REMOTE` (e.g. `backup:vegelink-backups`) |
| Exit | 0 uploaded; non-zero on dump or upload failure (logged in the journal; `backup-check.yml` reports it) |
| Leaves on disk | nothing (temporary file removed) |

## `restore.sh <object name | latest>`: restore a backup

| | |
|---|---|
| Effect | downloads the dump; stops `app`; `pg_restore --clean --if-exists`; starts `app`; waits for health |
| Confirmation | asks to type the object name unless `--yes` |
| Exit | 0 restored and healthy; non-zero otherwise, with the step that failed |
