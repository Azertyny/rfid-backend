# Data model: Tableau de bord (007)

No new table. The dashboard reads `record` and joins `picker`; the reader filter reads `reader`.

## Persisted entities used

### `RecordEntity` (table `record`) — read only

| Field | Column | Use in the dashboard |
|---|---|---|
| `id` | `id` | counted (`count(r)`) |
| `picker` | `picker_id`, nullable | grouping key of the picker table; set at scan time from the tag's bucket (`TagService.java:82`), never recomputed. Null → "Non attribué" row (FR-009) |
| `reader` | `reader_id` | optional filter (`readerId`) |
| `compliant` | `conformity` | current value, including manual corrections (FR-008); `nonCompliant` = records with `false` |
| `creationDate` | `creation_date` | period filter and hour of day, in the station time zone (FR-007) |

**Schema change**: one new index, created by `ddl-auto: update` at startup ([research.md](research.md) R8):

```java
@Index(name = "idx_record_creation_date", columnList = "creation_date")
```

Existing indexes `idx_record_reader_tag_date` and `idx_record_reader_date` are kept; the second serves the
reader-filtered queries.

### `PickerEntity` (table `picker`) — read only

`id`, `firstname`, `lastname` are returned in each picker row. `DELETE /pickers/{id}` (spec `001`) only refuses a
picker who still has buckets (`PickerService.deletePicker`), so a picker with records but no bucket can be deleted;
what then happens to `record.picker_id` (foreign-key error, or set to null) is outside this feature (plan follow-up,
spec `001`). The dashboard stays consistent either way: if the delete fails the picker keeps their row; if the link is
set to null their records move to the "Non attribué" row and the totals do not change (FR-009).

### `ReaderEntity` (table `reader`) — read only

`id` is the filter value; `name` (`uid` in the API), `active` and `mode` label the selector entries
([research.md](research.md) R6).

## Configuration

| Property | Default | Rule |
|---|---|---|
| `app.station.time-zone` (env `APP_STATION_TIME_ZONE`) | `Europe/Paris` | valid `ZoneId` whose offsets are whole hours; otherwise startup fails ([research.md](research.md) R4, R5) |

## Computed (non-persisted) values — API models

Defined in [contracts/openapi-dashboard.md](contracts/openapi-dashboard.md).

| Model | Fields | Built from |
|---|---|---|
| `RecordCounts` | `total`, `nonCompliant` | `count(r)`, `sum(case when r.compliant = false then 1 else 0 end)` |
| `PickerStats` | counts + `pickerId`, `firstname`, `lastname` (all null for the "Non attribué" row) | `left join r.picker p group by p.id, p.firstname, p.lastname` |
| `HourStats` | counts + `hour` (0-23) | `group by floor(extract(epoch from r.creationDate) / 3600)`, then each UTC hour mapped to its local hour in Java and summed |
| `RecordStats` | `from`, `to`, `timeZone`, `readerId`, `summary`, `pickers`, `hours` | resolved period + the three aggregates |

### Period resolution (`StatsPeriod` → date range)

`today = LocalDate.now(clock.withZone(zone))`

| `period` | `from` | `to` |
|---|---|---|
| `TODAY` (default) | today | today |
| `YESTERDAY` | today − 1 | today − 1 |
| `LAST_7_DAYS` | today − 6 | today |
| `CUSTOM` | `from` param | `to` param |

Query range: `creationDate >= from.atStartOfDay(zone)` and `creationDate < to.plusDays(1).atStartOfDay(zone)`
(half-open, DST-safe).

### Validation rules

- `CUSTOM` requires `from` and `to`; other periods refuse them.
- `from <= to`; `ChronoUnit.DAYS.between(from, to) + 1 <= 31`.
- `readerId`, when given, must be an existing reader (`404` otherwise).

### Invariants

- Σ `pickers[].total` = `summary.total`; Σ `hours[].total` = `summary.total` (same for `nonCompliant`).
- `hours` has exactly 24 entries; `pickers` has no zero row and at most one null-picker row, placed last.
