# Contract: CI/CD workflows

The interface the operator (and GitHub) sees. Implementation lives in `.github/workflows/`.

## `ci.yml`: build, test, publish

| | |
|---|---|
| Triggers | `pull_request` → `dev`, `main`; `push` → `dev`, `main` |
| Job `test` (always) | `mvn -B verify` on JDK 21 (Temurin); shellcheck from the pinned image `koalaman/shellcheck:v0.11.0` on `deploy/vps/*.sh`; `docker compose config` on `deploy/compose.yml`; `gitleaks` over full history; JUnit report annotated on the run and the PR |
| Job `publish` (push only, `needs: test`) | builds and pushes `ghcr.io/azertyny/rfid-backend:<tag>` and `ghcr.io/azertyny/rfid-web:<tag>` + moving tag `<branch>` |
| Tag | `<branch>-<7-char sha>` (e.g. `main-3f2a9c1`) |
| Manifests | one per image (`provenance: false`): no untagged attestation versions in the packages |
| Required check | `ci / test` is required by branch protection on `dev` and `main` (FR-004) |
| Permissions | `contents: read`, `packages: write` (publish job only), `checks: write` (report) |
| Outputs on failure | run conclusion `failure`; failing test names in the job summary and as annotations; nothing published |
| Job summary on publish | the published tag, copy-paste ready for `deploy.yml` |

## `deploy.yml`: deploy a version to production

| | |
|---|---|
| Trigger | `workflow_dispatch` only (never on push, FR-011a) |
| Input | `version` (string, required): a published `main-<7-char sha>` tag |
| Environment | `vege_prod` (holds `VPS_HOST`, `VPS_SSH_KEY`, `VPS_KNOWN_HOSTS`) |
| Concurrency | group `production-deploy`, `cancel-in-progress: false`: a second run waits (FR-016) |
| Step 1: check | refuse unless input matches `^main-[0-9a-f]{7}$` and both images exist in GHCR |
| Step 2: deploy | `ssh deploy@$VPS_HOST <version>`: runs the VPS deploy script ([vps-scripts.md](vps-scripts.md)) |
| Result | success ⇔ script exit 0 (health check passed) |
| Job summary | version, actor, start/end time, health-check result, previous version, script output on failure |
| Rollback | the same workflow with an older `main-*` tag (FR-014) |

## `backup-check.yml`: backup freshness

| | |
|---|---|
| Triggers | `schedule` daily 06:00 UTC; `workflow_dispatch` |
| Secrets | `BACKUP_S3_ENDPOINT`, `BACKUP_S3_BUCKET`, `BACKUP_S3_READ_KEY_ID`, `BACKUP_S3_READ_SECRET` (read-only key) |
| Permissions | `contents: read`, `actions: write` (first step re-enables this workflow with `gh workflow enable`) |
| Check | newest object named `vegelink-*.dump` is < 26 h old |
| Result | success, or failure naming the newest backup and its age; GitHub emails the operator on failure (FR-017b) |
