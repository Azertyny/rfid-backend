# Research: Chaîne de build, CI/CD et déploiement sur VPS

Decisions taken for spec `009-deploiement-vps`. Each entry: Decision / Rationale / Alternatives considered.

## R1. Why CI fails, and the fix (FR-002)

**Finding** (reproduced locally on 2026-09-25):

```zsh
mvn surefire:test -Dtest='AccessMatrixSecurityTest,ReaderTokenVisibilityTest,AuthFlowSecurityTest' \
    -Dsurefire.runOrder=alphabetical
# → AuthFlowSecurityTest.login_returnsFreshCsrfCookieUsableForTheNextWrite: "Expecting actual not to be null" (line 120)
```

`spring-security-test`'s `csrf()` post-processor (`SecurityMockMvcRequestPostProcessors.CsrfRequestPostProcessor`,
lines 518-523) replaces the `CsrfTokenRepository` **of the shared `CsrfFilter` instance** with a
`TestCsrfTokenRepository(new HttpSessionCsrfTokenRepository())` the first time it is used, and never restores it. All
`@SpringBootTest` classes share one cached Spring context, so once any test has used `.with(csrf())`, the app no
longer writes the `XSRF-TOKEN` cookie for the rest of the JVM. `login_returnsFreshCsrfCookieUsableForTheNextWrite`
is the only test that reads that cookie.

Surefire's default `runOrder=filesystem` runs classes in directory order: on macOS (APFS) `AuthFlowSecurityTest`
comes before `AccessMatrixSecurityTest`, on the Linux runner (ext4) after. Hence green locally, red on CI. It is a
test isolation bug, not a production bug: the real app never runs the test post-processor.

**Decision**:
1. `AuthFlowSecurityTest` gets `@DirtiesContext(classMode = BEFORE_CLASS)` so it starts on a context whose
   `CsrfFilter` still holds the real `CookieCsrfTokenRepository`, and stops using `.with(csrf())` itself (its one use,
   in `logout_invalidatesSession`, sends the real cookie + `X-XSRF-TOKEN` header instead). Its outcome then no
   longer depends on class or method order.
2. `maven-surefire-plugin` gets `<runOrder>alphabetical</runOrder>` so the class order is the same on every OS
   (US1 scenario 4). With (1) the suite passes in any order; (2) makes a future order bug reproduce locally too.

**Alternatives considered**:
- `@DirtiesContext` on every class using `csrf()`: ~10 extra context startups, slower suite, same result.
- Resetting the repository with `WebTestUtils.setCsrfTokenRepository` in a `@BeforeEach`: relies on test-utility
  internals; harder to read.
- `@Disabled` / skipping the test: forbidden by FR-002.
- `runOrder=random` in CI: surfaces such bugs but makes CI non-deterministic (conflicts with SC-001).

## R2. Pipeline layout (FR-001 to FR-006, FR-011a/b, FR-016)

**Decision**: three GitHub Actions workflows, replacing `docker-image.yml`:

| Workflow | Trigger | Does |
|---|---|---|
| `ci.yml` | `pull_request` to `dev`/`main`; `push` to `dev`/`main` | job `test`: `mvn -B verify`, JUnit report annotated on the run/PR; job `publish` (push only, `needs: test`): builds and pushes both images |
| `deploy.yml` | `workflow_dispatch` with input `version` | checks the version, SSHes to the VPS, runs the deploy there, prints the result in the job summary |
| `backup-check.yml` | `schedule` daily + `workflow_dispatch` | fails if the newest backup in the external bucket is older than 26 h (FR-017b) |

- Test report: `mikepenz/action-junit-report` reads `target/surefire-reports/*.xml` and puts failing test names in
  the run summary and as PR check annotations (FR-003, US1 scenario 3).
- Maven dependencies cached with `actions/setup-java` `cache: maven` (SC-002).
- `deploy.yml` uses a GitHub **environment** `vege_prod` (holds the VPS secrets) and
  `concurrency: { group: production-deploy, cancel-in-progress: false }` (FR-016). The run itself is the deployment
  record (FR-011b): actor, date, input version, and health-check result in the job summary.
- Branch protection on `dev` and `main` requiring the `ci / test` check makes FR-004 enforceable; it is a repository
  setting, documented in the runbook, not code.

**Alternatives considered**: one workflow with a manual-approval gate (the spec chose a separate manual trigger,
clarification Q1/Q5); GitHub "deployments" API only (the environment already records deployments).

## R3. What a "version" is (FR-005, FR-006, FR-007)

**Decision**: a version is a pair of container images published together to GHCR with the **same tag**:

- `ghcr.io/azertyny/rfid-backend:<tag>`: the Spring Boot app (existing image name kept).
- `ghcr.io/azertyny/rfid-web:<tag>`: reverse proxy + static front + the production compose file (R4, R5).

Tag format: `<branch>-<short sha>` (e.g. `main-3f2a9c1`, `dev-b008d64`), plus moving tags `main` and `dev`. The
`latest` tag is dropped (it was pushed by both branches). Images carry the OCI labels
`org.opencontainers.image.revision` (full SHA) and `org.opencontainers.image.source`. Only `ci.yml`'s `publish` job
pushes, and only after `test` passed on that same commit (FR-005).

`deploy.yml` accepts only `^main-[0-9a-f]{7}$` and checks both images exist (`docker manifest inspect`) before
touching the VPS (edge case "version inexistante").

GHCR packages are set to **public**: the repository is public, the images hold no secret (all config comes from the
VPS `.env`), and the VPS then pulls without a registry credential.

**Alternatives considered**: semver release tags (manual step on every release, no value for a single deployment);
private packages with a read-only PAT on the VPS (one more secret to rotate, no gain for a public repo).

## R4. Reverse proxy, HTTPS and front serving (FR-007, FR-008, FR-009, FR-009b, FR-010)

**Decision**: **Caddy 2** in the `rfid-web` image, replacing nginx + certbot.

- Automatic HTTPS for `vegelink.apolog.fr`: gets and renews the Let's Encrypt certificate itself (HTTP-01 on port 80)
  and redirects HTTP → HTTPS (FR-009, US2 scenarios 3 and 5). Certificates persist in a named volume.
- Serves `front/` (copied into the image at build, FR-007) and proxies `/api/*` and `/actuator/health` to `app:8080`,
  same origin (FR-008). Everything else under `/actuator` returns 404.
- Sends `X-Forwarded-Proto`/`-For` (Caddy default); `application-prod.yml` already has
  `server.forward-headers-strategy: framework`, so the app sees HTTPS requests as secure.
- The reader scan route `POST /api/tags/scan` goes through the same HTTPS-only site. An explicit `http://` site
  answers `/api/*` with 403 and redirects only the other paths: Caddy's default redirect is a 308, which keeps the
  method and body, so a reader following redirects would replay an HTTP scan over HTTPS and it would be recorded
  (FR-009a). The token of such a request has still travelled unencrypted; readers must be configured with `https://`.
  The ACME HTTP-01 challenge on port 80 is served by Caddy before these handlers.
- The site address comes from `SITE_ADDRESS` (env), so the image is not tied to the domain. An `ACME_EMAIL` variable
  receives Let's Encrypt expiry notices.

**Alternatives considered**: nginx + certbot sidecar (the `feature/mep_vps` approach): two containers, a bootstrap
dance to get the first certificate before nginx can start with TLS, renewal needs an nginx reload; Traefik:
label-driven config is heavier to read for one site; serving the front from Spring Boot: the security chain denies
unlisted routes (`anyRequest().denyAll()`), would mix static caching into the app.

## R5. How the VPS gets the compose file of a version (FR-007, FR-014)

**Decision**: the production compose file (`deploy/compose.yml`) is baked into the `rfid-web` image at
`/opt/vegelink/compose.yml`. `deploy.sh` extracts it from the image of the requested version (`docker create` +
`docker cp`) before `docker compose up`. The compose file, the app and the front are then always of the same
version, and a rollback also rolls back the compose file. No repository checkout on the VPS.

**Alternatives considered**: download from `raw.githubusercontent.com` at the commit (needs the full SHA and GitHub
up during rollback); pipe it over SSH from the workflow (couples deploy to the runner's checkout); keep a hand-copied
file on the VPS (drifts from the version, the problem today).

## R6. Deploy access to the VPS (FR-011a, FR-011c, FR-012, FR-016)

**Decision**:
- A dedicated `deploy` user, **not** in the `docker` group (docker group = root-equivalent). Its only SSH key is the
  workflow's, restricted in `authorized_keys`:
  `command="sudo /opt/vegelink/bin/deploy.sh \"$SSH_ORIGINAL_COMMAND\"",restrict ssh-ed25519 AAAA… github-actions-deploy`.
  sshd expands `SSH_ORIGINAL_COMMAND` (the requested version) before `sudo` runs; passing it through the environment
  would not work, since `sudo` resets it. The script accepts only `^main-[0-9a-f]{7}$`, which blocks injected text.
- `/etc/sudoers.d/vegelink-deploy` allows `deploy` to run only `/opt/vegelink/bin/deploy.sh`, as root, no password.
- `deploy.sh` (root-owned, 0755): validates the version regex, takes an exclusive `flock` (second safety for FR-016),
  pulls both images, runs `backup.sh` if the database is running (exit 7 on failure, nothing changed) so a
  deployment that alters the schema can always be undone by restoring that backup, extracts the compose file (R5), writes `APP_VERSION` into `/opt/vegelink/.env`, runs
  `docker compose up -d`, then waits up to 120 s for health: app health through the internal network, then
  `https://vegelink.apolog.fr/actuator/health` end to end. Exit 0 = healthy; non-zero = failure, with the last 100
  lines of app logs on stdout (visible in the workflow, FR-012). It does **not** roll back automatically: the operator
  decides and redeploys the previous version (FR-014). It records `previous` and `current` versions in
  `/opt/vegelink/state/`, and prunes images unused for 30 days.
- The runner pins the VPS host key (`VPS_KNOWN_HOSTS` secret), never `StrictHostKeyChecking=no`.
- Revoking CI access = removing one line from `/home/deploy/.ssh/authorized_keys` (FR-011c).
- Accepted risk: `deploy.sh` runs as root the compose file shipped in the `rfid-web` image, so whoever can publish a
  `main-*` image controls the host. Only `ci.yml`'s `publish` job on pushes to `main` produces such tags; branch
  protection on `main` limits who can trigger it.

**Alternatives considered**: `deploy` in the `docker` group (full root via Docker); a pull agent on the VPS polling
GHCR (auto-deploy, contradicts the manual trigger); Watchtower (same).

## R7. VPS base and hardening (FR-010, FR-010a-c, FR-013)

**Decision**: Debian 12 or Ubuntu 24.04 LTS, prepared once by an idempotent `deploy/vps/bootstrap.sh` run as root:

- Docker Engine + Compose plugin from Docker's apt repository; `restart: unless-stopped` on every service and the
  Docker daemon enabled at boot (FR-013).
- `ufw`: default deny incoming; allow 22/tcp, 80/tcp, 443/tcp (FR-010a). Only the `web` service publishes ports; `app`
  and `db` use `expose` only. This matters because Docker-published ports bypass ufw: nothing else may publish.
- `/etc/ssh/sshd_config.d/10-vegelink.conf`: `PasswordAuthentication no`, `KbdInteractiveAuthentication no`,
  `PermitRootLogin no` (FR-010b). The script refuses to apply it until an admin user with an authorized key exists,
  so it cannot lock the operator out.
- `unattended-upgrades` with security origins enabled (FR-010c).
- Creates `deploy` (R6), `/opt/vegelink/{bin,state,backups}`, installs `deploy.sh`, `backup.sh`, `restore.sh` and the
  backup systemd timer (R8).

**Alternatives considered**: Ansible (more tooling for one host); fail2ban (out of scope by clarification Q5).

## R8. Backups (FR-017, FR-017a, FR-017b, FR-018, SC-008, SC-010)

**Decision**:
- `backup.sh`, run daily at 03:00 station time by a systemd timer (`vegelink-backup.timer`, `Persistent=true` so a
  missed run happens at next boot): `docker compose exec -T db pg_dump -Fc` → `vegelink-<UTC timestamp>-<version>.dump`
  → uploaded with **rclone** to `BACKUP_REMOTE` (`.env`, e.g. `backup:vegelink-backups`: an rclone S3 remote cannot
  carry the bucket name), an S3-compatible bucket at a provider other than the VPS host. The local file is
  deleted after upload.
- Retention: a **lifecycle rule on the bucket** expires objects after 30 days (≥ 14 required). The VPS key then only
  needs put + list + get, no delete (FR-017a): a compromised VPS cannot erase the history.
- Failure visibility (FR-017b): `backup-check.yml` runs daily at 06:00 UTC with a separate **read-only** key, lists the
  bucket and fails if the newest object is older than 26 h. A failed scheduled run emails the operator through
  GitHub's standard notification. This catches both a failing and a silently non-running backup.
  Caveats: GitHub emails the user who last changed the `cron` line, and disables scheduled workflows of a public
  repository after 60 days without commits. The workflow runs `gh workflow enable` on itself (keeps it explicitly
  enabled while it still runs); the runbook tells the operator to check monthly in the off-season that it is enabled.
- Pre-deployment backup: `deploy.sh` also calls `backup.sh` before switching containers (R6), so the newest backup
  before a schema-changing deployment is at most minutes old.
- `restore.sh <object name>`: downloads the dump, stops `app`, `pg_restore --clean --if-exists` into `db`, starts
  `app`, waits for health. Works on a fresh VPS after `bootstrap.sh` + first deploy (SC-010).
- Provider left to the operator (e.g. Scaleway Object Storage, Backblaze B2, OVHcloud Object Storage); rclone makes the
  scripts provider-neutral. Constraint: not the VPS host.

**Alternatives considered**: host snapshots (same provider as the VPS, rejected in clarification Q3); healthchecks.io
ping (one more external service and secret); retention by script (needs delete rights on the VPS key).

## R9. Production configuration (FR-009b, FR-015)

**Decision**:
- One file on the VPS, `/opt/vegelink/.env` (root, 0600), from the committed `deploy/.env.example` (fake values, every
  variable listed). Variables in [contracts/configuration.md](contracts/configuration.md).
- `application-prod.yml`: default `app.cors.allowed-origins` becomes `https://vegelink.apolog.fr` (FR-009b);
  `server.servlet.session.cookie.secure: true`. The CSRF cookie is already `Secure` on HTTPS requests
  (`CookieCsrfTokenRepository` uses `request.isSecure()`, true behind Caddy thanks to forwarded headers); the
  quickstart checks both cookies.
- GitHub secrets live in the `vege_prod` environment: `VPS_HOST`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS`; and repository
  secrets for `backup-check.yml`: `BACKUP_S3_ENDPOINT`, `BACKUP_S3_BUCKET`, `BACKUP_S3_READ_KEY_ID`,
  `BACKUP_S3_READ_SECRET`.
- Secret scan: `gitleaks` step in `ci.yml` over the full history (SC-009). A scan on 2026-09-25 found only `change_me`
  placeholders.

## R10. Container images (FR-007, SC-002, SC-006)

**Decision**:
- `Dockerfile` (app): keep the multi-stage build, add a BuildKit cache mount for `~/.m2`, run as a non-root user,
  `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`. The `publish` job uses `docker/build-push-action` with GHA cache.
- `deploy/web/Dockerfile`: `FROM caddy:2-alpine`, copies `front/`, `deploy/web/Caddyfile` and `deploy/compose.yml`.
  Build context is the repository root.
- Downtime during deploy is the app restart only (~20 s; Caddy keeps serving the front and answers 502 on `/api`
  meanwhile), under the 1-minute budget of SC-006. Zero-downtime (blue/green) is not needed at this scale.

## R11. Removing the local-server target (FR-020, FR-021)

**Decision**: delete `deploy/docker-compose.yml`, `deploy/front/default.conf`, `deploy/.env.local.example`,
`.github/workflows/docker-image.yml`; rewrite `deploy/INSTALL.md` as the VPS runbook (FR-019); update `CLAUDE.md`
(frontend serving, deployment). `application-dev.yml`, `front/config.js` (`API_URL: "/api"`, same origin) and
`mvn spring-boot:run` stay as they are (FR-021). The unmerged `feature/mep_vps` branch is superseded; deleting it is
left to the operator.
