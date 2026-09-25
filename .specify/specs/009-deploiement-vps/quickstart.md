# Quickstart: validate spec 009 end to end

Each scenario names the spec items it proves. Contracts: [pipelines](contracts/pipelines.md),
[VPS scripts](contracts/vps-scripts.md), [configuration](contracts/configuration.md).

## Prerequisites

- A fresh Debian 12 / Ubuntu 24.04 VPS with an admin user holding an SSH key; DNS `A`/`AAAA` record
  `vegelink.apolog.fr` → VPS.
- An S3-compatible bucket at a provider other than the VPS host, lifecycle rule "expire after 30 days", one write key
  without delete (VPS) and one read-only key (GitHub); root's `backup` rclone remote configured on the VPS (see
  `deploy/INSTALL.md`, First install).
- GitHub: environment `vege_prod` with `VPS_HOST`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS`; repository secrets
  `BACKUP_S3_*`; packages `rfid-backend` and `rfid-web` public; branch protection requiring `ci / test`.

## 1. CI is reliable (US1, FR-001–FR-004, SC-001, SC-002)

```zsh
mvn clean test                                                     # green locally
mvn surefire:test -Dtest='AccessMatrixSecurityTest,AuthFlowSecurityTest'   # the order that failed on CI: now green
```

- Open a PR to `dev` with no functional change → `ci / test` green in < 10 min, shown on the PR.
- Push a commit to that PR with a deliberately false assertion → red, test name in the summary and as an annotation,
  no `publish` job. Revert.
- Merge to `dev` → `publish` pushes `rfid-backend:dev-<sha>` and `rfid-web:dev-<sha>`; no `latest` tag moves.
- Re-run `ci.yml` on `dev` ten times (or ten merges) → 10/10 green.

## 2. First install on a fresh VPS (US2, FR-008–FR-010c, FR-013, FR-015, SC-004, SC-011)

Follow `deploy/INSTALL.md` only, timing it (target < 1 h):

```zsh
sudo ./bootstrap.sh --admin-user <you> --deploy-key "<CI public key>"
sudo cp .env.example /opt/vegelink/.env && sudo chmod 600 /opt/vegelink/.env   # then fill real values
```

Then run `deploy.yml` with the newest `main-<sha>` tag.

Expected:
- `https://vegelink.apolog.fr/` → login page with a valid certificate; `http://…` → redirect to HTTPS.
- Log in as the bootstrap Administrateur; the response sets `JSESSIONID` and `XSRF-TOKEN` with `Secure`.
- `curl -s https://vegelink.apolog.fr/actuator/health` → `{"status":"UP"}`; `/actuator/env` → 404.
- From another machine: `nmap -p- vegelink.apolog.fr` → only 22, 80, 443 open.
- `ssh -o PubkeyAuthentication=no <you>@vegelink.apolog.fr` → refused; `ssh root@…` → refused.
- `ssh deploy@vegelink.apolog.fr bash` → runs `deploy.sh` with argument `bash` → exit 2, no shell.
- `sudo reboot`, wait → site answers again without intervention.

## 3. Reader scans over HTTPS (US2 scenario 4, FR-009a)

- Create a reader in the admin UI, point a real reader (or `curl`) at
  `https://vegelink.apolog.fr/api/tags/scan` with its `x-api-token` → record visible in `reader.html`.
- Same call on `http://…` (with `curl -L` too) → 403 `HTTPS required`, no redirect, no record created. The token
  still travelled unencrypted, which is why every reader must be configured with `https://`.

## 4. Deploy, data kept, rollback (US2 scenario 2, US3, FR-011–FR-016, SC-003, SC-005–SC-007)

1. Create a picker, a bucket, a few scans.
2. Merge a trivial change to `main`; `publish` done in < 15 min. Run `deploy.yml` with the new tag → success in
   < 5 min; data from step 1 still there; downtime of `/api` < 1 min (watch with `while curl …; sleep 1`).
   Also check that a `vegelink-<ts>-<old version>.dump` object was uploaded just before the switch.
3. Start `deploy.yml` twice in a row → the second waits for the first.
4. Run `deploy.yml` with `main-0000000` → refused before any SSH.
5. Run `deploy.yml` with the previous tag → success in < 5 min; data intact; run summary shows actor, versions, health.
6. Repeat steps 2 and 5 once more (3 deployments, SC-007).
7. **Refused before switching (exit 5)**: set `APP_BOOTSTRAP_ADMIN_PASSWORD=change_me` in `/opt/vegelink/.env`, run
   `deploy.yml` with the current tag → `FAILED 5`, running containers untouched. Restore the real value.
8. **App that does not start (exit 6, US3 scenario 2, FR-012)**: set a wrong `POSTGRES_PASSWORD` in
   `/opt/vegelink/.env` (the database keeps its real password, only read on first init), run `deploy.yml` with the
   current tag → pre-deploy backup succeeds (no password used), the app cannot connect, after 120 s `FAILED 6`, the
   summary shows the app logs (authentication error) and the "Redeploy `<previous>`" hint. Put the real password
   back and redeploy the same tag → success.

## 5. Backups and restore (US4, FR-017–FR-018, SC-008, SC-010)

- `sudo systemctl start vegelink-backup.service` → an object `vegelink-<ts>-<version>.dump` in the bucket.
- `sudo -u deploy …` / the VPS key cannot delete it (`rclone delete` → access denied).
- Run `backup-check.yml` → green. Stop the timer for 27 h (or point the check at an empty prefix) → red, email received.
- On a second fresh VPS: bootstrap, deploy the same version, `sudo /opt/vegelink/bin/restore.sh latest` → data of
  step 4 present; total < 2 h, restore itself < 30 min.

## 6. Clean-up (US5, FR-019–FR-021, SC-009)

- `git ls-files deploy .github` → no `docker-compose.yml`, `front/default.conf`, `.env.local.example`,
  `docker-image.yml`.
- Every command and file named in `deploy/INSTALL.md` exists.
- `gitleaks detect` (CI step) → no leak.
- `APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run` → dev app on :8080
  as before.
