---

description: "Task list for 009 — Chaîne de build, CI/CD et déploiement sur VPS"
---

# Tasks: Chaîne de build, CI/CD et déploiement sur VPS

**Input**: Design documents from `.specify/specs/009-deploiement-vps/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: No new automated test suite is requested. US1 *is* a test fix (FR-002) and its tasks include the run that
proves it. Delivery tooling is validated by `shellcheck`/`docker compose config` in CI and by the scenarios of
[quickstart.md](quickstart.md), cited in each checkpoint.

**Organization**: Tasks are grouped by user story so each story can be delivered and checked on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: User story from spec.md (US1–US5)
- Paths are relative to the repository root.

## Conventions used by every task

- Image names: `ghcr.io/azertyny/rfid-backend` and `ghcr.io/azertyny/rfid-web`; tag `<branch>-<7-char sha>` (research R3).
- VPS layout: `/opt/vegelink/{bin,state}`, config `/opt/vegelink/.env` (root, 0600); variables exactly as in
  [contracts/configuration.md](contracts/configuration.md).
- Shell scripts: `#!/usr/bin/env bash`, `set -euo pipefail`, log lines prefixed with a UTC timestamp, exit codes as in
  [contracts/vps-scripts.md](contracts/vps-scripts.md); must pass `shellcheck`.
- Pin third-party GitHub Actions to a major version tag (`@v4`, …), as `docker-image.yml` does today.
- Build and test in a copy of the repo or with the VS Code Java extension paused (see `CLAUDE.md`): it races Maven on
  `target/classes`.
- Operator tasks (things done outside the repository) are marked **(operator)**; their file path is the doc that
  describes them.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: directories and the single config template everything else refers to.

- [X] T001 Create directories `deploy/web/` and `deploy/vps/` (with a `.gitkeep` if empty at commit time)
- [X] T002 Rewrite `deploy/.env.example` with exactly the variables of [contracts/configuration.md](contracts/configuration.md) (`APP_VERSION`, `SITE_ADDRESS=vegelink.apolog.fr`, `ACME_EMAIL`, `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `APP_BOOTSTRAP_ADMIN_USERNAME`, `APP_BOOTSTRAP_ADMIN_PASSWORD`, `APP_STATION_TIME_ZONE`), placeholder values only (`change_me`, `ops@example.org`), one comment line per variable saying who uses it; note that `APP_VERSION` is written by `deploy.sh`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: nothing in this feature is shared by all stories beyond Phase 1; US1 itself is the blocking story (no
image is published until CI is green). Go straight to Phase 3.

---

## Phase 3: User Story 1 - Une intégration continue fiable (Priority: P1) 🎯 MVP

**Goal**: CI builds and tests every PR and merge on `dev`/`main`, gives the same verdict as a local run, and names failing tests.

**Independent Test**: quickstart §1: the class order that failed on CI passes locally; a PR to `dev` is green in < 10 min; a deliberately false assertion turns it red with the test name shown.

### Implementation for User Story 1

- [X] T003 [US1] Fix the order dependency in `src/test/java/com/rfidback/security/AuthFlowSecurityTest.java` (research R1): add `@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)` to the class with a one-line comment explaining that `spring-security-test`'s `csrf()` replaces the shared `CsrfFilter` repository for the rest of the context; in `logout_invalidatesSession` replace `.with(csrf())` by the real flow (log in with `mockMvc.perform(login(...))`, take the session and `lastCookie(result, "XSRF-TOKEN")`, send the cookie and the `X-XSRF-TOKEN` header on the logout); remove the now-unused `csrf` static import if nothing else uses it
- [X] T004 [US1] In `pom.xml`, add `<runOrder>alphabetical</runOrder>` to the `maven-surefire-plugin` `<configuration>` (next to the existing `<argLine>`), so test classes run in the same order on macOS and Linux
- [X] T005 [US1] Verify the fix in a clean copy of the repo: `mvn -B surefire:test -Dtest='AccessMatrixSecurityTest,ReaderTokenVisibilityTest,AuthFlowSecurityTest' -Dsurefire.runOrder=alphabetical` (failed before T003) and `mvn -B clean verify` both pass; record the results in the PR description
- [X] T006 [US1] Create `.github/workflows/ci.yml`, job `test` only (the `publish` job comes in US2) per [contracts/pipelines.md](contracts/pipelines.md): `name: ci`; triggers `pull_request` (branches `dev`, `main`) and `push` (branches `dev`, `main`); `permissions: contents: read, checks: write, pull-requests: write`; steps: `actions/checkout@v4` with `fetch-depth: 0`, `actions/setup-java@v4` (temurin, 21, `cache: maven`), `mvn -B verify`, then `mikepenz/action-junit-report@v5` with `report_paths: target/surefire-reports/TEST-*.xml`, `if: always()`, `detailed_summary: true`, `include_passed: false`
- [X] T007 [US1] Add a `gitleaks/gitleaks-action@v2` step to the `test` job in `.github/workflows/ci.yml` (full history thanks to `fetch-depth: 0`; `GITHUB_TOKEN` env) for SC-009; if it flags the historical `change_me` placeholders, add a `.gitleaks.toml` at the repo root that allowlists only the literal value `change_me` in `deploy/.env*` files
- [X] T008 [US1] Delete `.github/workflows/docker-image.yml` (replaced by `ci.yml`; its image push moves to T014)
- [ ] T009 [US1] **(operator)** Push the branch, open the PR to `dev`, check the `ci / test` run is green and appears on the PR; then in GitHub settings add branch protection on `dev` and `main` requiring status check `test` (FR-004); document this setting in the "GitHub setup" section of `deploy/INSTALL.md` (created in T021)

**Checkpoint**: CI is green on `dev` and trustworthy. Delivers value on its own even before any VPS exists.

---

## Phase 4: User Story 2 - Déployer une version sur le VPS (Priority: P1)

**Goal**: a green merge on `main` publishes a self-contained version; the operator deploys it on demand to `https://vegelink.apolog.fr`, with HTTPS, a hardened host and data kept across deployments.

**Independent Test**: quickstart §2, §3 and §4 steps 1–4: fresh VPS set up from `deploy/INSTALL.md` only, login over HTTPS, reader scan over HTTPS recorded, a second deployment keeps the data, a bogus version is refused.

### Images and application config

- [X] T010 [P] [US2] In `src/main/resources/application-prod.yml`: change the `app.cors.allowed-origins` default to `${APP_CORS_ALLOWED_ORIGINS:https://vegelink.apolog.fr}` (FR-009b) and add `server.servlet.session.cookie.secure: true` (keep `forward-headers-strategy: framework`)
- [X] T011 [P] [US2] Update the root `Dockerfile` (research R10): add `# syntax=docker/dockerfile:1` first line; build stage uses `RUN --mount=type=cache,target=/root/.m2 mvn -B -DskipTests package` (copies only `pom.xml` and `src`, the image's `mvn` is used, no wrapper); runtime stage `eclipse-temurin:21-jre` creates and switches to a non-root user (`useradd --system --uid 10001 app`, `USER app`), sets `ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"`, keeps `EXPOSE 8080` and the `java -jar /app/app.jar` entrypoint
- [X] T012 [P] [US2] Create `deploy/web/Caddyfile` per [contracts/configuration.md](contracts/configuration.md) "Public routes": global block `{ email {$ACME_EMAIL} }`; site `{$SITE_ADDRESS}` with `encode gzip`, `handle /api/* { reverse_proxy app:8080 }`, `handle /actuator/health { reverse_proxy app:8080 }`, `handle /actuator/* { respond 404 }`, `handle { root * /srv/front; file_server }`; plus an explicit plain-HTTP site before it: `http://{$SITE_ADDRESS} { handle /api/* { respond "HTTPS required" 403 } handle { redir https://{host}{uri} 308 } }`, so a scan sent over HTTP is refused, never redirected and replayed over HTTPS (FR-009a); Caddy still answers the ACME HTTP-01 challenge on port 80 before these handlers; automatic HTTPS handles certificate renewal (FR-008, FR-009)
- [X] T013 [US2] Create `deploy/compose.yml` per [contracts/configuration.md](contracts/configuration.md) "Compose services": project name `vegelink`; `web` image `ghcr.io/azertyny/rfid-web:${APP_VERSION}`, ports `80:80` and `443:443`, env `SITE_ADDRESS`, `ACME_EMAIL`, volumes `caddy_data:/data`, `caddy_config:/config`, `depends_on: app`; `app` image `ghcr.io/azertyny/rfid-backend:${APP_VERSION}`, `expose: ["8080"]`, env `SPRING_PROFILES_ACTIVE: prod`, `DB_URL: jdbc:postgresql://db:5432/${POSTGRES_DB}`, `DB_USERNAME: ${POSTGRES_USER}`, `DB_PASSWORD: ${POSTGRES_PASSWORD}`, `APP_CORS_ALLOWED_ORIGINS: https://${SITE_ADDRESS}`, `APP_BOOTSTRAP_ADMIN_USERNAME`, `APP_BOOTSTRAP_ADMIN_PASSWORD`, `APP_STATION_TIME_ZONE: ${APP_STATION_TIME_ZONE:-Europe/Paris}`, `depends_on: db: condition: service_healthy`; `db` `postgres:16-alpine` with `POSTGRES_*`, volume `db_data:/var/lib/postgresql/data`, healthcheck `pg_isready -U $${POSTGRES_USER} -d $${POSTGRES_DB}`; every service `restart: unless-stopped` and `logging: *logging` from a top-level `x-logging: &logging { driver: json-file, options: { max-size: "10m", max-file: "3" } }` so container logs cannot fill the disk; no `ports` on `app` or `db` (FR-010)
- [X] T014 [US2] Create `deploy/web/Dockerfile` (build context = repo root): `FROM caddy:2-alpine`, `COPY front/ /srv/front/`, `COPY deploy/web/Caddyfile /etc/caddy/Caddyfile`, `COPY deploy/compose.yml /opt/vegelink/compose.yml` (research R5), OCI label `org.opencontainers.image.source`
- [X] T015 [US2] Add job `publish` to `.github/workflows/ci.yml` per [contracts/pipelines.md](contracts/pipelines.md): `if: github.event_name == 'push'`, `needs: test`, `permissions: contents: read, packages: write`; `docker/setup-buildx-action@v3`, `docker/login-action@v3` to `ghcr.io` with `GITHUB_TOKEN`; compute the lower-case owner and `TAG=${GITHUB_REF_NAME}-${GITHUB_SHA::7}`; two `docker/build-push-action@v6` steps (`context: .`, `file: Dockerfile` → `rfid-backend`; `file: deploy/web/Dockerfile` → `rfid-web`), each pushing `:${TAG}` and `:${GITHUB_REF_NAME}`, labels `org.opencontainers.image.revision=${{ github.sha }}` and `org.opencontainers.image.source`, `cache-from/cache-to: type=gha` (separate `scope` per image); final step appends `Published version: \`${TAG}\`` to `$GITHUB_STEP_SUMMARY`. No `latest` tag
- [X] T016 [US2] Add a `lint` step to the `test` job in `.github/workflows/ci.yml`: `shellcheck deploy/vps/*.sh` and `docker compose -f deploy/compose.yml --env-file deploy/.env.example config -q`

### VPS host and deploy path

- [X] T017 [P] [US2] Create `deploy/vps/deploy.sh` per [contracts/vps-scripts.md](contracts/vps-scripts.md) "deploy.sh" and [data-model.md](data-model.md) "Déploiement": version from `$1` only (the forced command of T018 passes it as a single argument); reject a missing argument or anything not matching `^main-[0-9a-f]{7}$` (exit 2), which also blocks any injected text; `exec 9>/run/vegelink-deploy.lock; flock -n 9` (exit 3); check `/opt/vegelink/.env` exists and defines every required variable, and `APP_BOOTSTRAP_ADMIN_PASSWORD` is not `change_me` (exit 5); `docker pull` both images (exit 4, running version untouched); if the `db` service is running, run `/opt/vegelink/bin/backup.sh` and exit 7 ("pre-deploy backup failed; nothing changed") if it fails, so every deployment is preceded by a backup of the data it may migrate; extract `/opt/vegelink/compose.yml` from the web image (`docker create` → `docker cp` → `docker rm`) to `/opt/vegelink/compose.yml`; copy `state/current` to `state/previous`; set `APP_VERSION=<version>` in `.env` (replace the line, `sed -i`); `docker compose -f /opt/vegelink/compose.yml --env-file /opt/vegelink/.env -p vegelink up -d --remove-orphans`; poll up to 120 s: `docker compose exec -T web wget -qO- http://app:8080/actuator/health` returns `"UP"`, then `curl -fsS https://$SITE_ADDRESS/actuator/health`; on timeout print `docker compose logs --tail 100 app` and exit 6; on success write `state/current`, run `docker image prune -af --filter until=720h`, print `DEPLOYED <version> (previous: <previous>)`
- [X] T018 [P] [US2] Create `deploy/vps/bootstrap.sh` per [contracts/vps-scripts.md](contracts/vps-scripts.md) "bootstrap.sh" and research R7, idempotent, run as root with `--admin-user <name> --deploy-key "<pubkey>"`: refuse (exit 2) unless `/home/<name>/.ssh/authorized_keys` is non-empty and `<name>` is in group `sudo`; install Docker Engine + Compose plugin from Docker's apt repo (skip if present), `systemctl enable --now docker`; `apt-get install -y ufw unattended-upgrades curl rclone`; ufw `default deny incoming`, `default allow outgoing`, allow `22/tcp`, `80/tcp`, `443/tcp`, `ufw --force enable` (FR-010a); write `/etc/ssh/sshd_config.d/10-vegelink.conf` with `PasswordAuthentication no`, `KbdInteractiveAuthentication no`, `PermitRootLogin no`, validate with `sshd -t`, then reload ssh (FR-010b); enable unattended security upgrades via `/etc/apt/apt.conf.d/20auto-upgrades` (FR-010c); create system user `deploy` (no password, not in group `docker`) with `/home/deploy/.ssh/authorized_keys` = `command="sudo /opt/vegelink/bin/deploy.sh \"$SSH_ORIGINAL_COMMAND\"",restrict <deploy-key>` (mode 600; sshd expands the variable before `sudo`, which would otherwise drop it from the environment) and `/etc/sudoers.d/vegelink-deploy` = `deploy ALL=(root) NOPASSWD: /opt/vegelink/bin/deploy.sh` (checked with `visudo -cf`) (FR-011c); create `/opt/vegelink/{bin,state}`; install every `deploy/vps/*.sh` found next to it into `/opt/vegelink/bin/` (root, 0755); install and enable `vegelink-backup.timer` if its unit files are present (US4)
- [X] T019 [US2] Create `.github/workflows/deploy.yml` per [contracts/pipelines.md](contracts/pipelines.md) "deploy.yml": `on: workflow_dispatch` with required string input `version`; `environment: production`; `concurrency: { group: production-deploy, cancel-in-progress: false }`; `permissions: contents: read, packages: read`; step "check": fail unless `version` matches `^main-[0-9a-f]{7}$`, and `docker manifest inspect ghcr.io/azertyny/rfid-backend:<v>` and `…/rfid-web:<v>` both succeed; step "deploy": write `VPS_SSH_KEY` to a 600 key file and `VPS_KNOWN_HOSTS` to `~/.ssh/known_hosts`, run `ssh -i key -o StrictHostKeyChecking=yes deploy@${{ secrets.VPS_HOST }} "<version>" | tee deploy.log` with `set -o pipefail`; step "summary" (`if: always()`): append version, `github.actor`, start/end UTC time, last line of `deploy.log` and, on failure, the whole log in a collapsed block to `$GITHUB_STEP_SUMMARY` (FR-011b, FR-012)
- [X] T020 [US2] Delete the local-server files replaced by this phase: `deploy/docker-compose.yml`, `deploy/front/default.conf` (and the empty `deploy/front/`), `deploy/.env.local.example` (FR-020)
- [X] T021 [US2] Rewrite `deploy/INSTALL.md` as the VPS runbook, part 1 (FR-019): "Architecture" (web/app/db, one domain, HTTPS only); "Prerequisites" (VPS Debian 12/Ubuntu 24.04, DNS record `vegelink.apolog.fr`, admin user with SSH key); "GitHub setup" (environment `production` with `VPS_HOST`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS` from `ssh-keyscan`; how to generate the deploy key pair; packages `rfid-backend`/`rfid-web` set to public; branch protection from T009); "First install" (copy `deploy/vps/` and `deploy/.env.example` to the VPS, run `bootstrap.sh`; **backup storage**: create a bucket at a provider other than the VPS host with a lifecycle rule "expire after 30 days", a write key **without delete** for the VPS, `sudo rclone config` to create an S3 remote named `backup` pointing at the bucket, check with `sudo rclone lsf backup:`, and say that without it every deployment after the first stops with exit 7 because `deploy.sh` backs up before switching; fill `/opt/vegelink/.env` with `chmod 600`, run the Deploy workflow with the newest `main-*` tag from the CI summary, first login and password change as in the old doc); "Deploy a version"; "Readers" (set each reader's target to `https://vegelink.apolog.fr/api/tags/scan`, token unchanged); "Troubleshooting" (DNS not pointing to the VPS → Caddy cannot get a certificate: check `docker compose -p vegelink logs web`; forgotten bootstrap admin variables → `deploy.sh` exit 5). Every command and path must exist in the repo or on the VPS
- [ ] T022 [US2] **(operator)** Rent the VPS, create the DNS record, set the GitHub secrets and package visibility, configure the backup storage from the "First install" step of `deploy/INSTALL.md` (needed before the second deployment), then run quickstart §2 and §3 and §4 steps 1–4 following only `deploy/INSTALL.md`; fix the runbook where it was wrong or incomplete

**Checkpoint**: production runs on the VPS over HTTPS and is updated only by the Deploy workflow.

---

## Phase 5: User Story 3 - Revenir en arrière après un mauvais déploiement (Priority: P2)

**Goal**: the operator puts the previous version back in < 5 min with the same workflow; a failed start is reported.

**Independent Test**: quickstart §4 steps 5–8: deploy A, deploy B, redeploy A; data intact; a version that cannot start makes the workflow red with the reason.

### Implementation for User Story 3

- [X] T023 [US3] In `.github/workflows/deploy.yml`, make the job summary show "previous version" (parsed from the `DEPLOYED … (previous: …)` line) and on failure a ready-to-use rollback hint: "Redeploy `<previous>` with this workflow"
- [X] T024 [US3] Add the "Rollback" section to `deploy/INSTALL.md`: find the previous tag (run summary or `/opt/vegelink/state/previous`), run the Deploy workflow with it; warn that a rollback across an entity change can meet a schema Hibernate already altered, in which case restore the backup `deploy.sh` took just before that deployment (the newest `vegelink-*` object whose version is the one being rolled back from; link to the "Restore" section of US4); fallback if GitHub is unavailable: `sudo /opt/vegelink/bin/deploy.sh <tag>` from the admin account
- [ ] T025 [US3] **(operator)** Run quickstart §4 steps 5–8 on the VPS and note timings against SC-005 and SC-006 in `.specify/specs/009-deploiement-vps/quickstart.md` (a "Results" line under §4)

**Checkpoint**: deploy and rollback are the same one-click action.

---

## Phase 6: User Story 4 - Sauvegarder et restaurer les données (Priority: P2)

**Goal**: a daily database dump goes to a bucket at another provider; a missing backup is reported without logging into the VPS; restore works, including on a new VPS.

**Independent Test**: quickstart §5: backup object appears; VPS key cannot delete; `backup-check.yml` green, then red when stale; restore on a second fresh VPS brings the data back.

### Implementation for User Story 4

- [X] T026 [P] [US4] Create `deploy/vps/backup.sh` per [contracts/vps-scripts.md](contracts/vps-scripts.md) "backup.sh" and [data-model.md](data-model.md) "Sauvegarde": read `APP_VERSION`, `POSTGRES_USER`, `POSTGRES_DB` from `/opt/vegelink/.env`; `docker compose -p vegelink exec -T db pg_dump -Fc -U … -d …` (runs inside the `db` container over the local socket, no password, so it does not depend on `POSTGRES_PASSWORD` in `.env`) into a `mktemp` file (trap-removed on exit); fail if the dump is empty; `rclone copyto <file> backup:vegelink-$(date -u +%Y%m%dT%H%M%SZ)-${APP_VERSION}.dump` to `BACKUP_REMOTE` from `.env` (e.g. `backup:vegelink-backups`: an rclone S3 remote cannot carry the bucket name); non-zero exit on any failure
- [X] T027 [P] [US4] Create `deploy/vps/restore.sh` per [contracts/vps-scripts.md](contracts/vps-scripts.md) "restore.sh": argument = object name or `latest` (newest `vegelink-*.dump` by `rclone lsf --format tp`); require typing the object name unless `--yes`; download to a temp file; `docker compose -p vegelink stop app`; copy the dump into `db` and `pg_restore --clean --if-exists --no-owner -U … -d …`; `docker compose -p vegelink start app`; wait up to 120 s for `http://app:8080/actuator/health` `UP` (same check as `deploy.sh`); report and exit non-zero naming the failed step
- [X] T028 [P] [US4] Create `deploy/vps/vegelink-backup.service` (`Type=oneshot`, `ExecStart=/opt/vegelink/bin/backup.sh`) and `deploy/vps/vegelink-backup.timer` (`OnCalendar=*-*-* 03:00 Europe/Paris`, `Persistent=true`, `WantedBy=timers.target`); `bootstrap.sh` (T018) installs them into `/etc/systemd/system/` and runs `systemctl enable --now vegelink-backup.timer`
- [X] T029 [P] [US4] Create `.github/workflows/backup-check.yml` per [contracts/pipelines.md](contracts/pipelines.md) "backup-check.yml": `on: schedule: cron '0 6 * * *'` and `workflow_dispatch`; `permissions: contents: read, actions: write`; a first step `gh workflow enable backup-check.yml` (env `GH_TOKEN: ${{ github.token }}`) keeps the workflow explicitly enabled; install rclone; configure an S3 remote from env (`RCLONE_CONFIG_BACKUP_TYPE=s3`, `…_PROVIDER=Other`, `…_ENDPOINT`, `…_ACCESS_KEY_ID`, `…_SECRET_ACCESS_KEY` from `BACKUP_S3_*` secrets); `rclone lsf --format tp backup:$BACKUP_S3_BUCKET --include 'vegelink-*.dump'`; fail with the newest name and its age when it is older than 26 h or when there is none; write the result to `$GITHUB_STEP_SUMMARY`
- [X] T030 [US4] Add "Backups" and "Restore" sections to `deploy/INSTALL.md`: refer to the "First install" step for the bucket, lifecycle rule and VPS write key/`backup` remote (already in place); add the read-only key for GitHub (`BACKUP_S3_ENDPOINT`, `BACKUP_S3_BUCKET`, `BACKUP_S3_READ_KEY_ID`, `BACKUP_S3_READ_SECRET` repository secrets); run a backup now (`sudo systemctl start vegelink-backup.service`), see the timer (`systemctl list-timers vegelink-backup.timer`) and logs (`journalctl -u vegelink-backup.service`); restore on the same VPS and on a new VPS (bootstrap → deploy the version in the dump name → `restore.sh latest`); alerting caveats: failure emails go to the GitHub user who last changed the `cron` line of `backup-check.yml`, and GitHub disables scheduled workflows of a public repository after 60 days without commits, so in the off-season check monthly on the Actions tab that *backup-check* is still enabled (re-enable it there if not); note that `deploy.sh` also takes a backup before each deployment and needs the `backup` rclone remote to exist once the database has data
- [ ] T031 [US4] **(operator)** Create the read-only key and set the `BACKUP_S3_*` GitHub secrets (the bucket and the VPS `backup` remote already exist from T022), re-run `bootstrap.sh` to install the timer, then run quickstart §5, including the restore drill on a second fresh VPS (FR-018, SC-008, SC-010); note the timings under a "Results" line in `.specify/specs/009-deploiement-vps/quickstart.md`

**Checkpoint**: a season of data survives the loss of the VPS.

---

## Phase 7: User Story 5 - Nettoyer la chaîne historique (Priority: P3)

**Goal**: one documented deployment path, no stale files, no secrets, dev workflow unchanged.

**Independent Test**: quickstart §6: no local-deployment files remain, every command in `deploy/INSTALL.md` exists, gitleaks is clean, `mvn spring-boot:run` works as before.

### Implementation for User Story 5

- [X] T032 [US5] Add the "Rotate a secret" section to `deploy/INSTALL.md`: database password (change it in PostgreSQL with `ALTER USER` via `docker compose -p vegelink exec db psql`, then in `.env`, then redeploy the current version), bootstrap admin variables (only used when no enabled Administrateur exists), CI deploy key (new key pair → replace the line in `/home/deploy/.ssh/authorized_keys` → update `VPS_SSH_KEY`), backup keys (provider console → `rclone config` / GitHub secrets), admin SSH keys
- [X] T033 [P] [US5] Update `CLAUDE.md`: in "Frontend" replace "served via `deploy/front` (nginx config)" with "served in production by Caddy in the `rfid-web` image (`deploy/web/`)"; add a short "Deployment" section: CI `ci.yml` publishes `rfid-backend`/`rfid-web` tagged `<branch>-<sha>`, production is `https://vegelink.apolog.fr` deployed only by the manual `deploy.yml`, runbook in `deploy/INSTALL.md`; mention in "Persistence" that production uses PostgreSQL with daily backups (`deploy/vps/backup.sh`)
- [X] T034 [P] [US5] Update `.dockerignore`: keep existing entries, add `.specify`, `.claude`, `deploy/.env` and `deploy/vps` (both Dockerfiles only need `pom.xml`, `src/`, `front/`, `deploy/web/`, `deploy/compose.yml`); check both images still build locally (`docker build .` and `docker build -f deploy/web/Dockerfile .`)
- [X] T035 [US5] Check that no reference to removed files remains: `grep -rn "docker-compose.yml\|default.conf\|env.local.example\|docker-image.yml\|api.apolog.fr\|rfid-backend:latest" --exclude-dir=.git --exclude-dir=target --exclude-dir=.specify .` returns nothing; fix any hit

**Checkpoint**: the repository describes one deployment path, the VPS.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T036 Run the full [quickstart.md](quickstart.md) once more end to end on the production VPS (§1–§6) and tick SC-001 (10 consecutive green CI runs on `dev`), SC-004 and SC-011 in the "Results" lines
- [ ] T037 [P] **(operator)** Delete the superseded remote branch `feature/mep_vps` once the VPS runs from this feature (research R11); record it in the PR description

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none.
- **US1 (Phase 3)**: none. Blocks the `publish` job of US2 (T015 adds to `ci.yml` from T006; images are only published after `test` is green).
- **US2 (Phase 4)**: after US1 (needs green CI to publish) and T002.
- **US3 (Phase 5)**: after US2 (rollback is a redeploy; needs two published versions).
- **US4 (Phase 6)**: scripts T026–T029 can be written in parallel with US2; T026 (`backup.sh`) must exist before T017 is usable, since `deploy.sh` backs up before switching; the bucket and root's `backup` rclone remote are set up in T022 (runbook "First install"), before the second deployment (the first one has no running `db` to back up); the restore drill in T031 needs a running VPS from US2.
- **US5 (Phase 7)**: T032 after T021 (same file); T033–T035 after T020.
- **Polish (Phase 8)**: after all stories.

### Within stories

- T003 → T004 → T005 (verify after both changes). T006 → T007 (same file); T008 any time after T006.
- T012, T013 → T014 (web image copies both). T011, T014 → T015. T013, T017 → T016 (lint needs the files).
- T026 → T017 (pre-deploy backup). T017, T018 → T019 → T022. T021 before T022.
- `deploy/INSTALL.md` is edited by T021, T024, T030, T032: do them in that order, not in parallel.
- `.github/workflows/ci.yml` is edited by T006, T007, T015, T016: sequential.

### Parallel Opportunities

- US2: T010, T011, T012, T017, T018 all touch different files.
- US4: T026, T027, T028, T029 are independent files and can be written while US2 is being deployed.
- US5: T033 and T034 in parallel.

## Parallel Example: User Story 2

```text
Task: "T010 [US2] application-prod.yml: CORS default + secure session cookie"
Task: "T011 [US2] Dockerfile: cache mount, non-root user, JVM memory flag"
Task: "T012 [US2] deploy/web/Caddyfile"
Task: "T017 [US2] deploy/vps/deploy.sh"
Task: "T018 [US2] deploy/vps/bootstrap.sh"
```

## Parallel Example: User Story 4

```text
Task: "T026 [US4] deploy/vps/backup.sh"
Task: "T027 [US4] deploy/vps/restore.sh"
Task: "T028 [US4] deploy/vps/vegelink-backup.service + .timer"
Task: "T029 [US4] .github/workflows/backup-check.yml"
```

## Implementation Strategy

### MVP first (US1 only)

1. T001–T009: CI green and required on PRs. Merge to `dev`. This alone ends the "CI does not work" problem.

### Incremental delivery

2. US2 → first production on the VPS (MVP of the deployment), with `backup.sh` (T026) and the backup bucket in place
   before the second deployment.
3. US4 → backups and restore drill (`backup.sh` already ships with US2 because every deployment backs up first).
4. US3 → rollback documented and drilled.
5. US5 + Polish → one clean runbook.

### Suggested PR split

- PR 1 (to `dev`): US1 (T001–T009).
- PR 2 (to `dev`): US2 + US3 code + `backup.sh` (T010–T021, T023–T024, T026).
- PR 3 (to `dev`): rest of US4 + US5 (T027–T030, T032–T035).
- Then `dev` → `main` to publish the first `main-*` version and run the operator tasks.
