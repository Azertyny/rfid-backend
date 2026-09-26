# Research: Activités des lignes de production

Decisions taken while planning spec `012`. Code references are to branch `012-activites-lignes` at `44e0fdf`.

## R1 — Where the current activity lives

- **Decision**: two nullable columns on `reader`: `current_activity_id` (FK to `activity`) and
  `current_activity_set_at` (instant of the choice). There is no separate "line" table: a line is a reader in
  `PRODUCTION` mode (spec Assumptions).
- **Rationale**: at most one current activity per line (FR-008) is a column, not a relation. Keeping the instant of the
  choice lets any code path tell whether the choice belongs to today (R3) without a separate state table.
- **Alternatives considered**: a `line_activity_state` table (one row per reader, same content, one more join on each
  scan); deriving the current activity from the latest change-log row (a query per scan, and the log would become
  the source of truth for scans).

## R2 — Stamping the activity on a scan

- **Decision**: `record.activity_id`, nullable FK to `activity`. `TagService.registerScan` reads the reader's current
  activity **from the database, inside the scan transaction** (`ReaderRepository.findCurrentActivity(readerId)`, a
  projection of the two columns plus the activity), applies the "is it today" rule (R3), and sets it on the new
  `RecordEntity`. The duplicate path (`ignoreDuplicate`) does not touch `activity` (FR-015).
- **Rationale**: the `ReaderEntity` in the `ReaderAuthentication` principal is loaded by
  `ReaderApiTokenAuthenticationFilter` at the start of the request and is detached: reading a lazy
  `currentActivity` on it would fail, and its value could be stale. The scan does not lock the reader row: it sees
  either the value before or after a concurrent change, never a mix (edge case "choix au moment d'un scan"). No
  change to the scan request or response (FR-016).
- **Alternatives considered**: storing the activity name on the record (a rename would not show on past records,
  contrary to User Story 1 scenario 4); locking the reader row on every scan (serialises the line for no benefit).

## R3 — Daily reset at midnight, station time

- **Decision**: three parts, all using the injected `Clock` and `StationProperties.timeZone()` (spec `007`):
  1. **Rule applied on read** (`LineActivityService.effectiveActivity`): a current activity whose
     `current_activity_set_at`, converted to the station zone, is not today's date is treated as "no activity". The
     scan (R2), the kiosk read and the choice all go through this rule, so a choice from a previous day is never
     stamped on a record of today, even between midnight and the job below, or if the server was down at midnight
     (FR-008a).
  2. **Scheduled job** `ActivityDailyReset`: `@Scheduled(cron = "0 0 0 * * *", zone = "${app.station.time-zone}")`,
     plus a run on `ApplicationReadyEvent` (catch-up after a stop across midnight). Both call the transactional
     `LineActivityService.resetStaleActivities()` in another bean: the row locks need a transaction, and a call between
     two methods of the same bean would bypass Spring's transaction proxy (analysis C1). It clears every stale current
     activity (one bulk read of readers whose `current_activity_set_at` is before today's start of day in the
     station zone) and writes one `line_activity_change` per line, author `SYSTEM`, `changed_at` = the midnight
     that follows the choice (the moment the reset was due, not the moment the job ran).
  3. `@EnableScheduling` on a configuration class (`ClockConfig`, which already groups the time settings).
- **Rationale**: the rule on read gives the guarantee; the job gives the trace (FR-012, SC-005) and makes the database
  state match what users see. `changed_at` set to the due midnight keeps the history truthful after a downtime.
  Europe/Paris midnight always exists (DST changes happen at 02:00/03:00), and `StationProperties` already refuses
  zones with non-whole-hour offsets.
- **Alternatives considered**: job only (a scan between 00:00:00 and the job's commit, or after a downtime, would carry
  yesterday's activity — contradicts FR-008a); rule only (no trace of the reset); `ShedLock`/distributed locking (one
  instance in production, `deploy/INSTALL.md`).

## R4 — Activity names

- **Decision**: `activity.name` (≤ 100, trimmed, displayed) plus `activity.name_key` = `name.trim().toLowerCase(Locale.ROOT)`,
  **unique**. Create and rename check `existsByNameKey` then rely on the unique constraint for the race, like
  `ReaderService.createReader` (`DataIntegrityViolationException` → `409`).
- **Rationale**: FR-001 wants uniqueness ignoring case and surrounding spaces, portable across H2 and PostgreSQL
  without a functional index (`ddl-auto` cannot create one).
- **Alternatives considered**: `lower(name)` functional unique index (needs a startup upgrade class like
  `ConformityAuthorSchemaUpgrade`; not worth it); PostgreSQL `citext` (not in H2).

## R5 — Associations

- **Decision**: join table `reader_activity (reader_id, activity_id)`, primary key on both, mapped as
  `@ManyToMany Set<ReaderEntity> lines` on `ActivityEntity` (owning side). Replaced per line with
  `PUT /readers/{readerId}/activities` (the Administrateur ticks the activities of a line, User Story 1). A reader in
  `ENREGISTREMENT` mode is refused with `400` (FR-006). A disabled or deactivated activity may stay associated
  (it is shown as disabled and cannot be chosen, FR-011).
- **Rationale**: one call per line matches the screen (a line × activity grid, one row saved at a time); the owning
  side on `ActivityEntity` keeps `ReaderEntity` unchanged apart from R1's columns.
- **Alternatives considered**: `POST/DELETE /activities/{id}/readers/{readerId}` (one call per tick, more routes, same
  rules); an association entity with its own id (no extra attribute to carry).

## R6 — Confirmation before clearing a current activity (FR-007)

- **Decision**: the two operations that can clear a line's current activity take `confirmed: boolean` (default
  `false`): `PUT /readers/{readerId}/activities` (removing the current one) and `PATCH /activities/{activityId}`
  (`active: false` while current on lines). Without confirmation they answer `409` with
  `LinesLosingActivity { message, readerUids[] }` and change nothing; with it they clear the current activity of
  those lines and log one change per line with the Administrateur as author.
- **Rationale**: same pattern as `moveConfirmed` / `TagsInOtherBuckets` (spec `003`): the server names the lines, so
  the page never works from a stale list.
- **Alternatives considered**: the page checks beforehand from `GET /activities` (race with a kiosk choosing that
  activity meanwhile); refusing outright (forces two steps for a routine end-of-season change).

## R7 — Deleting an activity

- **Decision**: `DELETE /activities/{activityId}` → `409` if a record **or a change-log row** references it; otherwise
  delete its associations and the activity, `204`. Spec FR-003 is refined accordingly (see Spec changes below).
- **Rationale**: the change log (FR-012) keeps foreign keys to the previous/new activity; deleting an activity that
  was ever chosen would break that history. An activity that was never chosen has no record and no log row, which
  covers the real use (a typo right after creation). The `409` message invites to deactivate.
- **Alternatives considered**: storing names instead of FKs in the log (a rename would not show there, unlike on
  records); `ON DELETE SET NULL` (history loses the activity; `ddl-auto` cannot declare it anyway).

## R8 — Change log

- **Decision**: table `line_activity_change`: `reader_id`, `previous_activity_id` (nullable), `new_activity_id`
  (nullable), `changed_at` (set by the code, not `@CreationTimestamp`, for R3), `author_type`
  (`USER` / `READER` / `SYSTEM`), `author_user_id` (nullable), `author_reader_id` (nullable). A `@PrePersist` check
  enforces the author matching `author_type`, like `RecordConformityChangeEntity.checkSingleAuthor`. Index
  `(reader_id, changed_at)`. A change is written only when the value actually changes (choosing the current activity
  again writes nothing, like conformity). No read route in this feature: the log answers SC-005 from the database;
  a history screen can come with the dashboard work (spec Assumptions, out of scope).
- **Rationale**: FR-012, SC-005; `author_type` is explicit because `SYSTEM` has no FK.
- **Alternatives considered**: reuse `record_conformity_change` (different subject); a `GET` history route now (no
  screen needs it in this spec).

## R9 — Choosing the current activity: route, security, concurrency

- **Decision**: `GET` and `PUT /lines/{readerUid}/current-activity`, OpenAPI tag `Activity`. The path uses the reader
  **uid** (like `/records/readers/{readerId}`, which the kiosk already calls with `CURRENT_READER.uid`), under a new
  `/lines` prefix. `PUT` body `{ activityId: uuid | null }`.
  - Kiosk chain (`@Order(2)`): add `GET` and `PUT /api/lines/*/current-activity` as `authenticated()`;
    `LineActivityService` refuses another reader's uid with `403` (FR-009), reusing the `currentReader()` pattern of
    `RecordService`.
  - User chain: `/api/lines/*/current-activity` → Opérateur or Administrateur (FR-010); CSRF applies as for any write.
  - `PUT` locks the reader row (`ReaderRepository.findWithLockById`, `PESSIMISTIC_WRITE`) so a kiosk choice, an
    Opérateur choice, an Administrateur dissociation/deactivation and the daily job serialise; last write wins, each
    change logged (edge case "deux choix simultanés").
  - Refusals: unknown uid `404`; reader in `ENREGISTREMENT` mode `400`; activity unknown, disabled or not associated
    `400` (FR-011).
- **Rationale**: `/readers/**` is Administrateur-only in the user chain and uses the reader UUID; a separate `/lines`
  prefix keeps that rule intact and names the concept the Opérateur sees.
- **Alternatives considered**: `/readers/{readerId}/current-activity` (needs a more specific rule before
  `/api/readers/**` and the UUID, which the kiosk does not keep); `PATCH /readers/{readerId}` (Administrateur-only).

## R10 — Kiosk read model and banner count (FR-009a)

- **Decision**: `GET /lines/{readerUid}/current-activity` returns `LineActivity`: `readerUid`, `currentActivity`
  (`ActivityRef { id, name }` or null, after R3's rule), `setAt`, `availableActivities` (associated **and** active,
  sorted by name), `recordsWithoutActivityToday` (count of the reader's records since today's midnight, station time,
  with `activity` null). `PUT` returns the same body. The count uses the existing `idx_record_reader_date` index.
- **Rationale**: one call gives the kiosk everything for the header, the chooser and the banner; the kiosk polls it
  every 5 s (much slower than the 500 ms records poll) and right after a choice.
- **Alternatives considered**: separate count route (two polls); counting in the browser from the last 10 records
  (wrong beyond 10).

## R11 — Records seen by the kiosk and users (FR-017)

- **Decision**: `RecordSummary` gains `activityId` and `activityName` (both nullable). `findTop10ByReader_Name…` gets
  `activity` in its `@EntityGraph` next to `tag`, so the list stays one query (spec `005`, SC-003).
- **Rationale**: FR-017 with no extra round-trip. Adding optional fields is backward compatible for `reader.html`.

## R12 — Mode change and disabled readers

- **Decision**: `ReaderService.updateReader` switching a reader to `ENREGISTREMENT` clears its current activity via
  `LineActivityService.clear(reader, author = current user)` with a change-log row (FR-013); associations are kept.
  Disabling a reader keeps both (edge case).
- **Rationale**: same place that already closes the registration session on a mode change.

## R13 — Front

- **Decision**:
  - New page `front/activities.html` (Administrateur, `data-admin-only` link added to every page's navbar):
    activities table (create, rename, deactivate/reactivate, delete) and a line × activity grid for `PRODUCTION`
    readers; a `409 LinesLosingActivity` answer opens a Bootstrap confirmation modal naming the lines, then resends
    with `confirmed: true`. No `window.confirm` (kiosk and Chrome automation guidance).
  - `front/reader.html`: the header shows the current activity with a large "Changer" button opening a touch-friendly
    list of `availableActivities` plus "Aucune activité" (2 gestures, SC-001); a warning banner at the top while
    `currentActivity` is null, showing `recordsWithoutActivityToday`, and the "no activity available" variant when
    `availableActivities` is empty (FR-009a); each record box shows its activity name or "Sans activité".
- **Rationale**: follows the existing static pages (Bootstrap, `apiFetch`); kiosk mode already sends the token.

## R14 — Tests

- **Decision**: on the `test` profile:
  - `ActivityServiceTest`, `LineActivityServiceTest` (Mockito; fixed `Clock` for the midnight rule and the job's
    `changed_at`).
  - `ActivityApiTest` (CRUD, name rules, `409` confirmation flows, delete refusal), `LineActivityApiTest` (choice,
    refusals, kiosk own-line only, banner count), `ActivityDailyResetTest` (fixed clock before/after midnight,
    catch-up after "downtime").
  - `TagScanApiTest` extended: record carries the activity, none, stale-day choice ignored, duplicate keeps original;
    existing scan tests unchanged (SC-003).
  - `AccessMatrixSecurityTest` and `KioskReaderTokenSecurityTest` gain the new routes (SC-004).

## Spec changes made during planning

- FR-003 refined (R7): an activity can be deleted only if no record **and no change-log entry** references it, i.e.
  it was never chosen as a line's current activity.
