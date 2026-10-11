# Quickstart: validating feature 004

## Automated

```zsh
mvn clean test -Dtest='TagServiceTest,TagScanApiTest,TagRegistrationApiTest,RegistrationApiTest,OffListTagPurgeTest,ReaderScanSecurityTest'
mvn clean test      # whole suite: the stats, kiosk and registration tests scan tags too
mvn clean install   # full build, regenerates OpenAPI sources
```

Expected: all green. The cases checked are the rows of the table in [contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md) §2,
plus the rule order in [data-model.md](data-model.md). Windows and the station day are tested with a fixed `Clock` in
`TagServiceTest` (research R3), not by waiting.

## Schema upgrade on an existing database

Start the app on the existing dev database (`./data/rfidbackdb.mv.db`) **without deleting it**. Expected: startup succeeds;
the H2 console shows the columns `RECORD.LAST_READ_AT`, `RECORD.BUCKET_ID`, `TAG.COUNTED_RECORD_ID` (all nullable, `null` on
existing rows) and the index `IDX_RECORD_READER_BUCKET_DATE`. Restart once more: no error. Before deploying, do the same
against a copy of the prod PostgreSQL database (`\d record`, `\d tag`).

## Manual (local app)

Prerequisites: start the app with a bootstrap admin (see `CLAUDE.md`) and log in. In `readers.html`, create two Production readers,
`Ligne 1` (token `T1`) and `Ligne 2` (token `T2`). In `buckets.html` / `tags.html`, register into bucket `1` (assigned to a picker)
four tags from `src/main/resources/tags/rfid_tag_list.csv`, called `A`, `B`, `C`, `D` below. A curl call stands in for each reader:

```zsh
scan() { curl -s -w ' %{http_code}\n' -X POST localhost:8080/api/tags/scan -H 'Content-Type: application/json' -H "x-api-token: $1" -d "{\"uid\":\"$2\",\"isCompliant\":${3:-true}}"; }
```

1. **Count, the tag leaves its bucket**: `scan T1 $A` → `200`, no `message`. One Record for `Ligne 1` with the picker (`reader.html`).
   `buckets.html`: bucket `1` no longer lists `A`.
2. **Crate left in front of the reader** (the reported bug): `for i in $(seq 15); do scan T1 $A; sleep 1; done` → every answer
   `"Duplicate read ignored"`, still one Record, although more than 10 s passed since it was created.
3. **Two tags on one crate**: `scan T1 $B; scan T1 $C` → the first `200` with no `message`, the second
   `"Second tag of the crate ignored"` with the first one's `processedAt`. One new Record; bucket `1` lists neither `B` nor `C`.
4. **Two crates of the same picker**: `scan T1 $D` more than 2 s after `B`, while still re-reading `B` (`scan T1 $B` just before) → a new Record (no `message`): a crate still in front of the reader doesn't swallow the next one.
5. **Rescan the same day**: wait 11 s, then `scan T1 $A` → `200`, `isCompliant: false`, `message: "Tag already scanned today"`.
   A new Record, non-compliant, with no picker. Run it again straight away → `"Duplicate read ignored"`, no third Record.
   `scan T2 $C` (the crate's second tag, other line) → `"Tag already scanned today"` as well.
6. **Register again**: register `A` into bucket `1` again, then `scan T1 $A` → an ordinary Record with the picker, no `message`.
7. **Earlier day**: not practical by hand (it needs the station day to change). `TagServiceTest` covers it with a fixed `Clock`;
   or set `APP_STATION_TIME_ZONE` to a zone where midnight has just passed between two scans.

## Latency (SC-004)

As in the first delivery: SC-004's load is 1 to 3 readers sending scans back to back. Register the tags used by the loop into
buckets first, otherwise every scan goes through rule 4 with no bucket and the crate lookup isn't measured. Create a third
Production reader (`T3`) and set `show-sql: false` in `application-dev.yml`, then:

```zsh
load() { for uid in "${@:2}"; do curl -s -o /dev/null -w '%{time_total}\n' -X POST localhost:8080/api/tags/scan \
  -H 'Content-Type: application/json' -H "x-api-token: $1" -d "{\"uid\":\"$uid\",\"isCompliant\":true}"; done; }
```

Feed each reader its own slice of registered UIDs from the reference list (`load T1 uid1 uid2 …` & `load T2 …` & `load T3 …`
& `wait`, sorted, 95th percentile as before). Then run it again with the same UIDs straight away: those reads go through
rule 1 or rule 3. Expected: p95 below `0.200` in both runs. For a realistic figure, run it against PostgreSQL with a season's
worth of `record` rows.
