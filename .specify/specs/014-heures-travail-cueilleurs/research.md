# Research: Heures de travail des cueilleurs et caisses par activité

**Feature**: [spec.md](spec.md) | **Plan**: [plan.md](plan.md) | **Date**: 2026-10-07

The spec has no `NEEDS CLARIFICATION` left (Clarifications 2026-10-07). The decisions below settle the design
questions the spec leaves to planning.

## R1 — Storing a duration

- **Decision**: store whole **minutes** (`int`), constrained to a multiple of 15 between 15 and 1440. The API speaks
  in decimal **hours** (`7.25`), converted at the service boundary (`hours × 60`, refused if not a whole multiple of
  15 minutes).
- **Rationale**: FR-001 asks for quarter-hour steps; an integer sums exactly over 31 days (no floating drift in the
  Total row, FR-010), and `sum(minutes)` is a plain SQL aggregate on H2 and PostgreSQL.
- **Alternatives considered**: `numeric(4,2)` hours (exact too, but the quarter-hour rule becomes a modulo on a
  decimal and Java gets `BigDecimal` everywhere); `double` (sums drift: 0.1-style errors in a total); an `Duration`
  ISO string in the API (`PT7H15M`, unreadable for the front and the CSV).

## R2 — One row per picker and day, no history

- **Decision**: new table `picker_work_day` (picker, `work_date` in the station calendar, minutes, `updated_at`,
  `updated_by` → `app_user`), unique on `(picker_id, work_date)`. Saving again for the same day updates the row
  (FR-002); clearing the hours deletes the row, so "no row" is the only meaning of "non saisi" (FR-009).
- **Rationale**: FR-005 asks only for the author and date of the **last** change; a change log like
  `line_activity_change` (spec 012) would be unused. `work_date` is a `LocalDate` already in the station zone, so no
  instant range is needed to sum a period (the dashboard's days are station days, spec 007 FR-007).
- **Alternatives considered**: keep a row with `minutes = 0` for "cleared" (two meanings for "no hours" and 0 h is
  refused by FR-001); a history table (not requested, YAGNI); a timestamp instead of a date (would need the zone at
  every read and breaks on a server in UTC).

## R3 — Saving: one bulk call per day

- **Decision**: `PUT /work-hours` with `{ day, entries: [{ pickerId, hours | null }] }`. Each entry upserts the
  picker's hours for that day, `null` deletes them; pickers not listed are untouched. The call is all or nothing:
  any invalid entry (hours out of range or off the quarter hour, a duplicated `pickerId`) → `400`, an unknown picker
  → `404`, and nothing is saved. A `day` after today in the station zone → `400` (FR-003). Past days are open (late
  entry, corrections). The author is the session's user, the date the `Clock` bean (as spec 012).
- **Rationale**: FR-004 and SC-001 (30 pickers in under 5 minutes) call for one save of the whole team; one
  idempotent `PUT` also covers correction (FR-002) and deletion (FR-003) without three routes. Rows are upserted
  under the unique constraint; two Administrateurs saving the same day concurrently is last-write-wins, which FR-002
  allows ("une nouvelle saisie remplace la précédente"). A unique-constraint race on insert is retried once by the
  service, then answered `409`. The write runs in a `TransactionTemplate` and `saveWorkDay` itself is not
  transactional: a `DataIntegrityViolationException` marks its transaction rollback-only, so the retry must start a
  fresh transaction from outside the failed one, never inside an outer transaction that would still fail at commit
  (`UnexpectedRollbackException`).
- **Alternatives considered**: `PUT /pickers/{id}/work-hours/{day}` per picker (30 calls, partial failures visible to
  the user); `POST` + `PATCH` + `DELETE` (more routes, same result); writing under `/pickers/**` (would inherit the
  Picker matrix, fine for Administrateur only, but the read side needs the day's record counts, which is not picker
  data).

## R4 — Reading a day (morning entry and missing hours)

- **Decision**: `GET /work-hours?day=` (optional, default today in the station zone; future day → `400`) returns
  the resolved `day`, `today` (boolean) and **every picker**, sorted like the dashboard (French collation, last name
  then first name), each with: `hours` (null when not entered), `records` (records attributed to the picker that
  day, all readers), `missingHours` (`records > 0` and no hours), `updatedAt`, `updatedBy` (username).
- **Rationale**: in the morning nobody has records yet, so a list limited to "pickers with records or hours" (FR-014)
  would be empty when the entry is made (User Story 1). Listing every picker serves both the morning entry and the
  check of User Story 3; `missingHours` is FR-014's flag and SC-004's measure. The picker list is small (tens), one
  query for pickers, one grouped count of the day's records (the `countByPicker` query of spec 007 on the day's
  instant range), one query of the day's hours.
- **Alternatives considered**: only pickers with records or hours (empty in the morning); two endpoints for entry and
  check (same data twice).

## R5 — Dashboard: extend `GET /records/stats`, not a new endpoint

- **Decision**: `RecordStats` gains:
  - `activities`: the table's activity columns, `{ activityId | null, name | null }`, those carried by at least one
    record of the period (and reader), sorted by name in French collation, the null "Sans activité" column last
    (FR-008);
  - `workHours` (number, nullable): sum of the hours of the period, the Total row's value (FR-010); null when a
    reader is selected (FR-011);
  - `includesToday` (boolean): the period contains today in the station zone (Edge Case "Journée en cours").

  `PickerStats` gains `activities: [{ activityId | null, total }]` (non-zero entries only, the front fills the
  zeros) and `workHours` (number, nullable: no hours in the period, or a reader selected — FR-009, FR-011).
- **Rationale**: FR-012 asks the server to compute, with the same period filter, and FR-010 needs the hours, the
  activity counts and the existing totals to stay consistent in one response. The CSV export (FR-013) is built by the
  page from the same response, as today.
- **Alternatives considered**: a new `GET /records/stats/pickers` (a second request whose period resolution could
  disagree with the first around midnight); one `RecordCounts` per activity in `PickerStats` (non-compliant per
  activity is not asked).

## R6 — Computing the new columns

- **Decision**:
  - Activity counts: one more grouped query, `RecordRepository.countByPickerAndActivity` (and `…ForReader`, same
    split as spec 007 T008 to avoid `:reader is null` on PostgreSQL): `left join r.picker p left join r.activity a
    … group by p.id, a.id, a.name` over the same instant range. The service builds the columns from its rows.
  - Hours: `PickerWorkDayRepository.sumMinutesByPicker(firstDay, lastDay)` → `(pickerId, firstname, lastname,
    minutes)`, a `between` on `work_date`, used only without a reader.
  - Merge: picker rows of spec 007 stay the base (totals, non-compliant); the activity rows fill `activities`; the
    hours fill `workHours`; without a reader, pickers with hours and no record are added with zero counts (FR-009),
    in the same sorted list. `summary` is unchanged, so the picker rows still add up to it (spec 007 FR-009, FR-010).
- **Rationale**: two cheap aggregates on top of the three existing ones; activity sums per picker equal the picker's
  total by construction (same rows, same range). The "Non attribué" row has activity counts and `workHours = null`.
- **Alternatives considered**: a single query joining hours and records (a records × days product, sums inflated by
  the join); computing activity counts in the page (spec 007 FR-002 forbids raw records in the browser).

## R7 — Deleting a picker

- **Decision**: `PickerService.deletePicker` deletes the picker's `picker_work_day` rows
  (`PickerWorkDayRepository.deleteByPicker`) before the picker, in the same transaction (Edge Case "Cueilleur
  supprimé").
- **Rationale**: the foreign key would otherwise block the deletion; no hours without their picker.
- **Alternatives considered**: `ON DELETE CASCADE` (not expressible reliably through `ddl-auto: update` on both
  databases); keeping orphan hours (meaningless).

## R8 — Access

- **Decision**: user chain: `GET` and `PUT /api/work-hours` → `ADMINISTRATEUR` (FR-006). The kiosk chain does not
  list the route, so a reader token gets `403`; `GET /api/records/stats` stays Administrateur + Opérateur, hours
  included (FR-006: consultation stays open). Writes need CSRF like every user-chain write.
- **Rationale**: matches the spec 008 matrix pattern ("unlisted routes are denied").
- **Alternatives considered**: Opérateur read access to `GET /work-hours` (not asked; the dashboard already shows the
  hours to them).

## R9 — Front

- **Decision**:
  - New Administrateur page `front/work-hours.html` ("Heures"), link in every page's navbar with `data-admin-only`:
    a day selector (default: the day returned by `GET /work-hours`), a table of pickers with the day's records, an
    hours input (`step 0.25`, empty = not entered) and a "Heures manquantes" badge, a "fill the empty rows with
    _x_ h" helper, and one "Enregistrer" button sending only changed rows. Validation errors from the `400` are shown
    in the page (no browser dialog).
  - `front/index.html`: the picker table becomes Cueilleur | Heures | one column per `stats.activities` |
    Total caisses | Non conformes | Taux de conformité. "Heures" is hidden when a reader is selected, with the hint
    "Choisir « Tous les lecteurs » pour voir les heures"; "non saisi" for a null `workHours` of a picker; "—" on
    "Non attribué". A notice under the table when `includesToday`. The CSV gets the same columns (hours with a
    decimal comma, as the rate already is).
  - Added during implementation: before the `PUT`, `work-hours.html` checks each changed value with the server's
    rule (a multiple of 0.25 between 0.25 and 24) and, on the first bad one, names the picker and focuses its field;
    nothing is sent. The server's `400` names the picker by id only, which means nothing to the Administrateur. The
    server stays the authority: every rule is still checked there (R3).
- **Rationale**: one page for the morning entry and the missing-hours check (User Stories 1 and 3); the dashboard
  change stays in the existing table (FR-007).
- **Alternatives considered**: hours entry inside `pickers.html` (one picker at a time, fails SC-001); a modal in
  the dashboard (Opérateurs use that page too).

## R10 — Tests

- **Decision**: `WorkHoursServiceTest` (validation, upsert, delete, future day, unknown picker, all-or-nothing,
  sorting, `missingHours`); `WorkHoursApiTest` (MockMvc, both routes, CSRF, author and date); `RecordStatsServiceTest`
  and `RecordStatsApiTest` extended (activity columns and order, null activity, hours sum over several days, hours
  without records, `workHours` null with a reader, `includesToday`, row sums = Total); `AccessMatrixSecurityTest`
  (Opérateur `403`, anonymous `401`) and `KioskReaderTokenSecurityTest` (`403`); `PickerServiceTest` (deletion
  removes hours). Fixed `Clock` bean for "today" and "future".
- **Rationale**: same layout as specs 007 and 012; every FR maps to at least one test.

## R11 — Performance (SC-003)

- **Decision**: no new index on `record`; the activity query uses the creation-date range like the three existing
  ones. `picker_work_day` gets the unique index `(picker_id, work_date)` and an index on `work_date` for the period
  sum. Checked with the spec 007 quickstart data set (200 000 records over 31 days, PostgreSQL), extended with one
  hours row per picker and day.
- **Rationale**: spec 007 measured 0.12 s for three aggregates; a fourth of the same shape and a sum over at most
  31 × (number of pickers) rows keep the dashboard well under 1 s.
