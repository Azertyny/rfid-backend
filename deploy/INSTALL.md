# RFID Back Local Installation

This deployment target is a local machine on the counting line network.

## Architecture

- `nginx` exposes the application on port `80`
- `nginx` serves the static front from `../front`
- `nginx` proxies `/api` to the Spring Boot app
- `app` is only reachable from the Docker network
- `db` is only reachable from the Docker network

From a browser on the local network:

- front: `http://<machine-or-hostname>/`
- api: `http://<machine-or-hostname>/api`

## Prerequisites

- Docker
- Docker Compose plugin
- access to the Docker daemon
- access to GHCR if you pull the backend image from GitHub Container Registry

## Environment

Create `deploy/.env` from `deploy/.env.local.example`.

Example:

```env
APP_IMAGE=ghcr.io/<owner_lc>/rfid-backend:latest
POSTGRES_DB=rfidback
POSTGRES_USER=rfid_user
POSTGRES_PASSWORD=change_me
DB_URL=jdbc:postgresql://db:5432/rfidback
DB_USERNAME=rfid_user
DB_PASSWORD=change_me
APP_CORS_ALLOWED_ORIGINS=http://localhost
APP_BOOTSTRAP_ADMIN_USERNAME=admin
APP_BOOTSTRAP_ADMIN_PASSWORD=change_me
```

`APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` create the first Administrateur account. It is
created only at a start where no enabled Administrateur exists; later restarts leave accounts untouched. Choose a
real password here, not `change_me`.

## First Start

From the project root:

```zsh
cd deploy
docker compose --env-file .env up -d
```

Check the services:

```zsh
docker compose --env-file .env ps
docker compose --env-file .env logs -f app
```

## First Login

1. Open `http://<machine-or-hostname>/`: you are redirected to `login.html`.
2. Log in with the bootstrap Administrateur account.
3. Open **Utilisateurs** (`users.html`): create the Opérateur accounts, and reset the bootstrap account's password
   to a new one (or create a personal Administrateur and disable the bootstrap one).

Opérateurs land on `reader.html` (reads and compliance) and can open the dashboard; management pages are
Administrateur-only. RFID reader devices are not affected: they keep sending their `x-api-token` as before.

The application runs over plain HTTP on the line network: passwords and the session cookie travel unencrypted on
that network. Put TLS in front of nginx if the network is not trusted.

## Update Procedure

Pull the new backend image, then recreate the services without deleting volumes:

```zsh
cd deploy
docker pull ghcr.io/<owner_lc>/rfid-backend:latest
docker compose --env-file .env up -d --force-recreate
```

This keeps the PostgreSQL volume and therefore keeps the data.

## Data Reset

If you need a full reset of the local database:

```zsh
cd deploy
docker compose --env-file .env down -v
docker compose --env-file .env up -d
```

This deletes the PostgreSQL volume.

## Notes

- The front files are served from `front/` in the repository through the nginx container.
- The API is not exposed directly on port `8080` on the host.
- If you need a stable access URL on the local network, use a fixed hostname or a local DNS entry.
