# Data Model: Statuts de lecture — relecture ignorée, doublon / perdu et cheat

**Feature**: [spec.md](spec.md) | **Research**: [research.md](research.md)

All changes are additive (nullable columns, one new table), applied by `ddl-auto: update`. No constraint is relaxed
and no existing row is rewritten.

## `record` (changed)

| Column | Type | Null | Notes |
|---|---|---|---|
| `anomaly` | varchar(20), `RecordAnomaly` | yes | **New.** `DOUBLON_PERDU` or `CHEAT`; null for an ordinary record and for every record created before this version (R1) |
| `conformity` | boolean | no | Unchanged. Always `false` while `anomaly` is set |

Status (FR-001), derived, never stored:

| `anomaly` | `conformity` | Status |
|---|---|---|
| null | true | Conforme |
| null | false | Non conforme |
| `DOUBLON_PERDU` | false | Doublon / perdu |
| `CHEAT` | false | Cheat |

Transitions:

- created Conforme or Non conforme by a count (reader's verdict), or Doublon / perdu by the rescan of a counted tag,
  whatever the day (R3);
- Conforme / Non conforme → Cheat when a second tag of the crate is read, unless the record has a manual change (R5);
- Conforme → Non conforme by a non-compliant read of the same pass (spec 004, FR-008, unchanged);
- Conforme ↔ Non conforme by hand: Opérateur, Administrateur, kiosk (spec 005, unchanged);
- Doublon / perdu or Cheat → Conforme or Non conforme by hand: Administrateur only (R8); `anomaly` becomes null and
  the record is ordinary again (a former doublon then counts as a crate without picker);
- nothing sets an anomaly by hand.

## `tag` (changed)

| Column | Type | Null | Notes |
|---|---|---|---|
| `last_read_at` | timestamp with time zone | yes | **New.** Last production read of the tag by any reader, ignored reads included (R2). Null until the tag's first read after this version: the rule then uses the counted record's `last_read_at`, else its `creation_date` |

`counted_record_id` and `bucket_id` keep their meaning (spec 004): a tag is "déjà compté" when `counted_record_id`
is set; registration into a bucket clears it, which switches FR-003 and FR-005 off for the tag's next scan (FR-004).

## `scan_setting` (new)

| Column | Type | Null | Notes |
|---|---|---|---|
| `name` | varchar(30), `ScanSettingName` | no | Primary key: `REREAD_WINDOW`, `CHEAT_WINDOW` |
| `seconds` | integer | no | `REREAD_WINDOW`: 0 to 86 400, a multiple of 60; `CHEAT_WINDOW`: 0 to 60 (FR-013). 0 disables the rule |
| `updated_at` | timestamp with time zone | no | Date of the last change (FR-014), from the `Clock` bean |
| `updated_by` | → `app_user.id` | no | The Administrateur who made it (FR-014) |

No row = the default from configuration (`app.scan.reread-window`, `app.scan.crate-window`), with no author and no
date (R6). A row is written only when a value actually changes.

## `record_conformity_change` (changed)

| Column | Type | Null | Notes |
|---|---|---|---|
| `previous_anomaly` | varchar(20), `RecordAnomaly` | yes | **New.** The anomaly the change lifted; null for an ordinary toggle (R8) |

A change is written when the boolean moves, or when an anomaly is lifted (the boolean may then stay `false`).
`new_conformity` alone gives the new status, since a manual change never sets an anomaly.

## Invariants (tested)

- `anomaly` set ⇒ `conformity = false`.
- A Doublon / perdu record has no picker and no bucket; a Cheat record keeps both.
- Dashboard figures never include a Doublon / perdu record: `total`, `nonCompliant`, activity columns, picker rows
  and hours are computed over records whose anomaly is not `DOUBLON_PERDU` (FR-002, R10); `duplicates` counts them
  apart, for the period and reader.
- In any period: `cheats ≤ nonCompliant ≤ total` for the summary, each picker and each hour; picker rows, activity
  columns and hours still add up to the summary.
- A Cheat record counts once in its picker's `total` and in its activity column (FR-011).
- The anomalies list of a period and reader has exactly `duplicates + summary.cheats` rows (FR-015).
- An ignored re-read changes no row of `record`.
- A row of `record_conformity_change` with `previous_anomaly` set has a user author, never a reader.
