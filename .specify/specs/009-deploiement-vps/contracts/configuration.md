# Contract: production configuration and routing

## `/opt/vegelink/.env` (root, 0600)

Committed template: `deploy/.env.example` (fake values only). Replaces both current example files.

| Variable | Used by | Example | Notes |
|---|---|---|---|
| `APP_VERSION` | compose | `main-3f2a9c1` | written by `deploy.sh`; do not edit by hand |
| `SITE_ADDRESS` | web (Caddy) | `vegelink.apolog.fr` | domain served over HTTPS |
| `ACME_EMAIL` | web (Caddy) | `ops@example.org` | Let's Encrypt account / expiry notices |
| `POSTGRES_DB` | db, app | `rfidback` | |
| `POSTGRES_USER` | db, app | `rfid_user` | |
| `POSTGRES_PASSWORD` | db, app | `change_me` | also passed to the app as `DB_PASSWORD` by compose |
| `APP_BOOTSTRAP_ADMIN_USERNAME` | app | `admin` | first Administrateur (spec 008) |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | app | `change_me` | rejected by `deploy.sh` if equal to `change_me` |
| `APP_STATION_TIME_ZONE` | app | `Europe/Paris` | optional, default `Europe/Paris` |
| `BACKUP_REMOTE` | backup.sh, restore.sh | `backup:vegelink-backups` | rclone remote + bucket; not a secret (an S3 remote cannot carry the bucket name) |

`DB_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SPRING_PROFILES_ACTIVE=prod` are derived in `deploy/compose.yml` and no
longer set by hand. `APP_CORS_ALLOWED_ORIGINS` is derived from `SITE_ADDRESS` (`https://${SITE_ADDRESS}`).

Backup storage credentials (unlike `BACKUP_REMOTE`, which only names the remote and bucket) are **not** in this file: they live in root's rclone config
(`/root/.config/rclone/rclone.conf`, remote `backup`, write key without delete).

## Compose services (`deploy/compose.yml`)

| Service | Image | Ports published on the host | Volumes |
|---|---|---|---|
| `web` | `ghcr.io/azertyny/rfid-web:${APP_VERSION}` | `80`, `443` | `caddy_data`, `caddy_config` |
| `app` | `ghcr.io/azertyny/rfid-backend:${APP_VERSION}` | none (`expose: 8080`) | none |
| `db` | `postgres:16-alpine` | none | `db_data` |

All services `restart: unless-stopped` and log with `json-file`, `max-size: 10m`, `max-file: 3` (≈ 30 MB per
service). `app` waits for `db` to be healthy (`pg_isready`).

## Public routes (Caddy, `https://vegelink.apolog.fr`)

| Path | Goes to |
|---|---|
| `http://…/.well-known/acme-challenge/*` | answered by Caddy (certificate issuance/renewal) |
| `http://…/api/*` | 403 `HTTPS required`, no redirect: an HTTP scan is never replayed over HTTPS (FR-009a) |
| `http://…/*` (other) | 308 redirect to `https://…` |
| `/api/*` | `app:8080` (including `POST /api/tags/scan` for readers) |
| `/actuator/health` | `app:8080` |
| `/actuator/*` (other) | 404 |
| everything else | static files from `front/` |

## Application settings changed in `application-prod.yml`

| Setting | Value |
|---|---|
| `app.cors.allowed-origins` default | `https://vegelink.apolog.fr` (was `https://apolog.fr`) |
| `server.servlet.session.cookie.secure` | `true` |
