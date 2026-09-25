# Data Model: Chaîne de build, CI/CD et déploiement sur VPS

No change to the application's database schema. The "entities" of this feature are operational artifacts: where
they live, what identifies them, and the rules they follow.

## Version publiée (published version)

A pair of container images pushed together by `ci.yml` (research R3).

| Field | Where | Rule |
|---|---|---|
| tag | image tag on both `rfid-backend` and `rfid-web` | `<branch>-<7-char sha>`; unique per commit and branch |
| branch | tag prefix | `main` or `dev`; only `main-*` can be deployed |
| commit | OCI label `org.opencontainers.image.revision` | full SHA of the source commit |
| source | OCI label `org.opencontainers.image.source` | repository URL |
| created | registry push date | set by GHCR |

Rules:
- Exists only if the `test` job passed on the same commit (FR-005).
- Both images of a tag are published by the same run; a tag missing either image is not deployable.
- Moving tags `main` / `dev` point to the newest version of each branch; they are never used to deploy.

## Déploiement (deployment)

One run of `deploy.yml` (research R2, R6).

| Field | Where | Rule |
|---|---|---|
| version | workflow input `version` | must match `^main-[0-9a-f]{7}$` and exist in GHCR |
| actor | workflow run | GitHub user who triggered it |
| date | workflow run | start/end timestamps |
| result | workflow conclusion + job summary | `success` only if the health check passed |
| previous version | `/opt/vegelink/state/previous` on the VPS | written before switching |

State transitions:

```text
requested ──(version invalid or missing)──────────────► refused (VPS untouched)
requested ──(queued behind another run)──► waiting ──► running
running ──(pre-deploy backup fails, exit 7)───────────► failed (nothing changed)
running ──(pull or compose fails)─────────────────────► failed (previous containers still running if not yet replaced)
running ──(containers replaced, health OK ≤120 s)─────► succeeded   (state/current = version)
running ──(containers replaced, health KO after 120 s)► failed      (state/current = version, operator redeploys state/previous)
```

A rollback is a deployment whose version is older than `state/current` (FR-014).

## Environnement (environment)

The production VPS.

| Field | Where |
|---|---|
| domain | `SITE_ADDRESS=vegelink.apolog.fr` in `/opt/vegelink/.env` |
| current version | `APP_VERSION` in `/opt/vegelink/.env`, mirrored in `/opt/vegelink/state/current` |
| configuration and secrets | `/opt/vegelink/.env` (see [contracts/configuration.md](contracts/configuration.md)) |
| persistent data | Docker volumes `db_data` (PostgreSQL), `caddy_data` (certificates), `caddy_config` |

## Sauvegarde (backup)

One object in the external bucket (research R8).

| Field | Where | Rule |
|---|---|---|
| name | object key | `vegelink-<YYYYMMDDTHHMMSSZ>-<version>.dump` |
| date | in the name (UTC) and object mtime | at least one < 26 h old, else `backup-check.yml` fails |
| version | in the name | app version running when dumped; restore on the same or a later version |
| content | `pg_dump -Fc` of the whole database | restorable with `pg_restore --clean --if-exists` |
| expiry | bucket lifecycle rule | 30 days after creation (≥ 14 required by FR-017) |
