# Data Model: Activités des lignes de production

All schema changes go through entities and `spring.jpa.hibernate.ddl-auto: update` (`CLAUDE.md`, Persistence). Every
new column on an existing table is **nullable** (or has a default), so `ddl-auto` can add it over existing rows; no
existing constraint is relaxed, so no startup upgrade class is needed.

## New: `ActivityEntity` → table `activity`

| Field | Column | Type | Rules |
|---|---|---|---|
| `id` | `id` | UUID | generated |
| `name` | `name` | varchar(100), not null | trimmed; not blank; ≤ 100 (FR-001) |
| `nameKey` | `name_key` | varchar(100), not null, **unique** | `name.trim().toLowerCase(Locale.ROOT)`, set in `@PrePersist`/`@PreUpdate` (research R4) |
| `active` | `active` | boolean, not null, default `true` | `false` = désactivée (FR-002) |
| `lines` | join table `reader_activity` | `Set<ReaderEntity>` | `@ManyToMany`, owning side (research R5) |
| `creationDate` / `updateDate` | | timestamptz | `@CreationTimestamp` / `@UpdateTimestamp`, as on `ReaderEntity` |

**Transitions**: active ⇄ disabled (PATCH). Disabling clears it as current activity on the lines where it is
current, after confirmation (FR-007, research R6). Delete only when no record and no change-log row references it
(research R7).

## New: join table `reader_activity`

| Column | Type | Rules |
|---|---|---|
| `activity_id` | UUID, FK `activity` | PK part |
| `reader_id` | UUID, FK `reader` | PK part; the reader must be in `PRODUCTION` mode when associated (FR-006) |

A reader switched to `ENREGISTREMENT` keeps its rows (FR-013).

## Changed: `ReaderEntity` → table `reader`

| New field | Column | Type | Rules |
|---|---|---|---|
| `currentActivity` | `current_activity_id` | UUID, FK `activity`, nullable, lazy | associated and active when set (FR-008); null for `ENREGISTREMENT` readers |
| `currentActivitySetAt` | `current_activity_set_at` | timestamptz, nullable | instant of the choice; null when `currentActivity` is null |

**Effective current activity** (research R3): `currentActivity` if `currentActivitySetAt` falls on today's date in the
station zone (`app.station.time-zone`), else none. Every read of the current activity (scan, kiosk, choice) uses this
rule.

**State transitions of a line's current activity** (each one that changes the value writes a `LineActivityChange`):

| From | Event | To | Author |
|---|---|---|---|
| any | Opérateur/Administrateur or kiosk chooses A (associated, active) | A, `setAt` = now | user / reader |
| A | chooses "aucune activité" | none | user / reader |
| A | Administrateur removes A from the line, confirmed | none | user |
| A | Administrateur disables A, confirmed | none | user |
| A | reader switched to `ENREGISTREMENT` | none | user |
| A set before today (station time) | midnight job or startup catch-up | none, `changed_at` = the due midnight | system |

## Changed: `RecordEntity` → table `record`

| New field | Column | Type | Rules |
|---|---|---|---|
| `activity` | `activity_id` | UUID, FK `activity`, nullable, lazy | the reader's effective current activity at scan time (FR-014); never updated afterwards (FR-015) |

New index `idx_record_activity (activity_id)` for the delete check (PostgreSQL does not index foreign keys). The
banner count (records of a reader since midnight with `activity_id` null) uses the existing `idx_record_reader_date`.

## New: `LineActivityChangeEntity` → table `line_activity_change`

Append-only; never updated or deleted.

| Field | Column | Type | Rules |
|---|---|---|---|
| `id` | `id` | UUID | generated |
| `reader` | `reader_id` | FK `reader`, not null | the line |
| `previousActivity` | `previous_activity_id` | FK `activity`, nullable | null = no activity |
| `newActivity` | `new_activity_id` | FK `activity`, nullable | null = no activity |
| `changedAt` | `changed_at` | timestamptz, not null | set by the code: now, or the due midnight for the system reset |
| `authorType` | `author_type` | varchar(10) enum `USER`/`READER`/`SYSTEM`, not null | |
| `authorUser` | `author_user_id` | FK `app_user`, nullable | set iff `USER` |
| `authorReader` | `author_reader_id` | FK `reader`, nullable | set iff `READER` (kiosk token) |

`@PrePersist` refuses a row whose author fields do not match `authorType`, and a row where previous = new. Index
`idx_line_activity_change_reader_date (reader_id, changed_at)`.

## Repository additions

| Repository | Method | Use |
|---|---|---|
| `ActivityRepository` (new) | `existsByNameKey`, `existsByNameKeyAndIdNot`, `findAllByOrderByNameAsc` (with `lines` fetched) | FR-001, FR-002, FR-004 |
| `ReaderRepository` | `findWithLockById`, `findWithLockByName` (`PESSIMISTIC_WRITE`, current activity fetched) | serialise choices, dissociations, mode switches and the job (research R9); the choice locks on its first load by uid |
| `ReaderRepository` | `findWithCurrentActivityById`, `findWithCurrentActivityByName` (no lock) | scan stamp (research R2), kiosk read |
| `ReaderRepository` | `findIdsByCurrentActivitySetAtBefore(instant)` → ids | daily job (research R3) |
| `ReaderRepository` | `findIdsByCurrentActivity(activity)` → ids | lines losing a disabled activity (research R6) |
| `RecordRepository` | `existsByActivity`, `countByReaderAndCreationDateGreaterThanEqualAndActivityIsNull` | delete check (R7), banner (R10) |
| `RecordRepository` | `findTop10ByReader_Name…` entity graph gains `activity` | FR-017 in one query (R11) |
| `LineActivityChangeRepository` (new) | `isReferenced(activity)`, `findAllByReaderOrderByChangedAtAsc` | delete check (R7), tests and SC-005 |

The two `findIds…` queries return ids, not readers (found during implementation): the caller then locks each reader
with `findWithLockById`. Had they returned readers, those would already sit in the persistence context, and the lock
query would hand back that copy as it was read, not as it is once locked, so a choice made in between could be
overwritten. For the same reason `setLineActivity` locks on its first load (`findWithLockByName`) instead of reading
the reader and locking it afterwards.

## Validation summary

| Input | Rule | Answer |
|---|---|---|
| activity name | blank after trim, > 100 | `400` |
| activity name | same `name_key` as another activity | `409` |
| association | reader in `ENREGISTREMENT`, unknown activity id | `400` |
| association / disable | would clear a line's current activity, `confirmed` not true | `409 LinesLosingActivity` |
| delete | referenced by a record or a change | `409` |
| choice | unknown reader uid | `404` |
| choice | reader in `ENREGISTREMENT`; activity unknown, disabled or not associated | `400` |
| choice (kiosk) | another reader's uid | `403` |
