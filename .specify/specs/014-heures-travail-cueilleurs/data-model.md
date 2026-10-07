# Data Model: Heures de travail des cueilleurs et caisses par activité

**Feature**: [spec.md](spec.md) | **Research**: [research.md](research.md)

Schema changes go through the entities with `spring.jpa.hibernate.ddl-auto: update` (`CLAUDE.md`): one new table,
nothing relaxed, no existing column touched.

## New table `picker_work_day` — entity `PickerWorkDayEntity`

The hours a picker worked on one station day (spec Key Entities "Heures de travail", FR-001, FR-002, FR-005; R1, R2).

| Column | Type | Null | Notes |
|---|---|---|---|
| `id` | `uuid` | no | PK, generated (as every entity) |
| `picker_id` | `uuid` | no | FK → `picker.id` |
| `work_date` | `date` | no | Day in the station time zone (`APP_STATION_TIME_ZONE`) |
| `minutes` | `integer` | no | 15 ≤ minutes ≤ 1440, multiple of 15 |
| `updated_at` | `timestamp with time zone` | no | Last change, from the `Clock` bean |
| `updated_by_id` | `uuid` | no | FK → `app_user.id`, the Administrateur of the last change |

Constraints and indexes:

- unique `uk_picker_work_day_picker_date (picker_id, work_date)` — one entry per picker and day (FR-002);
- index `idx_picker_work_day_date (work_date)` — the dashboard's period sum (R11);
- the minute rule is checked by the service (`400` before any write); a `@PrePersist`/`@PreUpdate` guard throws if
  it is ever broken, as `LineActivityChangeEntity` guards its author.

Relations: many-to-one `picker` (lazy), many-to-one `updatedBy` (`UserEntity`, lazy). Users are never deleted (spec
008), so the author FK never blocks; pickers are, so their rows are deleted first (R7).

### Lifecycle

```text
(no row) ──PUT hours=h──▶ row(minutes=h×60, updated_*) ──PUT hours=h'──▶ row(minutes=h'×60, updated_* refreshed)
    ▲                                                                       │
    └──────────────── PUT hours=null / picker deleted ◀────────────────────┘
```

"No row" is the only representation of "non saisi" (FR-009); 0 h cannot be stored (FR-001).

## Repository additions

`PickerWorkDayRepository` (new):

- `List<PickerWorkDayEntity> findByWorkDate(LocalDate day)` with the picker and author fetched — day screen (R4);
- `Optional<PickerWorkDayEntity> findByPickerAndWorkDate(PickerEntity picker, LocalDate day)` — upsert (R3);
- `List<PickerMinutesView> sumMinutesByPicker(LocalDate first, LocalDate last)` — `select p.id, p.firstname,
  p.lastname, sum(w.minutes) … where w.workDate between :first and :last group by …` (R6);
- `void deleteByPicker(PickerEntity picker)` — picker deletion (R7).

`RecordRepository` (spec 007 statistics block):

- `List<PickerActivityCountsView> countByPickerAndActivity(start, end)` and `…ForReader(start, end, reader)` —
  `select p.id as pickerId, a.id as activityId, a.name as activityName, count(r) as total from RecordEntity r left
  join r.picker p left join r.activity a where <IN_RANGE> [and r.reader = :reader] group by p.id, a.id, a.name`
  (R6). No new index: same range as the existing queries.
- `countByPicker(start, end)` is reused as is for a day's record counts on the hours screen (R4).

## API-side shapes (generated DTOs, see [contracts](contracts/openapi-work-hours.md))

| Schema | Fields | Notes |
|---|---|---|
| `WorkDay` (new) | `day`, `today`, `pickers: WorkDayPicker[]` | Every picker (R4) |
| `WorkDayPicker` (new) | `pickerId`, `firstname`, `lastname`, `hours?`, `records`, `missingHours`, `updatedAt?`, `updatedBy?` | `hours` decimal, null = non saisi |
| `SaveWorkDayRequest` (new) | `day`, `entries: WorkHoursEntry[]` (1..500) | All or nothing (R3) |
| `WorkHoursEntry` (new) | `pickerId`, `hours?` | `null` deletes; 0.25 ≤ hours ≤ 24, multiple of 0.25 |
| `ActivityColumn` (new) | `activityId?`, `name?` | null/null = "Sans activité" |
| `ActivityCount` (new) | `activityId?`, `total` | Non-zero entries only |
| `PickerStats` (extended) | + `activities: ActivityCount[]`, + `workHours?` | |
| `RecordStats` (extended) | + `activities: ActivityColumn[]`, + `workHours?`, + `includesToday` | |

## Invariants (tested, SC-002)

- For each picker row, `sum(activities[].total) = total`; per activity column, the sum over rows (including "Non
  attribué") equals the column's records in the period; `sum(rows.total) = summary.total` (spec 007 FR-009).
- Without a reader: `sum(rows.workHours ?? 0) = workHours`; a picker with hours and no record is a row with
  `total = 0` and `activities = []`; "Non attribué" has `workHours = null`.
- With a reader: `workHours = null` everywhere, and no row is added for hours alone.
