# Quickstart: validating feature 004

## Automated

```zsh
mvn clean test -Dtest='TagServiceTest,TagScanApiTest,ReaderScanSecurityTest'
mvn clean install   # full build, regenerates OpenAPI sources
```

Expected: all green. The cases checked are the rows of the table in [contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md) §2. The window cases in `TagServiceTest` use a fixed `Clock` (research R3), not real waiting.

## Schema upgrade on an existing database

Start the app on the existing dev database (`./data/rfidbackdb.mv.db`) **without deleting it**. Expected: startup succeeds, and the index `IDX_RECORD_READER_TAG_DATE` shows up in the H2 console (`SELECT * FROM INFORMATION_SCHEMA.INDEXES WHERE TABLE_NAME = 'RECORD'`). Restart once more: no error, and the index is not created twice. Before deploying, do the same against a copy of the prod PostgreSQL database (`\d record`).

## Manual (local app)

Prerequisites: start the app with a bootstrap admin (see `CLAUDE.md`), log in, and create two readers in `readers.html`, both in Production mode: `Ligne 1` (token `T1`) and `Ligne 2` (token `T2`). A curl call stands in for each reader:

```zsh
scan() { curl -s -w ' %{http_code}\n' -X POST localhost:8080/api/tags/scan -H 'Content-Type: application/json' -H "x-api-token: $1" -d "{\"uid\":\"$2\",\"isCompliant\":${3:-true}}"; }
```

1. **Blank UID**: `scan T1 ""` and `scan T1 "   "` → `400` with a JSON body whose `detail` names `uid`. `reader.html` for `Ligne 1` shows no new record.
2. **First read**: `scan T1 Q-001` → `200`, no `message`. One record for `Ligne 1`.
3. **Repeat within the window**: `scan T1 Q-001` straight away → `200`, `message: "Duplicate read ignored"`, `processedAt` equal to step 2's. Still one record.
3b. **Non-compliant repeat**: `scan T1 Q-001 false` straight away → `200`, `message: "Duplicate read ignored"`, `isCompliant: false`. Still one record, now non-compliant in `reader.html`. Then `scan T1 Q-001 true` → `isCompliant: false`: a compliant repeat doesn't raise it back.
4. **Another reader**: `scan T2 Q-001` → `200`, no `message`. `Ligne 2` now has one record.
5. **After the window**: wait 11 s, then `scan T1 Q-001` → new record, no `message`.
6. **Deduplication off**: set `app.scan.duplicate-window: 0s` in `application-dev.yml`, restart, run `scan T1 Q-002` twice → two records.

## Latency (SC-004)

SC-004's load is 1 to 3 readers at the same time, each sending scans back to back. Create a third Production reader `Ligne 3` (token `T3`) and set `show-sql: false` in `application-dev.yml` (logging every query skews timings). Then run one loop per reader in parallel, 300 scans each, and read the 95th percentile over all of them:

```zsh
load() { for i in $(seq 1 300); do curl -s -o /dev/null -w '%{time_total}\n' -X POST localhost:8080/api/tags/scan \
  -H 'Content-Type: application/json' -H "x-api-token: $1" -d "{\"uid\":\"$2$i\",\"isCompliant\":true}"; done; }
{ load T1 A- & load T2 B- & load T3 C- & wait; } | sort -n | awk '{a[NR]=$1} END {print "p95:", a[int(NR*0.95)]}'
```

This measures distinct UIDs (a new Record each time). To measure the deduplication path, run it again with a fixed UID per reader: change `$2$i` to `$2` in `load`. Expected: p95 below `0.200` for both runs. For a realistic figure, run it against PostgreSQL with a `record` table already holding a season's worth of rows.
