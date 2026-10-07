---

description: "Task list for feature 014 — heures de travail des cueilleurs et caisses par activité"
---

# Tasks: Heures de travail des cueilleurs et caisses par activité

**Input**: Design documents from `.specify/specs/014-heures-travail-cueilleurs/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-work-hours.md](contracts/openapi-work-hours.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan names the test classes to add or extend (research R10), and every earlier feature ships with its tests.

**Organization**: one phase per user story of the spec: US1 (P1) the Administrateur enters the pickers' hours, US2 (P1) the dashboard shows per picker the hours and the crates per activity, US3 (P2) the Administrateur spots the missing hours of a day.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to
- Paths are relative to the repository root

## Path Conventions

Single Spring Boot project: `src/main/java/com/rfidback/…`, `src/main/resources/…`, `src/test/java/com/rfidback/…`; static front in `front/`. Generated OpenAPI code lands in `target/generated-sources/openapi` and is never edited by hand: change `src/main/resources/openapi/api.yaml`, then run `mvn generate-sources`. Schemas with **required** properties get a required-args constructor from the generator. Integration tests use `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, `user("…").roles("…")` for sessions, `csrf()` for user writes and the `x-api-token` header for the kiosk, as in `RecordStatsApiTest` and `KioskReaderTokenSecurityTest`. Never rely on the `XSRF-TOKEN` cookie (see `CLAUDE.md`, Deployment). "Today" always comes from the `Clock` bean in the station zone (`StationProperties.timeZone()`), never from `LocalDate.now()`.

---

## Phase 1: Setup

**Purpose**: green starting point on the feature branch.

- [X] T001 Update the local `dev` from `origin/dev`, create branch `014-heures-travail-cueilleurs` from it, carry over the untracked `.specify/specs/014-heures-travail-cueilleurs/` folder, then run `mvn clean test` from the repository root and confirm all tests pass before any change. If it fails with `NoClassDefFoundError` on a bare class name or "Unresolved compilation problem", that's the VS Code Java extension racing Maven (see `CLAUDE.md`): rerun, don't fix code.

---

## Phase 2: Foundational

**Purpose**: contract, table, repository and security rule shared by every story. Blocks all stories.

- [X] T002 Edit `src/main/resources/openapi/api.yaml` exactly as in [contracts/openapi-work-hours.md](contracts/openapi-work-hours.md):
  - add a "Work hours section (spec 014)" comment block with the path `/work-hours` (`get` → `getWorkDay`, `put` → `saveWorkDay`, tag `WorkHours`) right after the Picker section's last path;
  - add the schemas `WorkDay`, `WorkDayPicker`, `SaveWorkDayRequest`, `WorkHoursEntry`, `ActivityColumn`, `ActivityCount` after `RecordStats`;
  - extend `PickerStats` (`activities` required, `workHours` nullable) and `RecordStats` (`activities` and `includesToday` required, `workHours` nullable, new `pickers` description) and append the sentence of the contract to the `/records/stats` description.
  Run `mvn generate-sources` and check that `WorkHoursApiDelegate` exists with `getWorkDay(LocalDate day)` and `saveWorkDay(SaveWorkDayRequest)`, and that `PickerStats`/`RecordStats` have the new getters. Then run `mvn clean test`: `RecordStatsServiceTest`/`RecordStatsApiTest` may now fail only on the new required fields being null — set `stats.setActivities(List.of())`, `picker.setActivities(List.of())` and `stats.setIncludesToday(false)` in `src/main/java/com/rfidback/service/RecordStatsService.java` as a placeholder so the build stays green until US2.
- [X] T003 [P] Create `src/main/java/com/rfidback/entity/PickerWorkDayEntity.java` (table `picker_work_day`, Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` like `PickerEntity`), per [data-model.md](data-model.md): `UUID id` (`GenerationType.UUID`); `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "picker_id") PickerEntity picker`; `@Column(name = "work_date", nullable = false) LocalDate workDate`; `@Column(nullable = false) int minutes`; `@Column(name = "updated_at", nullable = false) OffsetDateTime updatedAt` (set by the service from the `Clock`, no `@UpdateTimestamp`); `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "updated_by_id") UserEntity updatedBy`. `@Table(uniqueConstraints = @UniqueConstraint(name = "uk_picker_work_day_picker_date", columnNames = { "picker_id", "work_date" }), indexes = @Index(name = "idx_picker_work_day_date", columnList = "work_date"))`. Constants `MINUTES_STEP = 15`, `MAX_MINUTES = 1440`; a `@PrePersist @PreUpdate` guard throwing `IllegalStateException` unless `MINUTES_STEP <= minutes <= MAX_MINUTES` and `minutes % MINUTES_STEP == 0`. Class comment: the hours a picker worked on one station day, entered by an Administrateur (spec 014); no row means "non saisi".
- [X] T004 Create `src/main/java/com/rfidback/repository/PickerWorkDayRepository.java` (`JpaRepository<PickerWorkDayEntity, UUID>`), depends on T003:
  - `@EntityGraph(attributePaths = { "picker", "updatedBy" }) List<PickerWorkDayEntity> findAllByWorkDate(LocalDate workDate)`;
  - `List<PickerWorkDayEntity> findAllByWorkDateAndPickerIn(LocalDate workDate, Collection<PickerEntity> pickers)` (upsert lookup in one query);
  - `@Query("select p.id as pickerId, p.firstname as firstname, p.lastname as lastname, sum(w.minutes) as minutes from PickerWorkDayEntity w join w.picker p where w.workDate between :first and :last group by p.id, p.firstname, p.lastname") List<PickerMinutesView> sumMinutesByPicker(@Param("first") LocalDate first, @Param("last") LocalDate last)` with a nested `interface PickerMinutesView { UUID getPickerId(); String getFirstname(); String getLastname(); Number getMinutes(); }` (Number, as the comment in `RecordRepository` explains);
  - `@Modifying @Query("delete from PickerWorkDayEntity w where w.picker = :picker") int deleteByPicker(@Param("picker") PickerEntity picker)`.
- [X] T005 [P] In `src/main/java/com/rfidback/configuration/SecurityConfig.java`, user chain (`@Order(3)`), add `.requestMatchers(path(null, "/api/work-hours")).hasRole(ADMINISTRATEUR)` next to the Picker rows (research R8). The kiosk chain stays unchanged: its `denyAll()` answers `403` to a reader token.
- [X] T006 In `src/main/java/com/rfidback/service/PickerService.java`, inject `PickerWorkDayRepository`; in `deletePicker`, after the bucket check, call `pickerWorkDayRepository.deleteByPicker(entity)` before `pickerRepository.delete(entity)`, and make the method `@Transactional` (research R7). Depends on T004. Update the `PickerService` constructor wiring in `src/test/java/com/rfidback/service/PickerServiceTest.java` if it builds the service by hand, and add a test: deleting a picker without bucket calls `deleteByPicker` then `delete` (Mockito `InOrder`).

**Checkpoint**: `mvn clean test` green; the table exists at boot; the routes answer `403` for Opérateurs and `500`/`501` for the Administrateur until US1.

---

## Phase 3: User Story 1 — Saisir les heures de travail des cueilleurs (Priority: P1) 🎯 MVP

**Goal**: the Administrateur enters, corrects and deletes the hours of the whole team for a day in one save (FR-001–FR-006).

**Independent Test**: as `admin`, `PUT /api/work-hours` `{ day: today, entries: [{ pickerId: A, hours: 7.5 }] }` then `GET /api/work-hours?day=today` → A has `7.5`, `updatedBy = "admin"`; `PUT` again with `8` → `8`; with `null` → no hours; as Opérateur → `403`.

### Tests for User Story 1

- [X] T007 [P] [US1] Create `src/test/java/com/rfidback/service/WorkHoursServiceTest.java` (Mockito, fixed `Clock` at `2026-10-07T08:00:00Z`, zone `Europe/Paris`), covering: hours `7.25` stored as `435` minutes; a second save of the same picker/day updates the existing row (no new row); `hours = null` deletes the row; `0`, `-1`, `24.25`, `7.3` → `ResponseStatusException` 400 and **no** `save`/`delete` called for any entry (all or nothing, entries before the bad one included); duplicated `pickerId` → 400; unknown picker → `PickerNotFoundException`, nothing written; `day = 2026-10-08` → 400 for both `getWorkDay` and `saveWorkDay`; `day = null` on `getWorkDay` resolves to `2026-10-07` and `today = true`; `updatedAt` = clock instant and `updatedBy` = current user on written rows; concurrent insert: with a real `TransactionTemplate` over a mocked `PlatformTransactionManager`, `saveAllAndFlush` throwing `DataIntegrityViolationException` once then succeeding → the day is returned (second attempt re-reads the rows), throwing twice → 409; the day view lists every picker in French order (`Émile` between `Durand` and `Martin` by last name), with `hours = null` for pickers without a row.
- [X] T008 [P] [US1] Create `src/test/java/com/rfidback/controller/WorkHoursApiTest.java` (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, real `admin` and `operateur` users in `UserRepository` as in `RecordStatsApiTest`), covering: Administrateur `PUT` with `csrf()` then `GET` round trip (`hours`, `updatedBy`, `updatedAt` present); `PUT` without `csrf()` → 403; invalid hours → 400 and a following `GET` shows nothing saved; unknown picker → 404; future day → 400; empty `entries` → 400.
- [X] T009 [P] [US1] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java`, add rows for `GET /api/work-hours` and `PUT /api/work-hours`: anonymous 401, Opérateur 403, Administrateur not 401/403. In `src/test/java/com/rfidback/security/KioskReaderTokenSecurityTest.java`, add: a valid reader token on `GET /api/work-hours` → 403.

### Implementation for User Story 1

- [X] T010 [US1] Create `src/main/java/com/rfidback/service/WorkHoursService.java` (`@Service @RequiredArgsConstructor`), injecting `PickerWorkDayRepository`, `PickerRepository`, `UserRepository`, `StationProperties`, `Clock` (research R2–R4):
  - `@Transactional(readOnly = true) WorkDay getWorkDay(LocalDate day)`: resolve `day` (null → today in the station zone; after today → 400 `ResponseStatusException` "The day must not be in the future"); load `pickerRepository.findAll()` and `findAllByWorkDate(day)`; one `WorkDayPicker` per picker with `hours = minutes / 60.0` or null, `updatedAt`, `updatedBy` = username; `records = 0` and `missingHours = false` for now (filled in US3); sort with the same French `Collator` comparator as `RecordStatsService.toPickerStats` (last name, first name, id); `today` = `day.equals(today)`.
  - `WorkDay saveWorkDay(SaveWorkDayRequest request)`, **not** `@Transactional` (a `DataIntegrityViolationException` inside a transaction marks it rollback-only, so a retry in the same outer transaction would still end in `UnexpectedRollbackException`, research R3): resolve and check the day as above; validate **every** entry before writing anything: `pickerId` not null and not repeated (400), `hours` null or `hours * 60` a whole number of minutes that is a multiple of `PickerWorkDayEntity.MINUTES_STEP` within `[15, 1440]` (400, message naming the picker id and the rule); load the pickers with `pickerRepository.findAllById` and throw `PickerNotFoundException` for the first missing id; load existing rows with `findAllByWorkDateAndPickerIn`; then run the write in `transactionTemplate.execute(...)` (a `TransactionTemplate` built from the injected `PlatformTransactionManager`): load the existing rows with `findAllByWorkDateAndPickerIn`, and for each entry update the row (minutes, `updatedAt = OffsetDateTime.now(clock)`, `updatedBy = currentUser()`), create it, or delete it when `hours` is null and a row exists, then `saveAllAndFlush`. If `execute` throws `DataIntegrityViolationException` (concurrent insert of the same picker/day), its transaction is already rolled back: call `execute` once more with the same write (it now finds the other request's rows and updates them); if it throws again, answer 409 (`ResponseStatusException(HttpStatus.CONFLICT, …)`). Validation and picker lookup stay outside the template, so a 400/404 never opens a write. Return `getWorkDay(day)`.
  - `currentUser()` as in `LineActivityService.currentUser()` (username from the `SecurityContextHolder`, `UserRepository.findByUsername`).
- [X] T011 [US1] Create `src/main/java/com/rfidback/controller/WorkHoursController.java` implementing `WorkHoursApiDelegate` (pattern of `ActivityController`): `getWorkDay(day)` → `ResponseEntity.ok(workHoursService.getWorkDay(day))`; `saveWorkDay(request)` → `ResponseEntity.ok(workHoursService.saveWorkDay(request))`. Depends on T010.
- [X] T012 [US1] Create `front/work-hours.html` ("Heures", Administrateur only: `requireRole('ADMINISTRATEUR')` and navbar copied from `front/activities.html` with the new link active), using `apiFetch` from `front/auth.js` (research R9):
  - a date input (max = the day returned by the first `GET /api/work-hours`, which is today in the station zone) reloading `GET /api/work-hours?day=…` on change;
  - a table Cueilleur | Heures (an `<input type="number" min="0.25" max="24" step="0.25">`, empty = non saisi) | Dernière modification (`updatedBy`, `updatedAt` in fr-FR);
  - a "Remplir les cases vides avec [x] h" helper filling only empty inputs;
  - an "Enregistrer" button sending `PUT /api/work-hours` with only the rows whose value changed (empty → `hours: null`), disabled when nothing changed; on 200 re-render from the response and show a success message; on 400/404/409 show the problem's `detail` in an alert box in the page (never `alert()`), keeping the typed values;
  - a warning before leaving the page or changing the day with unsaved changes, shown in the page (not `confirm()`).
- [X] T013 [P] [US1] Add the navbar link `<a href="work-hours.html" data-admin-only class="btn btn-sm btn-outline-light border-0"><i class="fas fa-clock me-1"></i>Heures</a>` right after the "Cueilleurs" link in `front/index.html`, `front/pickers.html`, `front/readers.html`, `front/activities.html`, `front/tags.html`, `front/buckets.html` and `front/users.html`.

**Checkpoint**: User Story 1 works on its own: quickstart section 3; T007–T009 green.

---

## Phase 4: User Story 2 — Voir, par cueilleur, ses heures et ses caisses par activité (Priority: P1)

**Goal**: the dashboard's picker table shows hours, one column per activity and the total of crates, in the page and the CSV (FR-007–FR-013).

**Independent Test**: one picker with 7.5 h and 30 records (20 Fraise, 10 Framboise) today → `GET /api/records/stats` returns `activities = [Fraise, Framboise]`, the picker row with `workHours = 7.5`, `activities = [{Fraise, 20}, {Framboise, 10}]`, `total = 30`, `workHours = 7.5` at the top and `includesToday = true`.

### Tests for User Story 2

- [X] T014 [P] [US2] Extend `src/test/java/com/rfidback/service/RecordStatsServiceTest.java` (mocked repositories): columns sorted by name in French order with the null column last and only when a row has a null activity; per-picker `activities` built from the picker × activity rows and adding up to `total`; hours `minutes` converted to hours and summed (`240 + 480` minutes over two days → `12.0`); a picker with hours and no record added with zero counts, `activities = []`, in sorted position; "Non attribué" row with `workHours = null`; `workHours` at the top = sum of the rows; with a reader: no hours query called, every `workHours` null, no hours-only row; `includesToday` true for TODAY and LAST_7_DAYS, false for YESTERDAY and a past CUSTOM range.
- [X] T015 [P] [US2] Extend `src/test/java/com/rfidback/controller/RecordStatsApiTest.java` (real H2 data): records with two activities and without activity for two pickers plus one record without picker, hours for one picker on two days and for a third picker with no record → check the JSON columns, rows, sums (each column's rows add up, `sum(total) = summary.total`, `sum(workHours) = workHours`) and the reader-filtered response (`workHours` null, activity columns limited to the reader); the same request as an Opérateur returns the same `workHours` values (FR-006: consultation stays open).

### Implementation for User Story 2

- [X] T016 [US2] In `src/main/java/com/rfidback/repository/RecordRepository.java`, statistics block, add (research R6): `String BY_PICKER_ACTIVITY = "select p.id as pickerId, a.id as activityId, a.name as activityName, count(r) as total from RecordEntity r left join r.picker p left join r.activity a where" + IN_RANGE;` and `String PICKER_ACTIVITY_GROUP = " group by p.id, a.id, a.name";`; queries `countByPickerAndActivity(start, end)` and `countByPickerAndActivityForReader(start, end, reader)` (same split as the existing ones, with the `and r.reader = :reader` clause); nested `interface PickerActivityCountsView { UUID getPickerId(); UUID getActivityId(); String getActivityName(); Number getTotal(); }`.
- [X] T017 [US2] In `src/main/java/com/rfidback/service/RecordStatsService.java` (research R5, R6), replacing the T002 placeholders; inject `PickerWorkDayRepository`:
  - run the picker × activity query (with or without reader); build `stats.activities` from the distinct `(activityId, activityName)` pairs: non-null sorted by name with the French `Collator` then id, the null pair last if present;
  - group the activity rows by `pickerId` (null key for "Non attribué") into `List<ActivityCount>` in column order, set on each `PickerStats` (empty list when none);
  - without a reader: `sumMinutesByPicker(first, last)`, set `workHours = minutes / 60.0` on matching picker rows, add a `PickerStats` with `total = 0`, `nonCompliant = 0`, `activities = []` (names from the hours view) for each picker with hours and no row; then sort the **final `List<PickerStats>`** — change `toPickerStats` to map the rows without sorting, and sort afterwards with a comparator on `PickerStats`: null `pickerId` last, then `lastname`, `firstname` with the French `Collator` (`Comparator.nullsLast`), then `String.valueOf(pickerId)` — the same order as before for the existing rows; `stats.workHours` = sum of the minutes / 60.0, or `0.0` when no hours; with a reader: leave every `workHours` null and do not call the hours query;
  - `stats.includesToday` = `!today.isBefore(first) && !today.isAfter(last)` with `today` from the clock in the station zone;
  - update the class comment (spec 014: hours and crates per activity).
  Depends on T016.
- [X] T018 [US2] In `front/index.html`, picker table (research R9, FR-007–FR-011):
  - build the `<thead>` from the response: Cueilleur | Heures | one `<th class="num">` per `stats.activities` (name, or "Sans activité" for the null entry) | Total caisses | Non conformes | Taux de conformité; hide the Heures column when `stats.readerId` is set and show under the table "Choisir « Tous les lecteurs » pour voir les heures";
  - `pickerRow` gets the hours cell (`workHours` formatted with `toLocaleString('fr-FR', { minimumFractionDigits: 0, maximumFractionDigits: 2 })` + " h", so `7,25 h`, `7,5 h`, `40 h`; "non saisi" in muted text when null on a picker row; "—" on the "Non attribué" row) and one count per column (the matching `activities` entry's `total`, else 0), then the existing cells; the Total row uses `stats.workHours` and the column sums computed from the rows;
  - when `stats.includesToday`, show under the table: "Journée en cours : les heures couvrent toute la journée, les caisses s'ajoutent jusqu'au soir.";
  - rename the "Lectures" header of this table to "Total caisses" (the summary tile keeps its wording).
- [X] T019 [US2] In `front/index.html`, `exportCsv`/`csvCounts`: header `Cueilleur; Heures; <one per activity>; Total caisses; Conformes; Non conformes; Taux de conformité (%)` (Heures omitted when `readerId` is set); hours with a decimal comma (`toLocaleString('fr-FR', { maximumFractionDigits: 2, useGrouping: false })`), empty when null; activity counts as in the page; Total line with the same values as the page's Total row. Depends on T018.

**Checkpoint**: User Stories 1 and 2 work together: quickstart section 4; T014–T015 green.

---

## Phase 5: User Story 3 — Repérer les heures manquantes (Priority: P2)

**Goal**: the day view gives each picker's records of the day and flags those with records but no hours (FR-014, SC-004).

**Independent Test**: a day with records for 3 pickers and hours for 1 → `GET /api/work-hours?day=…` returns `missingHours = true` for the 2 others, with their `records` counts; a picker with hours and no record has `records = 0`, `missingHours = false`.

### Tests for User Story 3

- [X] T020 [P] [US3] Extend `src/test/java/com/rfidback/service/WorkHoursServiceTest.java`: `records` taken from `RecordRepository.countByPicker` over the day's instant range in the station zone (assert the `start`/`end` passed: `2026-10-06T00:00+02:00` to `2026-10-07T00:00+02:00`), the null-picker row ignored; `missingHours = records > 0 && hours == null` for the four combinations.
- [X] T021 [P] [US3] Extend `src/test/java/com/rfidback/controller/WorkHoursApiTest.java`: three pickers with records on a past day, hours for one → two `missingHours = true`; a record at 23:30 Paris the day before is not counted.

### Implementation for User Story 3

- [X] T022 [US3] In `src/main/java/com/rfidback/service/WorkHoursService.java`, inject `RecordRepository`; in `getWorkDay`, compute the day's range `day.atStartOfDay(zone)` → `day.plusDays(1).atStartOfDay(zone)` (as `RecordStatsService`), call `recordRepository.countByPicker(start, end)`, map `pickerId → total` (skipping the null picker), and set `records` and `missingHours` on each `WorkDayPicker`.
- [X] T023 [US3] In `front/work-hours.html`: add a "Caisses du jour" column (`records`) and a red "Heures manquantes" badge on rows with `missingHours`; above the table, a summary "N cueilleurs avec des caisses sans heures" (hidden when 0) and a "Afficher seulement les heures manquantes" toggle filtering the rows.

**Checkpoint**: all stories work: quickstart section 5; T020–T021 green.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T024 [P] Update `CLAUDE.md`: in **Project**, add the domain term "**Work hours** (heures de travail) — hours a picker worked on a station day, entered by the Administrateur (spec 014)"; in **Persistence**, one sentence: `picker_work_day` holds them (one row per picker and day, whole minutes), summed by `GET /api/records/stats` with the crates per activity; deleting a picker deletes their hours.
- [X] T025 [P] Mark the spec delivered: in `.specify/specs/014-heures-travail-cueilleurs/spec.md` set **Status** to "Delivered (<date>)"; in `.specify/specs/012-activites-lignes/spec.md`, next to the clarification deferring the dashboard by activity, add "Livré en partie par la spec `014` : colonnes par activité dans le tableau par cueilleur ; le filtre par activité reste à faire."
- [X] T026 Run `mvn clean install` from the repository root; all tests green.
- [X] T027 Run [quickstart.md](quickstart.md) sections 2–6 by hand against `mvn spring-boot:run` and fix any gap; then section 7 (PostgreSQL, 200 000 records over 31 days with activities and hours) and record the measured times of `GET /api/records/stats` with and without `readerId` under SC-003 in `spec.md` (target: under 1 s).

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)** → **Foundational (Phase 2)** → user stories.
- **US1 (Phase 3)** depends on Phase 2 only.
- **US2 (Phase 4)** depends on Phase 2 only for the backend (T016–T017 read `picker_work_day`); its manual check needs hours, entered through US1 or directly in the tests' data.
- **US3 (Phase 5)** depends on US1 (extends `WorkHoursService` and `work-hours.html`).
- **Polish (Phase 6)** after the stories to deliver.

### Within phases

- T003 → T004 → T006; T002 before any code using generated types (T010, T011, T017).
- T010 → T011 → T012; T016 → T017; T018 → T019; T022 → T023.
- Tests (T007–T009, T014–T015, T020–T021) are written first and fail until the implementation of their story lands.

### Parallel opportunities

- Phase 2: T003 and T005 together, after T002.
- US1: T007, T008, T009 together; T013 alongside T010–T012.
- US2: T014 and T015 together; US2 backend (T016–T017) can run in parallel with US1 once Phase 2 is done (different files, except `RecordStatsService`, which US1 does not touch).
- US3: T020 and T021 together.
- Polish: T024 and T025 together.

## Parallel Example: User Story 1

```text
Task: "T007 [US1] WorkHoursServiceTest in src/test/java/com/rfidback/service/WorkHoursServiceTest.java"
Task: "T008 [US1] WorkHoursApiTest in src/test/java/com/rfidback/controller/WorkHoursApiTest.java"
Task: "T009 [US1] access rows in src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java and KioskReaderTokenSecurityTest.java"
Task: "T013 [US1] navbar link Heures in front/*.html"
```

## Parallel Example: User Story 2

```text
Task: "T014 [US2] RecordStatsServiceTest in src/test/java/com/rfidback/service/RecordStatsServiceTest.java"
Task: "T015 [US2] RecordStatsApiTest in src/test/java/com/rfidback/controller/RecordStatsApiTest.java"
```

## Implementation Strategy

### MVP first

1. Phases 1 and 2.
2. Phase 3 (US1): hours can be entered and corrected. Stop and validate (quickstart section 3).
3. Phase 4 (US2): the result the user asked for, the dashboard table. Validate (quickstart section 4). US1 + US2 are the first useful delivery, since hours alone show nothing on the dashboard.

### Incremental delivery

- Deliver US1 + US2 together (one PR to `dev`), then US3 (missing hours) in the same PR or a follow-up.
- `GET /records/stats` changes are additive, so the deployed dashboard keeps working at every step.

## Phase 7: Convergence

- [ ] T028 Time the morning entry of 30 pickers on `front/work-hours.html` (quickstart section 3 step 5: "Remplir les cases vides" plus a few corrections, then "Enregistrer") with a person at the keyboard, and record the measured time under SC-001 in `.specify/specs/014-heures-travail-cueilleurs/spec.md` (target: under 5 minutes) per SC-001 (partial)
- [X] T029 In a browser on `front/work-hours.html`, type a value then change the day: check the in-page warning appears (no browser dialog), "Rester" keeps the typed value and day, "Abandonner et changer de jour" loads the new day; then check leaving the page with an unsaved value triggers the browser's leave-page prompt, per plan: research R9 (partial)
- [X] T030 Document the client-side hours check of `front/work-hours.html` (`invalidEdit()`: names the picker and focuses its field before the PUT; the server stays the authority) in `.specify/specs/014-heures-travail-cueilleurs/research.md` R9, or remove it, per plan: research R9 (unrequested)
