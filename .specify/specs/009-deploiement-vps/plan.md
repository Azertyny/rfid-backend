# Implementation Plan: Chaîne de build, CI/CD et déploiement sur VPS

**Branch**: `009-deploiement-vps` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/009-deploiement-vps/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Make CI trustworthy again, then deploy the whole application to one VPS at `https://vegelink.apolog.fr`.

CI has been red since spec 008 because of a test isolation bug, not a product bug: `spring-security-test`'s `csrf()`
helper permanently swaps the CSRF repository of the shared filter, so `AuthFlowSecurityTest` fails whenever another
class using `csrf()` runs first. That is the order on Linux runners but not on macOS (research R1). Fix: isolate
that test class and pin surefire's class order.

Delivery becomes three GitHub Actions workflows: `ci.yml` (test on PR and push, publish two images tagged
`<branch>-<sha>` after green tests), `deploy.yml` (manual, operator picks a `main-*` tag; SSH to a restricted
`deploy` user whose forced command runs `deploy.sh` and a health check) and `backup-check.yml` (daily freshness
check of external backups). On the VPS: Caddy (automatic HTTPS, serves the front, proxies `/api`), the Spring Boot
app and PostgreSQL in Docker Compose; the compose file ships inside the web image so every version is
self-contained. A one-time `bootstrap.sh` hardens the host (ufw, key-only SSH, unattended upgrades). A systemd timer
dumps PostgreSQL daily to S3-compatible storage at another provider, with retention by bucket lifecycle. The
local-server deployment files are removed.

## Technical Context

**Language/Version**: Java 21 (app, unchanged); Bash (VPS scripts); GitHub Actions YAML; Caddyfile

**Primary Dependencies**: Spring Boot 3.5.7 (unchanged, no new Maven dependency); Docker Engine + Compose v2;
Caddy 2 (`caddy:2-alpine`); PostgreSQL 16 (`postgres:16-alpine`, as today); rclone; GitHub Actions:
`actions/checkout`, `actions/setup-java`, `docker/login-action`, `docker/build-push-action`, `docker/metadata-action`,
`mikepenz/action-junit-report`, `gitleaks/gitleaks-action`

**Storage**: PostgreSQL 16 in a Docker volume on the VPS; backups as `pg_dump -Fc` files in an S3-compatible bucket
(provider chosen by the operator, not the VPS host); images in GHCR (public)

**Testing**: existing JUnit 5 / MockMvc suite (`test` profile, in-memory H2) run by `mvn -B verify` in CI;
shell scripts checked with `shellcheck` in CI; end-to-end validation by [quickstart.md](quickstart.md)

**Target Platform**: one Linux VPS (Debian 12 or Ubuntu 24.04 LTS) reachable at `vegelink.apolog.fr`; GitHub-hosted
`ubuntu-latest` runners

**Project Type**: web service (Spring Boot REST API) + static HTML/JS front, plus delivery tooling

**Performance Goals**: CI verdict < 10 min (SC-002); publish < 15 min after merge, deploy < 5 min after trigger
(SC-003); rollback < 5 min (SC-005); restore < 30 min (SC-008)

**Constraints**: deploy only by manual trigger (FR-011a); CI's VPS access limited to deploying (FR-011c); HTTPS
only, including reader scans (FR-008, FR-009a); API downtime per deploy ≤ 1 min (SC-006); dev workflow unchanged
(FR-021); no Flyway/Liquibase (schema still `ddl-auto: update`)

**Scale/Scope**: one station, a handful of users and readers, < 200 pickers; database well under 1 GB per season;
3 workflows, 4 VPS scripts, 2 images, ~6 files removed

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template; as in spec 008, the conventions of `CLAUDE.md` are
the gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: no Java production code beyond `application-prod.yml`; delivery files live in `deploy/` and `.github/` |
| API changes start in `api.yaml` | `CLAUDE.md` | Pass | Pass: no API change; `/actuator/health` already exists and is `permitAll` |
| `mvn clean test` and `mvn clean install` pass | `CLAUDE.md` | **Fail** on CI today | Pass (planned): R1 fixes the order-dependent test and pins the class order |
| Tests run on the `test` profile, never on `dev` | `CLAUDE.md` | Pass | Pass: CI runs the same `mvn -B verify` |

No unjustified violation; Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/009-deploiement-vps/
├── spec.md              # Feature spec (clarified 2026-09-25)
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R11
├── data-model.md        # Phase 1: version, deployment, environment, backup
├── quickstart.md        # Phase 1: end-to-end validation
├── contracts/
│   ├── pipelines.md        # ci.yml, deploy.yml, backup-check.yml
│   ├── vps-scripts.md      # bootstrap.sh, deploy.sh, backup.sh, restore.sh
│   └── configuration.md    # .env variables, compose services, public routes
├── checklists/requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
.github/workflows/
├── docker-image.yml          # DELETE
├── ci.yml                    # new: test (+ gitleaks, shellcheck, JUnit report) → publish 2 images
├── deploy.yml                # new: manual deploy/rollback of a main-* tag over restricted SSH
└── backup-check.yml          # new: daily backup freshness check

Dockerfile                    # app image: BuildKit m2 cache, non-root user, JVM memory flag
pom.xml                       # surefire <runOrder>alphabetical</runOrder>

deploy/
├── INSTALL.md                # REWRITE: VPS runbook (install, deploy, rollback, backup, restore, secret rotation)
├── .env.example              # REWRITE: variables of contracts/configuration.md, fake values
├── .env.local.example        # DELETE
├── docker-compose.yml        # DELETE (local target)
├── front/default.conf        # DELETE (nginx)
├── compose.yml               # new: production stack web/app/db
├── web/
│   ├── Dockerfile            # new: caddy:2-alpine + front/ + Caddyfile + compose.yml
│   └── Caddyfile             # new: HTTPS site, /api + /actuator/health proxy, static front
└── vps/
    ├── bootstrap.sh          # new: one-time host setup and hardening
    ├── deploy.sh             # new: pull, switch, health check, state files
    ├── backup.sh             # new: pg_dump → rclone
    ├── restore.sh            # new: rclone → pg_restore
    ├── vegelink-backup.service  # new
    └── vegelink-backup.timer    # new

src/main/resources/application-prod.yml         # CORS default → https://vegelink.apolog.fr; secure session cookie
src/test/java/com/rfidback/security/AuthFlowSecurityTest.java  # @DirtiesContext(BEFORE_CLASS), real CSRF cookie instead of csrf()
CLAUDE.md                                        # frontend serving + deployment sections updated
```

**Structure Decision**: the existing single Maven project is unchanged. All delivery tooling goes under
`.github/workflows/` and `deploy/` (`deploy/web/` for the web image, `deploy/vps/` for host-side scripts), replacing
the local-server files in place so there is one deployment path (FR-020).

## Implementation order (for `/speckit-tasks`)

1. **US1, CI green** (unblocks everything): R1 test fix + surefire order → `ci.yml` test job → delete `docker-image.yml`.
2. **Images**: app `Dockerfile` tweaks, `deploy/web/*`, `deploy/compose.yml`, `application-prod.yml`, `ci.yml` publish job.
3. **US2, VPS**: `bootstrap.sh`, `backup.sh` (called by `deploy.sh` before every switch), `deploy.sh`, `deploy.yml`,
   `.env.example`, first real install; backup bucket ready before the second deployment.
4. **US4, backups**: `restore.sh`, timer, `backup-check.yml`; restore drill (FR-018).
5. **US3, rollback**: same path as deploy; validate with quickstart §4.
6. **US5, clean-up**: delete local files, rewrite `INSTALL.md`, update `CLAUDE.md`, gitleaks.

## Operator actions outside the repository

Not code, but required before the quickstart can pass; `INSTALL.md` lists them:

- Rent the VPS; DNS record `vegelink.apolog.fr` → VPS.
- Create the bucket (another provider), lifecycle 30 days, write-no-delete key and read-only key.
- GitHub: `vege_prod` environment + secrets, backup secrets, packages public, branch protection on `dev`/`main`.
- Reconfigure each reader's target URL to `https://vegelink.apolog.fr/api/tags/scan`.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

None.
