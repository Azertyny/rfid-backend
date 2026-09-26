# Quickstart: Contrôle des tags par rapport à la liste de référence (révision)

How to check the revision end to end: tags not in the reference list are dropped without being shown, and those
already stored are deleted once. Contract: [contracts/openapi-tag-reference.md](contracts/openapi-tag-reference.md);
rules: [data-model.md](data-model.md).

## Automated

```zsh
mvn clean test -Dtest=ReferenceTagListTest     # shipped file loads 5,008 UIDs; malformed files refuse to start (SC-002)
mvn clean test -Dtest='TagServiceTest,RegistrationServiceTest'   # scan ignored, reads filtered, 400 on registration
mvn clean test -Dtest=OffListTagPurgeTest      # purge deletes off-list data only, and only once (FR-007, SC-005)
mvn clean test -Dtest='TagScanApiTest,TagRegistrationApiTest,RegistrationApiTest,RegistrationReadsApiTest,RecordApiTest'
mvn clean install                               # full build, CI order
```

## Manual, dev profile

Prerequisites: an Administrateur (`APP_BOOTSTRAP_ADMIN_*`), one reader in `ENREGISTREMENT` mode and one in
`PRODUCTION` mode, each with its token (`readers.html`). In-list UID used below: `E2806915200050287477D48C`
(reader form of line 1469: `E2806915000040287477C993`); off-list: `E2000017221101891400A23G`.

### 1. The purge (User Story 3) — on a copy of a database that has off-list tags

Start the **previous** version (`main` before this change) on a copy of the dev database (`./data/rfidbackdb.mv.db`),
register the off-list UID on a bucket with `offListConfirmed: true`, scan it once with the production reader, and
change that record's conformity at the line kiosk. Also scan an in-list UID. Stop, then start this version:

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

Expected: an `INFO` line from `OffListTagPurge` with 1 tag, 1 record, 1 conformity change (and any off-list
registration reads) deleted. The bucket still exists, without that tag; the in-list tag and its record are untouched;
the dashboard's counts for that day lost exactly the deleted record. Restart: the log says the purge was already
applied and deletes nothing.

### 2. Production scan (User Story 2)

```zsh
curl -s -X POST localhost:8080/api/tags/scan -H "x-api-token: $PROD_TOKEN" \
     -H 'Content-Type: application/json' -d '{"uid":"E2000017221101891400A23G","isCompliant":false}'; echo
```

Expected: `200`, `isCompliant: true`, message "Tag not in reference list, ignored". `reader.html` for that reader shows
no new box; no tag `E2000017221101891400A23G` exists. The same call with `E2806915000040287477C993` creates its record
as before (FR-002, SC-003).

### 3. Registration page (User Story 1)

Log in, open `tags.html`, pick the registration reader, click "Démarrer", then send both UIDs, tag by tag and as a
batch:

```zsh
for uid in E2806915200050287477D48C E2000017221101891400A23G; do
  curl -s -X POST localhost:8080/api/tags/scan -H "x-api-token: $REG_TOKEN" \
       -H 'Content-Type: application/json' -d "{\"uid\":\"$uid\",\"isCompliant\":true}"; echo
done
curl -s -X POST localhost:8080/api/tags/registration-reads -H "x-api-token: $REG_TOKEN" \
     -H 'Content-Type: application/json' -d '{"uids":["E2806915200050287477D48C","E2000017221101891400A23G"]}'; echo
```

Expected: the off-list scan answers "Registration read ignored: tag not in reference list"; the batch answers
`receivedCount: 2`, `addedCount: 0` (the in-list one is already there). The page lists only the in-list tag, with no
badge. Enter a bucket number and save: `200` with no confirmation dialog, only the in-list tag on the bucket.

### 4. Direct association (User Story 1, scenario 4)

With the Administrateur session (cookie and `X-XSRF-TOKEN` from the browser), `POST /api/tags/buckets/{n}` with
`{"uids":["E2806915200050287477D48C","E2000017221101891400A23G"],"moveConfirmed":true}`: `400`, `detail` names
`E2000017221101891400A23G`, nothing saved. Adding `"offListConfirmed": true` changes nothing.

### 5. Nothing left to see

`tags.html` has no "Tags hors liste" section; `GET /api/tags/off-list` no longer returns a list (the route is gone from `api.yaml`); `reader.html`
never shows "HORS LISTE".

## Latency (SC-004)

The off-list path does less than before (no database access), the in-list path adds one set lookup. Rerun the scan
measurement of spec `004` ([quickstart](../004-scan-tag-conformite/quickstart.md)) with in-list UIDs; p95 must stay
under 200 ms (measured 7.1 ms with the first delivery of this feature).

**Measured after the revision (2026-09-26)**, H2 file database (copy of the dev one), jar started with
`--spring.jpa.show-sql=false`, 3 production readers in parallel, 300 scans each (900 per run):

| Run | p95 |
|---|---|
| in-list UIDs in the readers' form (`E28069150000…`), all distinct (new Record each time) | 6.1 ms |
| off-list UIDs, all distinct (ignored, no database access) | 2.5 ms |
| one in-list UID per reader (duplicate path) | 3.9 ms |

All well under 200 ms (SC-004); the off-list path is the cheapest. PostgreSQL remains to be measured before production,
as spec `004` notes.
