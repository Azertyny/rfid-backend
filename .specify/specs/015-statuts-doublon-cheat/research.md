# Research: Statuts de lecture — relecture ignorée, doublon / perdu et cheat

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-11-15

The spec has no `NEEDS CLARIFICATION` left (Clarifications 2026-11-15, 11 answers; R3, R5, R9 and R10 were revised
after the last three: a Doublon / perdu whatever the day, kept out of the totals, and a list of anomalies). The decisions below settle the
design questions the spec leaves to planning. Code read for them: `service/TagService.registerScan` (rules 1 to 4 of
spec 004), `service/RecordService.updateRecordConformity`, `repository/RecordRepository` (dashboard aggregates),
`configuration/SecurityConfig`, `front/reader.html`, `front/index.html`.

## R1 — Storing the status: keep the boolean, add a nullable anomaly

- **Decision**: `record.conformity` (boolean) stays. A new nullable column `record.anomaly` holds `DOUBLON_PERDU` or
  `CHEAT` (`entity/RecordAnomaly`, `@Enumerated(STRING)`). A record with an anomaly always has `conformity = false`.
  The status of FR-001 is derived: anomaly if set, else Conforme / Non conforme from the boolean. The API exposes it
  as `status` (`CONFORME`, `NON_CONFORME`, `DOUBLON_PERDU`, `CHEAT`) next to `isCompliant`, which stays.
- **Rationale**: a Cheat then counts with the non-compliant (FR-002) in every existing query, which all read
  `conformity`; only the exclusion of Doublon / perdu from the totals needs a predicate (R10). The schema change is
  one nullable column,
  which `ddl-auto: update` adds; existing rows read as Conforme / Non conforme with no data migration (spec, Edge
  Cases: old rescans are not relabelled). The scan response and `RecordSummary.isCompliant` keep their meaning, so
  the readers' firmware and a cached front page keep working (spec, Assumptions).
- **Alternatives considered**: replace the boolean by a 4-value `status` column (every aggregate, the PATCH route,
  the history table and 14 test classes change, and `ddl-auto` cannot drop `conformity`'s `NOT NULL`: a second
  `ConformityAuthorSchemaUpgrade`); a separate `record_anomaly` table (a join in every dashboard query for one
  nullable value).
- **Checked during implementation (2026-11-15, T041)**: on H2 Hibernate creates `record.anomaly`,
  `record_conformity_change.previous_anomaly` and `scan_setting.name` with the native type
  `enum ('CHEAT','DOUBLON_PERDU')` (resp. `enum ('CHEAT_WINDOW','REREAD_WINDOW')`). On PostgreSQL Hibernate 6 is
  expected to create `varchar` with a `check (… in (…))` constraint, like the existing `reader.mode` and
  `app_user.role`; not verified, no PostgreSQL server was available. Either way `ddl-auto: update` never widens the
  list (`CLAUDE.md`), so a third anomaly needs a startup upgrade like `configuration/ConformityAuthorSchemaUpgrade`.
  Written in `entity/RecordAnomaly.java`.

## R2 — The tag's last read

- **Decision**: new nullable column `tag.last_read_at`, set to the scan time by every production read of a tag in
  the reference list, whatever the rule that handled it (counted, same pass, crate, ignored, doublon). FR-003 reads
  it; when it is null (tags last read before this version) the rule falls back to the counted record's
  `last_read_at`, then its `creation_date`.
- **Rationale**: FR-003's delay slides from the tag's last read by **any** reader, ignored reads included
  (Clarifications 2026-11-15). `record.last_read_at` cannot carry it: it is the same reader's pass (spec 004,
  FR-008), and moving it on another reader's read would stretch that reader's 10 s pass rule. The tag row is already
  loaded and written by a scan; the counted record's row lock (`findWithLockById`) already serialises two readers
  reading the same counted tag.
- **Alternatives considered**: reuse `record.last_read_at` (mixes two windows, see above); a table of ignored reads
  (a row per re-read of a crate left near a reader, thousands a day, for one timestamp).

## R3 — Order of the scan rules

- **Decision**: `TagService.registerScan`, for a tag in the reference list, first matching rule:

  | # | Rule | Condition | Result |
  |---|------|-----------|--------|
  | 1 | Same pass (spec 004, FR-008, unchanged) | counted record from this reader, read less than `duplicate-window` ago | no record; a non-compliant verdict still lowers the record |
  | 2 | Cheat (FR-006, FR-007) | tag in a bucket, this reader counted another tag of that bucket less than the cheat window ago | no record; the crate's record becomes Cheat (R5) |
  | 3 | Ignored re-read (FR-003, new) | tag counted, last read (R2) less than the re-read window ago | no record, nothing changed but `tag.last_read_at` |
  | 4 | Doublon / perdu (FR-005) | tag counted, whatever the day | new record, anomaly `DOUBLON_PERDU`, no picker |
  | 5 | Count (spec 004, unchanged) | otherwise | new record, tag leaves its bucket |

- **Rationale**: FR-004 puts the same pass before the 30 minutes. Rules 2 and 3 never compete: a tag in a bucket has
  no counted record (registration clears it, counting takes it out of the bucket), so their order is free. The day
  no longer matters (Clarifications 2026-11-15): rule 4 drops the `startOfToday()` test of spec 004, so a counted tag
  only reaches rule 5 again after a registration into a bucket; a tag counted at 23:50 is ignored at 0:10 and a
  Doublon / perdu at 0:30. Rule 5 still serves a tag never counted, with or without a bucket. A window of zero
  disables its rule (`isPositive()`, as today).
- **Alternatives considered**: merge rules 1 and 3 into one 30-minute window (loses "the non-compliant verdict wins
  during the pass", which the spec keeps).

## R4 — An ignored re-read

- **Decision**: the reader gets `200` with the counted record's `isCompliant` and `processedAt` and
  `message: "Recent read ignored"`. The record is not touched: no `last_read_at`, no verdict change.
- **Rationale**: FR-003 ("la lecture existante n'est pas modifiée") and the Edge Case on the verdict. Same response
  shape as the other absorbed reads, so the firmware has nothing to learn.

## R5 — Doublon / perdu and Cheat records

- **Decision**: rule 4 keeps the current rescan record (no picker, no bucket, current activity, `conformity = false`)
  and adds `anomaly = DOUBLON_PERDU`; its message is unchanged (`"Tag already scanned today"`), kept even
  when the rescan is on a later day: the readers' firmware is not in this repository, so nothing proves it ignores
  the text, and a slightly inexact wording costs less than a reader that stops recognising it.
  Rule 2 keeps absorbing the
  second tag (it leaves the bucket, its counted record is the crate's) and now sets the crate's record to
  `conformity = false`, `anomaly = CHEAT`, unless the record has a manual change (`record_conformity_change` row),
  in which case only `last_read_at` moves. Message unchanged (`"Second tag of the crate ignored"`); the response's
  `isCompliant` is the record's, so `false`.
- **Rationale**: FR-005 and FR-007; the manual-change guard is spec 005 FR-006, already applied by `absorb`. A third
  tag of the bucket inside the window finds the same crate record: still one Cheat record (Edge Cases).
- **Alternatives considered**: a new record for the second tag (refused by Clarifications 2026-11-15).

## R6 — Settings storage

- **Decision**: new table `scan_setting`, one row per setting: `name` (primary key, `REREAD_WINDOW` or
  `CHEAT_WINDOW`), `seconds` (int), `updated_at`, `updated_by` → `app_user`. No row means the default, taken from
  configuration: `app.scan.reread-window` (new, `30m`) and `app.scan.crate-window` (existing, `2s`).
  `service/ScanSettingsService` reads the two rows from the database on **every scan**, without a cache.
  `app.scan.duplicate-window` stays a plain property.
- **Rationale**: FR-008 asks for effect on the next scan with no restart; reading two rows by primary key per scan
  is the simplest way to guarantee it and cannot go stale (SC-006). One row per setting gives FR-014's author and
  date per setting. Keeping the properties as defaults means a fresh install shows 30 min / 2 s (US5, scenario 1),
  and tests keep steering the windows through properties.
- **Alternatives considered**: an in-memory cache refreshed on save (wrong as soon as there are two instances, and
  saves one primary-key read on a route measured at a few ms); a single row with four columns per setting (works,
  but a third setting means new columns); a history table of changes (FR-014 asks for the last change only).

## R7 — Settings API

- **Decision**: new OpenAPI tag `ScanSettings` → `ScanSettingsApiDelegate`. `GET /scan-settings` returns both
  settings, each `{ value, updatedAt, updatedBy }` (the last two null while the default applies).
  `PUT /scan-settings` takes `{ rereadWindowMinutes, cheatWindowSeconds }`, both required integers, `0..1440` and
  `0..60` (FR-013), validated by the generated bean validation (`400`). Only a value that differs from the one in
  force gets a new author and date. Administrateur only: one rule in the user chain; the kiosk chain already denies
  everything it does not list (`403`).
- **Rationale**: one small form, one save; the units are the ones the Administrateur types (FR-013). Two
  Administrateurs saving together is last write wins.
- **Alternatives considered**: `PATCH` per setting (two routes or a partial body for two numbers).

## R8 — Lifting an anomaly

- **Decision**: no new route. `PATCH /records/{id}/conformity` (`{ isCompliant }`) on a record **with an anomaly**:
  an Administrateur session clears the anomaly and sets the boolean, always writing a history row, even when the
  boolean does not move (Cheat → Non conforme); an Opérateur session or a kiosk token gets `403` and nothing changes.
  On a record without anomaly the route behaves as today, for the three callers. `record_conformity_change` gains a
  nullable `previous_anomaly`; the history API gains `previousStatus` and `newStatus`.
- **Rationale**: FR-010. The role check depends on the record's state, so it lives in `RecordService` next to the
  existing "own reader only" check, not in the route matrix. The history must show that a Cheat was lifted, which two
  booleans cannot say when both are `false`.
- **Alternatives considered**: a dedicated `DELETE /records/{id}/anomaly` (a second way to change the same value,
  and the kiosk page would call two routes); letting the request carry a `status` (would allow setting Cheat by
  hand, which FR-010 forbids).

## R9 — Where the Administrateur lifts an anomaly

- **Decision**: two places, one route (R8).
  - The line screen (`front/reader.html`) opened with an Administrateur session: an anomaly card offers "Conforme" /
    "Non conforme" inline. For an Opérateur and for the kiosk the card shows the status and no action.
  - A new Administrateur page `front/anomalies.html` (FR-015), linked from the dashboard's two anomaly figures with
    the dashboard's period and reader: `GET /records/anomalies` with the same `period`, `from`, `to`, `readerId`
    parameters as `GET /records/stats` (same resolution and 31-day limit, shared code in `RecordStatsService`), plus
    `page` / `size` (the existing paging parameters, `PageMetadata`), newest first. Each row: date, reader, tag,
    status, activity, picker; two inline buttons call the PATCH route, then the row is removed.
- **Rationale**: the line screen only lists a reader's 10 latest records; an anomaly noticed on the dashboard the
  next day must still be reachable (Clarifications 2026-11-15, SC-004). Reusing the dashboard's period makes the
  list's row count equal to the figures the Administrateur just read. The query is `anomaly is not null` on the
  `creation_date` range, served by `idx_record_creation_date` / `idx_record_reader_date`; anomalies are a small
  share of a period's records, so no new index.
- **Alternatives considered**: a block inside `index.html` (the dashboard is open to Opérateurs, the list is not,
  and the page is already long); a generic record search with a status filter (far more than FR-015 asks).

## R10 — Dashboard

- **Decision**: every dashboard aggregate of `RecordRepository` (summary, by picker, by picker and activity, by hour,
  with and without a reader) excludes Doublon / perdu records with one shared predicate
  (`r.anomaly is null or r.anomaly <> DOUBLON_PERDU`) next to `IN_RANGE`. `RecordCounts` gains `cheats` (one more
  `sum(case …)` in `COUNTS`), so the summary, each picker row and each hour get it from the queries that already
  run. `RecordStats` gains `duplicates`, the period's Doublon / perdu count, from one extra count query.
  `front/index.html`: two summary figures linking to the anomalies page (Administrateur), a "Cheat" column in the
  picker table, its Total row and the CSV.
- **Rationale**: FR-002 — a Doublon / perdu is not a crate. Excluding it in the `where` keeps every existing
  invariant (picker rows, activity columns and hours each add up to the summary) and stops a "Non attribué" row made
  only of doublons from appearing. A Cheat keeps `conformity = false` and stays in `total` (FR-011). One extra count
  on an indexed range does not move the dashboard's 1 s budget (spec 007 SC-002).
- **Consequence to accept**: periods before this version keep their old rescans in the totals (they have no
  anomaly); a doublon lifted by the Administrateur re-enters the totals as an ordinary record without picker. The
  kiosk's "records without activity" count (spec 012, FR-009a) is not a dashboard total and is left as it is.
- **Alternatives considered**: conditional sums instead of a `where` predicate (a picker-less group with `total = 0`
  would have to be filtered out in Java); a `duplicates` count per picker (always zero: a doublon has no picker).

## R11 — Tests

- **Decision**: `TagServiceTest` and `TagScanApiTest` for the five rules and their edges (sliding delay, midnight,
  rescan on a later day, tag never counted, zero windows, re-registration, manual change before the second tag,
  three tags); `ScanSettingsServiceTest` and
  `ScanSettingsApiTest` (defaults, bounds, author kept when a value is unchanged, effect on the next scan);
  `RecordServiceTest` and `RecordApiTest` for the lift, its `403`s and the anomalies list (period, reader, paging,
  a lifted record leaves it); `RecordStatsServiceTest` / `RecordStatsApiTest` for the two counts and the totals
  without doublons (spec US4 scenario 10); `AccessMatrixSecurityTest` and `KioskReaderTokenSecurityTest` for
  `/api/scan-settings` and `/api/records/anomalies`. Time is driven by the `Clock` bean and by ageing rows, as `TagScanApiTest.age` does today.
- **Rationale**: SC-001 is exactly a table of scans and expected outcomes.

## R12 — Performance

- **Decision**: no new index. A scan adds one read of `scan_setting` (two rows, primary key) and writes the tag row
  it already loads. SC-005 is re-measured with spec 004's quickstart protocol (3 readers in parallel), on the count,
  ignored and cheat paths.
