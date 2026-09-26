# Quickstart: Contrôle des tags par rapport à la liste de référence

How to check the feature end to end. Contract: [contracts/openapi-tag-reference.md](contracts/openapi-tag-reference.md);
rules: [data-model.md](data-model.md).

## Automated

```zsh
mvn clean test -Dtest=ReferenceTagListTest     # shipped file loads 5,008 UIDs; malformed files refuse to start (SC-002)
mvn clean test -Dtest='TagServiceTest,RegistrationServiceTest'   # 409 rules, separate confirmations (FR-004, SC-006)
mvn clean test -Dtest='TagRegistrationApiTest,RegistrationApiTest,RecordApiTest,TagOffListApiTest'
mvn clean install                               # full build, CI order
```

## Manual, dev profile

Prerequisites: an Administrateur (`APP_BOOTSTRAP_ADMIN_*`), one reader in `ENREGISTREMENT` mode and one in
`PRODUCTION` mode, each with its token (`readers.html`).

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

The log shows the list loaded (5,008 UIDs). In-list UID used below: `E2806915200050287477D48C`; off-list:
`E2000017221101891400A23G`.

1. **Registration page (User Story 1)** — log in, open `tags.html`, pick the registration reader, click "Démarrer",
   then simulate both reads:

   ```zsh
   for uid in E2806915200050287477D48C E2000017221101891400A23G; do
     curl -s -X POST localhost:8080/api/tags/scan -H "x-api-token: $REG_TOKEN" \
          -H 'Content-Type: application/json' -d "{\"uid\":\"$uid\",\"isCompliant\":true}"; echo
   done
   ```

   Expected: both lines appear; only the second has the "hors liste" badge. Enter a bucket number and save: a dialog
   lists the off-list tag and nothing is saved. Cancel: the session is still open. Save again and confirm: `200`,
   both tags on the bucket.

2. **Separate confirmations** — repeat with a tag already on another bucket plus an off-list tag: the dialog shows
   both lists; the saved request carries both flags. Through the API, sending only `moveConfirmed: true` still gets
   `409` with `offListTags` filled.

3. **Production scan (User Story 2)** — scan the off-list UID with the production reader token: `200`, same response
   shape as for an in-list tag. Open `reader.html` for that reader: the box shows "hors liste", the compliance stays
   the one the reader sent. Then scan `E2806915000040287477C993` (line 1469 in the form the production readers send it,
   `0000` instead of `2000`): its box must **not** show "hors liste" (FR-002, SC-003).

4. **Off-list tags (User Story 3)** — in `tags.html`, open "Tags hors liste": the off-list tag appears with its bucket,
   read count and last read; no in-list tag appears. With an Opérateur session, `GET /api/tags/off-list` answers `403`.

5. **Broken list** — start with `APP_TAGS_REFERENCELIST=file:/tmp/empty.csv` (an empty file): startup fails with a
   message naming the file.

## Latency (SC-004)

Rerun the scan measurement of spec `004` ([quickstart](../004-scan-tag-conformite/quickstart.md)); p95 must stay under
200 ms. The check is one set lookup, so no change is expected.

**Measured (2026-09-26)**, H2 file database, jar started with `--spring.jpa.show-sql=false`, 3 production readers in
parallel, 300 scans each, half of the UIDs off-list: p95 = 7.1 ms with distinct UIDs, 3.9 ms with one repeated UID per
reader (duplicate path). Spec `004` measured 4.8 ms and 3.3 ms without the feature. To redo on PostgreSQL before going to
production, as spec `004` already notes.
