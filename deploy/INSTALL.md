# RFID Back — production on the VPS

Production runs on one VPS at `https://vegelink.apolog.fr`. This is the only supported deployment. Design and
decisions: `.specify/specs/009-deploiement-vps/`.

## Architecture

- `web` (Caddy, image `ghcr.io/azertyny/rfid-web`): the only service reachable from the Internet. Serves the front,
  proxies `/api/*` and `/actuator/health` to the app, gets and renews the HTTPS certificate by itself. Plain HTTP:
  pages are redirected to HTTPS; `/api/*` is refused (403) so a reader misconfigured with `http://` never records a
  scan over an unencrypted connection.
- `app` (Spring Boot, image `ghcr.io/azertyny/rfid-backend`, profile `prod`): reachable only by `web`.
- `db` (PostgreSQL 16): reachable only by `app`; data in the Docker volume `vegelink_db_data`.

A **version** is the pair of images with the same tag `main-<7-char sha>` (or `dev-<sha>`, never deployed). The
`ci` workflow publishes one on every push to `dev`/`main` whose tests pass, and prints the tag in its run summary.
Production changes only when someone runs the **deploy** workflow.

On the VPS everything lives in `/opt/vegelink`:

| Path | What |
|---|---|
| `.env` | configuration and secrets (root, `chmod 600`), from [`.env.example`](.env.example) |
| `compose.yml` | compose file of the running version, extracted from its `rfid-web` image by `deploy.sh` |
| `bin/deploy.sh`, `bin/backup.sh`, `bin/restore.sh` | installed by `bootstrap.sh` from [`vps/`](vps/) |
| `state/current`, `state/previous` | running version and the one before |

## Prerequisites

- A VPS with Debian 12 or Ubuntu 24.04, and an admin account (member of `sudo`) that logs in with an SSH key.
- A DNS `A` (and `AAAA` if IPv6) record `vegelink.apolog.fr` → the VPS. Check with `dig +short vegelink.apolog.fr`
  before the first deployment: Caddy cannot get a certificate otherwise.
- An account with an S3-compatible object storage **at another provider than the VPS host** (e.g. Scaleway Object
  Storage, Backblaze B2, OVHcloud Object Storage), for the backups.

## GitHub setup (once)

1. **Deploy key**: on your machine, `ssh-keygen -t ed25519 -N "" -C github-actions-deploy -f vegelink-deploy`.
   The public key (`vegelink-deploy.pub`) goes to `bootstrap.sh` below; the private key goes to GitHub.
2. **Environment** *Settings → Environments → New environment* `vege_prod`, with secrets:
   - `VPS_HOST`: `vegelink.apolog.fr` (or the VPS IP)
   - `VPS_SSH_KEY`: content of `vegelink-deploy` (private key), then delete the local copy
   - `VPS_KNOWN_HOSTS`: output of `ssh-keyscan -t ed25519 vegelink.apolog.fr`, checked against the fingerprint the
     VPS shows (`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` on the VPS)
3. **Packages**: after the first `ci` run on `dev` or `main`, set the packages `rfid-backend` and `rfid-web` to
   *Public* (profile → Packages → package settings). The VPS pulls them without credentials; they contain no secret.
4. **Branch protection** on `dev` and `main`: require the status check `test` (from the `ci` workflow) before merging.
5. **Backup check secrets** (repository secrets, see [Backups](#backups)): `BACKUP_S3_ENDPOINT`, `BACKUP_S3_BUCKET`,
   `BACKUP_S3_READ_KEY_ID`, `BACKUP_S3_READ_SECRET`.

## First install

1. Copy the files to the VPS and run the bootstrap, as your admin user:

   ```zsh
   scp -r deploy/vps deploy/.env.example <admin>@vegelink.apolog.fr:
   ssh <admin>@vegelink.apolog.fr
   sudo ./vps/bootstrap.sh --admin-user <admin> --deploy-key "$(cat vegelink-deploy.pub)"   # paste the key if needed
   ```

   It installs Docker, opens only ports 22/80/443, turns off SSH passwords and root login, enables automatic security
   updates, creates the `deploy` user (it can only run `deploy.sh`), installs the scripts and the daily backup timer.
   It refuses to run until your admin account has an SSH key, so it cannot lock you out. Re-running it is safe.
   **Keep your current SSH session open** and check a new login works before closing it.

2. **Backup storage** (needed before the second deployment: `deploy.sh` backs up before every switch and stops
   with exit 7 if it cannot):
   - create a bucket, e.g. `vegelink-backups`, with a lifecycle rule "delete objects 30 days after creation";
   - create a key for the VPS that can **list, read and write but not delete** objects in that bucket;
   - on the VPS, `sudo rclone config`: new remote named `backup`, type `s3`, provider as offered (or `Other`), the
     key, the endpoint; then `sudo rclone lsf backup:vegelink-backups` must answer without error.

3. Configuration:

   ```zsh
   sudo cp .env.example /opt/vegelink/.env && sudo chmod 600 /opt/vegelink/.env
   sudo nano /opt/vegelink/.env
   ```

   Set real values for `ACME_EMAIL`, `POSTGRES_PASSWORD` (long random string; it is only read when the database is
   created, see [Rotate a secret](#rotate-a-secret)), `APP_BOOTSTRAP_ADMIN_PASSWORD` and `BACKUP_REMOTE`
   (`backup:<bucket>`). Leave `APP_VERSION`: `deploy.sh` writes it.

4. Run the first deployment: see [Deploy a version](#deploy-a-version).

5. First login: open `https://vegelink.apolog.fr/`, log in with the bootstrap Administrateur, open **Utilisateurs**,
   create the Opérateur accounts and change the bootstrap password (or create a personal Administrateur and disable
   the bootstrap one). The bootstrap variables are only used while no enabled Administrateur exists.

## Deploy a version

1. Merge to `main` (from `dev`). Wait for the `ci` run on `main`; its summary shows *Published version:
   `main-xxxxxxx`*.
2. *Actions → deploy → Run workflow*, version `main-xxxxxxx`.

The workflow checks the tag exists, connects as `deploy`, and `deploy.sh`:
checks `.env` → pulls both images → backs up the database → switches the containers → waits up to 120 s for the
app to answer, internally and on `https://vegelink.apolog.fr/actuator/health`. The run is green only if that health
check passed. Its summary records who deployed which version, when, the previous version and the result. Two runs
never overlap: a second one waits. The API is unavailable for about 20 s during the switch; the front stays up.

If GitHub is unavailable, the same script can be run from the admin account: `sudo /opt/vegelink/bin/deploy.sh
main-xxxxxxx`. It is the same procedure, not a different one.

`deploy.sh` exit codes: 2 invalid version, 3 another deployment running, 4 image not found, 5 `.env` incomplete or
bootstrap password still `change_me`, 6 not healthy after 120 s (the last 100 app log lines are printed), 7 pre-deploy
backup failed (nothing changed).

## Readers

Set each reader's target to `https://vegelink.apolog.fr/api/tags/scan`; its `x-api-token` does not change. A reader
configured with `http://` gets `403 HTTPS required` and its scans are not recorded.

## Rollback

1. Find the version to go back to: the *Previous version* line in the last deploy run's summary (a failed run also
   shows "Rollback: redeploy …"), or `cat /opt/vegelink/state/previous` on the VPS.
2. Run the **deploy** workflow with that version.

The data is kept. One risk: the database schema follows the entities automatically (`ddl-auto: update`). If the
version you roll back from changed an entity, the older version may not work with the altered schema. Then restore
the backup `deploy.sh` took just before that deployment: the newest `vegelink-*-<older version>.dump` in the bucket
(see [Restore](#restore)). Scans recorded since that backup are lost.

## Backups

- Daily at 03:00 (Paris) by the systemd timer `vegelink-backup.timer`, plus one before every deployment.
- Each backup is `vegelink-<UTC time>-<version>.dump` (`pg_dump -Fc`) in `BACKUP_REMOTE`; the bucket's lifecycle rule
  deletes it after 30 days. The VPS key cannot delete, so a compromised VPS cannot erase the history.
- The bucket and the VPS remote are set up in [First install](#first-install), step 2.

Commands on the VPS:

```zsh
sudo systemctl start vegelink-backup.service        # back up now
systemctl list-timers vegelink-backup.timer          # next run
journalctl -u vegelink-backup.service                # logs
sudo rclone lsf backup:vegelink-backups              # list backups
```

**Alerting**: the `backup-check` workflow runs every day at 06:00 UTC and fails if the newest backup is older than
26 h. Create a **read-only** key for the bucket and set the repository secrets `BACKUP_S3_ENDPOINT` (e.g.
`https://s3.fr-par.scw.cloud`), `BACKUP_S3_BUCKET`, `BACKUP_S3_READ_KEY_ID`, `BACKUP_S3_READ_SECRET`. Two GitHub rules
to know:
- the failure email goes to the GitHub user who last changed the `cron` line of `.github/workflows/backup-check.yml`;
- GitHub disables scheduled workflows of a public repository after 60 days without commits. In the off-season,
  check once a month on the Actions tab that *backup-check* is still enabled, and re-enable it there if not.

## Restore

On the running VPS:

```zsh
sudo /opt/vegelink/bin/restore.sh latest                      # or a backup name from rclone lsf
```

It asks you to type the backup name, stops the app, replaces all data with the backup, restarts the app and waits
until it is healthy.

On a new VPS (the old one is lost): [First install](#first-install) steps 1–3 with the same `.env` values, deploy
the version named in the backup file, then run `restore.sh` as above. Point the DNS record to the new VPS first, and
update `VPS_HOST` and `VPS_KNOWN_HOSTS` in the `vege_prod` environment.

## Rotate a secret

| Secret | How |
|---|---|
| Database password | `sudo docker compose -p vegelink -f /opt/vegelink/compose.yml --env-file /opt/vegelink/.env exec db psql -U rfid_user -d rfidback -c "ALTER USER rfid_user PASSWORD '<new>'"`, then set `POSTGRES_PASSWORD=<new>` in `/opt/vegelink/.env`, then redeploy the current version (`cat /opt/vegelink/state/current`). |
| Bootstrap Administrateur | Only used while no enabled Administrateur exists: change passwords in **Utilisateurs** instead. |
| CI deploy key | New key pair; `sudo ./vps/bootstrap.sh --admin-user <admin> --deploy-key "<new public key>"` (rewrites `/home/deploy/.ssh/authorized_keys`); update `VPS_SSH_KEY`. Removing that file's line revokes GitHub's access. |
| Backup keys | Create new keys at the storage provider; VPS: `sudo rclone config` (edit remote `backup`); GitHub: the `BACKUP_S3_READ_*` secrets; then delete the old keys. |
| Admin SSH key | Add the new key to `~/.ssh/authorized_keys`, log in with it, remove the old one. |

## Troubleshooting

- **No certificate / HTTPS fails**: the DNS record does not point to the VPS yet, or ports 80/443 are blocked.
  `sudo docker compose -p vegelink -f /opt/vegelink/compose.yml --env-file /opt/vegelink/.env logs web`.
- **Deploy fails with exit 5**: `.env` misses a variable or still has `APP_BOOTSTRAP_ADMIN_PASSWORD=change_me`.
- **Deploy fails with exit 6**: read the app logs in the run summary; redeploy the previous version.
- **Deploy fails with exit 7**: `sudo systemctl start vegelink-backup.service` then `journalctl -u vegelink-backup.service`
  (usually the `backup` remote or `BACKUP_REMOTE`).
- **Services after a VPS reboot**: they restart on their own (`restart: unless-stopped`); check with
  `sudo docker compose -p vegelink -f /opt/vegelink/compose.yml --env-file /opt/vegelink/.env ps`.
- **Disk**: container logs are capped (3 × 10 MB per service); images unused for 30 days are pruned at each deploy.
