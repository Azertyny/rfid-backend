# Quickstart: validating feature 005

## Automated

```zsh
mvn clean test -Dtest='RecordServiceTest,RecordApiTest,TagServiceTest,AccessMatrixSecurityTest'
mvn clean install   # full build, regenerates OpenAPI sources
```

Expected: all green. The cases checked are the rows of the response table in
[contracts/openapi-records.md](contracts/openapi-records.md) §5, the FR-006 duplicate-scan case in `TagServiceTest`,
and the last-10 limit and order (spec SC-001) in `RecordApiTest`.

## Schema upgrade on an existing database

Start the app on the existing dev database (`./data/rfidbackdb.mv.db`) **without deleting it**. Expected: startup
succeeds, and the H2 console shows the new table and its index
(`SELECT * FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'RECORD_CONFORMITY_CHANGE'`), and `RECORD` now has
`IDX_RECORD_READER_DATE` next to `IDX_RECORD_READER_TAG_DATE`. Existing records are
not locked: they have no change row. Before deploying, do the same against a copy of the prod PostgreSQL database
(`\d record_conformity_change`).

## Manual (local app)

Prerequisites: set `app.scan.duplicate-window: 120s` in `application-dev.yml` so step 7 is not a race against the
10 s default, then start the app with a bootstrap admin (see `CLAUDE.md`). As the admin, create an Opérateur `op1` in
`users.html`, and a Production reader `Ligne 1` (token `T1`) in `readers.html`. Helpers:

```zsh
scan() { curl -s -X POST localhost:8080/api/tags/scan -H 'Content-Type: application/json' -H "x-api-token: $1" -d "{\"uid\":\"$2\",\"isCompliant\":${3:-true}}"; echo; }
```

For the logged-in calls, use the browser (devtools console on any app page, where `apiFetch` from `front/auth.js`
adds the CSRF header):

```js
const id = '<record id>';
await apiFetch(`/records/${id}/conformity`, { method: 'PATCH',
  headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ isCompliant: false }) });
// silent: true avoids apiFetch's alert() on 403
const r = await apiFetch(`/records/${id}/conformity-history`, { silent: true }); [r.status, await r.text()];
```

1. **Scan**: `scan T1 Q-005` → compliant record. Open `reader.html` for `Ligne 1` and copy its id from the devtools
   network tab (`GET /records/readers/Ligne 1`).
2. **No history yet**: as the admin, `GET …/conformity-history` → `200`, `{"changes": []}`.
3. **Opérateur toggles**: log in as `op1`, click the tag in `reader.html` → it turns non-compliant.
4. **History**: as the admin → one change, `previousIsCompliant: true`, `newIsCompliant: false`,
   `authorUsername: "op1"`.
5. **Idempotent**: as `op1`, send `PATCH` with `isCompliant: false` twice → `204` both times; history still has
   one change.
6. **Opérateur cannot read history**: as `op1`, `GET …/conformity-history` → `403`.
7. **Locked for duplicate scans (FR-006)**: as `op1`, set the record back to compliant (history: 2 changes). Then,
   within the duplicate window of the record's creation, `scan T1 Q-005 false`
   → `message: "Duplicate read ignored"`, `isCompliant` equal to the Opérateur's value; the record in
   `reader.html` is unchanged and the history gained no row.
8. **Not locked without a change**: `scan T1 Q-006`, then `scan T1 Q-006 false` straight away → the record turns
   non-compliant (spec `004` behavior kept); its history is still empty.
9. **Unknown record**: `GET /records/00000000-0000-0000-0000-000000000000/conformity-history` as the admin → `404`.

## Concurrency (manual, optional)

Two Opérateurs clicking together (research R3): in two browser sessions logged in as two Opérateurs, run the same
`PATCH` with the same opposite value at the same moment (e.g. `Promise.all` of two `apiFetch` calls from one
session is enough). Expected: both `204`, exactly one new history row.

## Latency of the last-10 list (SC-003)

Target: p95 below 200 ms server-side, with 1 to 3 screens polling every 500 ms and a season's worth of records
(research R13 assumes 200,000 for the measured reader).

1. Set `show-sql: false` in `application-dev.yml` (logging every query skews timings), start the app, and create a
   Production reader `Ligne 1`.
2. Fill `record` for it in the H2 console (a season in one statement; `tag` rows are needed for the foreign key):
   ```sql
   INSERT INTO tag (id, uid, creation_date) SELECT RANDOM_UUID(), 'LOAD-' || X, CURRENT_TIMESTAMP FROM SYSTEM_RANGE(1, 2000);
   INSERT INTO record (id, tag_id, reader_id, conformity, creation_date)
     SELECT RANDOM_UUID(), (SELECT id FROM tag WHERE uid = 'LOAD-' || (1 + MOD(X, 2000))),
            (SELECT id FROM reader WHERE name = 'Ligne 1'), TRUE, DATEADD('SECOND', -X * 30, CURRENT_TIMESTAMP)
     FROM SYSTEM_RANGE(1, 200000);
   ```
   The columns match `TagEntity` and `RecordEntity` today. If an insert fails on a `NOT NULL` column, check
   `SHOW COLUMNS FROM TAG` / `RECORD` and add it.
3. Log in as any user in the browser, copy the `JSESSIONID` cookie into `SID=<cookie value>`, then run 3 loops in parallel, each polling every
   500 ms for 2 minutes:
   ```zsh
   poll() { for i in $(seq 1 240); do curl -s -o /dev/null -w '%{time_total}\n' -b "JSESSIONID=$1" \
     'localhost:8080/api/records/readers/Ligne%201'; sleep 0.5; done; }
   { poll $SID & poll $SID & poll $SID & wait; } | sort -n | awk '{a[NR]=$1} END {print "p95:", a[int(NR*0.95)]}'
   ```
   Expected: p95 below `0.200`.
4. Optional: temporarily set `show-sql: true`, call the list once, and check the log shows one query joining `tag`
   (plus the reader check), not one query per tag (research R12).
5. Delete the load data afterwards (`DELETE FROM record WHERE tag_id IN (SELECT id FROM tag WHERE uid LIKE 'LOAD-%');
   DELETE FROM tag WHERE uid LIKE 'LOAD-%';`) and put `show-sql` back. For a production figure, repeat against a copy
   of the PostgreSQL database.
