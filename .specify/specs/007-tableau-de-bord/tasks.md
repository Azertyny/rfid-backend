---

description: "Task list for 007 — Tableau de bord"
---

# Tasks: Tableau de bord (index.html)

**Input**: Design documents from `.specify/specs/007-tableau-de-bord/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-dashboard.md](contracts/openapi-dashboard.md), [quickstart.md](quickstart.md)

**Tests**: Included, because the spec requires them: SC-001 asks for exact totals of `GET /records/stats` checked by backend tests. The endpoint is new, so write the tests first and check that they fail (compilation or `404`) before implementing. The front is checked by hand ([quickstart.md](quickstart.md)); no JS test tooling is added (research R11).

**Organization**: the spec has a single user story (US1 — Visualiser l'activité, target scenario of the 2026-09-25 clarifications). It is split into a backend part (the endpoint, usable and testable on its own with `curl`) and a front part (the page), both labelled `[US1]`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: User story from spec.md (US1)
- Paths are relative to the repository root. Java sources: `src/main/java/com/rfidback/`; tests: `src/test/java/com/rfidback/`; front: `front/`.

## Conventions used by every task

- API-first: contract changes go into `src/main/resources/openapi/api.yaml` first, then `mvn generate-sources`. Copy the YAML from [contracts/openapi-dashboard.md](contracts/openapi-dashboard.md) as is.
- **No change** to `service/RecordService.java`, `service/TagService.java` or `configuration/SecurityConfig.java`: `/api/records/**` already allows Administrateur and Opérateur (`SecurityConfig.java:128`).
- `400` errors: throw `new ResponseStatusException(HttpStatus.BAD_REQUEST, "<English message>")`, as in `service/PickerService.java:202`. Unknown reader: `ReaderNotFoundException` (`404`), as in `RecordService.listLatestRecordsForReader`.
- Service unit tests: plain Mockito, no Spring context; mocks created in `@BeforeEach`, service built with its constructor, fixed clock `Clock.fixed(Instant.parse(...), ZoneOffset.UTC)` as in `service/TagServiceTest.java:60`.
- HTTP tests: `@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Transactional`, users saved in `@BeforeEach` as in `controller/RecordApiTest.java:80-85`.
  - The in-memory H2 database is shared between test classes, and other tests create records dated "now". To keep totals exact, every stats test uses `period=CUSTOM` on dates in **2031** (no other test writes there) and, where a reader is needed, its own reader with a unique name.
  - `@CreationTimestamp` overwrites `creationDate` on insert: save the record with `recordRepository.saveAndFlush(...)`, then set the date with `jdbcTemplate.update("update record set creation_date = ? where id = ?", offsetDateTime, id)`.
- Front: Bootstrap 5.3 and Font Awesome from the CDN, `config.js` and `auth.js`; every call through `apiFetch(path)`; values from the API set with `textContent` / `createElement`, never interpolated into `innerHTML`; UI text in French; pickers are "Cueilleurs" (Opérateur is a user role).
- If a run fails with `NoClassDefFoundError` or "Unresolved compilation problem", it is the VS Code Java extension racing Maven (`CLAUDE.md`). Rerun `mvn clean test`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: contract and configuration, so the generated delegate method and models exist.

- [X] T001 Add the `/records/stats` path (before `/records/readers/{readerId}`, in the Record section) and the schemas `StatsPeriod`, `RecordCounts`, `PickerStats`, `HourStats`, `RecordStats` (in `components/schemas`, near `RecordsList`) exactly as in `contracts/openapi-dashboard.md`, in `src/main/resources/openapi/api.yaml`; run `mvn generate-sources` and check that `RecordApiDelegate.getRecordStats(StatsPeriod period, LocalDate from, LocalDate to, UUID readerId)` and the five models exist under `target/generated-sources/openapi` (note the exact generated signature and the type of `total`/`nonCompliant`, expected `Long`, for the next tasks)
- [X] T002 [P] Add `app.station.time-zone: ${APP_STATION_TIME_ZONE:Europe/Paris}` under `app:` with a comment `# Days and hours of the dashboard statistics are computed in this zone (spec 007, FR-007).` in `src/main/resources/application.yml`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: station time zone bean and the index used by the range queries.

- [X] T003 Create `configuration/StationProperties.java`: a `@ConfigurationProperties("app.station")` record `StationProperties(ZoneId timeZone)` validated in its compact constructor: `timeZone` not null; the offset at `Instant.now()` and the offsets before/after the next 2 transitions (`rules.nextTransition(now)`, then `nextTransition` from that transition's instant; `null` = no transition, e.g. `UTC`, is fine) must all have `getTotalSeconds() % 3600 == 0`, otherwise throw `IllegalArgumentException("app.station.time-zone must have whole-hour offsets: <zone>")` (research R4, R5). Do **not** iterate `getTransitions()`: it contains historical local mean time (Europe/Paris `+00:09:21` until 1911) and would reject the default zone. Register it with `@EnableConfigurationProperties(StationProperties.class)` on `configuration/ClockConfig.java` (comment: station zone for spec 007 statistics). Do not change the `Clock` bean.
- [X] T004 [P] Add `@Index(name = "idx_record_creation_date", columnList = "creation_date")` to the `indexes` of `@Table` in `entity/RecordEntity.java`, and extend the comment above it: "the third serves the dashboard period queries without a reader (spec 007, research R8)".

**Checkpoint**: `mvn clean test` still passes (context starts with the new property; an invalid zone such as `Asia/Kolkata` makes startup fail).

---

## Phase 3: User Story 1 — Visualiser l'activité de production (Priority: P1) 🎯 MVP

**Goal**: an Opérateur or Administrateur opens `index.html`, picks a period and optionally a reader, and sees exact totals: summary, table per picker (with "Non attribué"), scans per hour, CSV export (FR-002, FR-005, FR-007 to FR-009).

**Independent test**: spec US1 acceptance scenarios 3-8; backend — `mvn clean test -Dtest='RecordStatsServiceTest,RecordStatsApiTest,AccessMatrixSecurityTest'` passes and `curl` on `/api/records/stats` returns the contract's shape; front — scenarios 1-9 of [quickstart.md](quickstart.md).

### Tests for User Story 1 (write first, they must fail) ⚠️

- [X] T005 [P] [US1] Create `service/RecordStatsServiceTest.java` (Mockito: `RecordRepository`, `ReaderRepository`; `StationProperties(ZoneId.of("Europe/Paris"))`) covering, for the method `getStats(StatsPeriod period, LocalDate from, LocalDate to, UUID readerId)` of `RecordStatsService` (constructor `RecordStatsService(RecordRepository, ReaderRepository, Clock, StationProperties)`):
  - `StationProperties`: `Europe/Paris` and `UTC` accepted; `Asia/Kolkata` throws `IllegalArgumentException` (put these cases in a small `configuration/StationPropertiesTest.java` if preferred);
  - period resolution with clock `2026-09-25T10:00:00Z`: `TODAY` and `null` → 25/09–25/09; `YESTERDAY` → 24/09; `LAST_7_DAYS` → 19/09–25/09; `CUSTOM` 01/09–30/09 kept; and with clock `2026-09-25T22:30:00Z` (00:30 Paris on the 26th) `TODAY` → 26/09;
  - the range passed to the repository is `[from 00:00 Paris, to+1 00:00 Paris)` as `OffsetDateTime` (capture arguments), including `CUSTOM` 2026-10-25 (25-hour day: end − start = 25 h);
  - `400` (`ResponseStatusException` with `HttpStatus.BAD_REQUEST`) for: `CUSTOM` without `from`, without `to`; `from` after `to`; 32 days (01/01–01/02); `from` given with `TODAY`; 31 days (01/01–31/01) is accepted;
  - unknown `readerId` → `ReaderNotFoundException`, repository aggregate methods never called; with `readerId` only the `…ForReader` variants are called, without it only the plain ones (stub and `verify` both sets);
  - hour mapping: repository returns UTC hour indexes for `2026-03-29T00:00Z` (01h Paris), `2026-03-29T01:00Z` (03h Paris, after the spring change), `2026-10-25T00:00Z` (02h CEST) and `2026-10-25T01:00Z` (02h CET, after the autumn change; both map to hour 2 and are summed) → `hours` has 24 entries with the expected sums;
  - picker rows: repository rows returned in any order with one null-picker row → response has pickers by lastname, firstname and the null row last; response echoes `from`, `to`, `timeZone = "Europe/Paris"`, `readerId`.
- [X] T006 [P] [US1] Create `controller/RecordStatsApiTest.java` (conventions above; users `stats-admin` Administrateur and `stats-op` Opérateur; own reader `Reader stats test` and a second reader `Reader stats test 2`; pickers `Diallo Amadou`, `Moreau Valérie` with buckets and tags, one tag without bucket). Seed records on 2031 dates via `saveAndFlush` + `jdbcTemplate` and check with `GET /api/records/stats?period=CUSTOM&from=…&to=…`:
  - exact `summary` (`total`, `nonCompliant`), picker rows (`Diallo` before `Moreau`, `pickerId: null` row last with its counts), and the invariants of the contract (Σ pickers = summary, Σ hours = summary, 24 hours);
  - a record at `2031-03-11T23:30:00Z` (00:30 Paris on 12/03) is counted for `from=to=2031-03-12` and not for `2031-03-11`; a record at `2031-03-12T06:15:00Z` (07:15 Paris, winter) is in `hour == 7`; a record at `2031-07-15T05:15:00Z` (07:15 Paris, summer) is in `hour == 7`;
  - `readerId` filter keeps only that reader's records; unknown `readerId` → `404`;
  - a record created compliant then changed with `PATCH /api/records/{id}/conformity {"isCompliant":false}` (with `csrf()`) is counted as non-compliant (FR-008);
  - a record whose tag's bucket is reassigned to another picker after the scan stays counted for the original picker (FR-009);
  - `400` for `period=CUSTOM&from=2031-01-01&to=2031-02-01`, `period=CUSTOM` alone, `period=TODAY&from=2031-01-01`, `period=FOO`, `from=2031-13-01`;
  - `200` for Opérateur and for Administrateur; a range with no record → `summary` 0/0, `pickers` `[]`, 24 zero hours.
- [X] T007 [P] [US1] Add `new Route(HttpMethod.GET, "/api/records/stats", null, LOGGED_IN)` next to the other `/api/records` routes in `security/AccessMatrixSecurityTest.java`

### Implementation for User Story 1 — backend

- [X] T008 [US1] Add three JPQL queries to `repository/RecordRepository.java`, each with parameters `start`, `end` (`OffsetDateTime`, condition `r.creationDate >= :start and r.creationDate < :end`), with a Javadoc citing spec 007. Declare each query **twice**: without reader (`countSummary`, `countByPicker`, `countByUtcHour`) and with an extra `ReaderEntity reader` parameter and `and r.reader = :reader` (`countSummaryForReader`, `countByPickerForReader`, `countByUtcHourForReader`); the service picks the variant. Do **not** use `(:reader is null or r.reader = :reader)`: a typed null parameter can make PostgreSQL fail ("could not determine data type of parameter") and the H2 tests would not catch it. Numeric results (`count`, `sum`, `floor(...)`) may come back as `Long`, `Double` or `BigDecimal` depending on the database: convert with `((Number) value).longValue()`.
  - `countSummary` → a projection interface/record with `total` and `nonCompliant`: `select count(r), coalesce(sum(case when r.compliant = false then 1 else 0 end), 0) from RecordEntity r where …`;
  - `countByPicker` → rows `(pickerId, firstname, lastname, total, nonCompliant)`: `… from RecordEntity r left join r.picker p where … group by p.id, p.firstname, p.lastname`;
  - `countByUtcHour` → rows `(hourIndex, total, nonCompliant)`: `select floor(extract(epoch from r.creationDate) / 3600) … group by floor(extract(epoch from r.creationDate) / 3600)`.
  Use small `record` projections declared in the repository file (e.g. `RecordCountsRow`, `PickerCountsRow`, `HourCountsRow`) with `select new …` constructor expressions, or `List<Object[]>` if Hibernate refuses the constructor with `floor(...)`. If Hibernate rejects `extract(epoch …)` on H2 when T006 runs, apply the fallback of research R4: replace `countByUtcHour` with `findCreationDates(start, end, reader)` returning `List<OffsetDateTime>` and group in the service (same response).
- [X] T009 [US1] Create `service/RecordStatsService.java` (`@Service`, constructor injection of `RecordRepository`, `ReaderRepository`, `Clock`, `StationProperties`), method `@Transactional(readOnly = true) RecordStats getStats(StatsPeriod period, LocalDate from, LocalDate to, UUID readerId)`:
  1. resolve and validate the period (data-model "Period resolution" and "Validation rules"; `null` period = `TODAY`; today = `LocalDate.now(clock.withZone(zone))`);
  2. load the reader with `readerRepository.findById` if `readerId` is given, else `ReaderNotFoundException("Reader %s not found")`;
  3. compute `start = from.atStartOfDay(zone).toOffsetDateTime()`, `end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime()`;
  4. build `summary` from `countSummary`; `pickers` from `countByPicker` (the `…ForReader` variants when `readerId` is given) sorted by lastname, firstname (case-insensitive, `Collator` for `fr` so accents sort naturally), null-picker row last; `hours` as 24 `HourStats` filled by mapping each `hourIndex` with `Instant.ofEpochSecond(hourIndex * 3600).atZone(zone).getHour()` and summing;
  5. set `from`, `to`, `timeZone = zone.getId()`, `readerId`.
  Add a class comment citing spec 007 (FR-002, FR-007 to FR-009) and research R4 for the hour mapping.
- [X] T010 [US1] Implement `getRecordStats` in `controller/RecordController.java` (inject `RecordStatsService`, return `ResponseEntity.ok(recordStatsService.getStats(period, from, to, readerId))`), using the exact signature generated in T001
- [X] T011 [US1] Run `mvn clean test -Dtest='RecordStatsServiceTest,RecordStatsApiTest,AccessMatrixSecurityTest'` and fix until green; if `extract(epoch …)` fails on H2, apply the T008 fallback and record it in `research.md` R4 ("Verification during implementation" → outcome)

**Checkpoint**: the endpoint is complete and proven; `curl` after login returns the contract's shape.

### Implementation for User Story 1 — front (`front/index.html`)

- [X] T012 [US1] In `front/index.html`, replace the dashboard markup (everything between the nav bar and the `<script src="config.js">` tag; keep the `<nav>` block and its links unchanged) with, in French:
  - a filter bar: `<select id="periodSelect">` (Aujourd'hui = `TODAY`, Hier = `YESTERDAY`, 7 derniers jours = `LAST_7_DAYS`, Personnalisée = `CUSTOM`), two `<input type="date" id="fromDate">`/`id="toDate"` shown only for `CUSTOM` with a `<div id="periodError" class="invalid-feedback d-block">`, `<select id="readerSelect">` ("Tous les lecteurs" first), buttons "Actualiser" (`id="refreshBtn"`) and "Exporter CSV" (`id="exportBtn"`, disabled while no data);
  - a line `<small id="periodLabel">` for the resolved period and zone;
  - four summary tiles: Lectures (`statTotal`), Conformes (`statCompliant`), Non conformes (`statNonCompliant`), Taux de conformité (`statRate`);
  - a card "Par cueilleur" with `<table id="pickersTable">` (thead: Cueilleur | Lectures | Non conformes | Taux de conformité; `<tbody id="pickersBody">`; `<tfoot id="pickersFoot">`);
  - a card "Lectures par heure" with `<canvas id="hoursChart">`;
  - state containers: `<div id="loadingState">`, `<div id="emptyState">` ("Aucune lecture sur la période"), `<div id="errorState">` (message + "Réessayer" button).
  Remove the old line selector, pickers grid, "Vitesse" chart, `apiStatus` badge and unused CSS. Pin Chart.js: replace `https://cdn.jsdelivr.net/npm/chart.js` with `https://cdn.jsdelivr.net/npm/chart.js@4.4.4/dist/chart.umd.min.js`.
- [X] T013 [US1] In the inline `<script>` of `front/index.html`, replace all existing functions (`fetchPickersInfo`, `fetchData`, `processBusinessLogic`, `updatePickersGrid`, `initChart`, `updateChart`, `updateStatus`, `animateValue`, `renderLineSelector`, `changeLine`) with:
  - `loadReaders()`: `apiFetch('/readers')`, fill `readerSelect` with **all** readers sorted by `uid` (`localeCompare('fr')`), option value `id`, text `uid` plus ` (désactivé)` when `active === false` and ` (enregistrement)` when `mode === 'ENREGISTREMENT'` (research R6), via `new Option(text, value)`;
  - `buildStatsQuery()`: `period`, `from`/`to` only for `CUSTOM`, `readerId` unless "Tous les lecteurs"; for `CUSTOM` check in the browser that both dates are set, `from <= to` and ≤ 31 days, show the message in `periodError` and skip the call otherwise;
  - `loadStats()`: show `loadingState`, `apiFetch('/records/stats?' + query)`; on `400` read the `detail` of the problem JSON into `periodError`; on other errors show `errorState`; on success keep the response in `lastStats` and call `render(stats)`;
  - `render(stats)`: `periodLabel` = "Du <from> au <to> (heure de <timeZone>)" (or "Le <from>" when equal) with dates formatted `dd/mm/yyyy` from the response strings (no `new Date()` parsing of the date, to avoid zone shifts); tiles (`rate = (total − nonCompliant) / total`, `toLocaleString('fr-FR', {maximumFractionDigits: 1})` + " %", "—" when total is 0); table rows built with `createElement`/`textContent` — name "Prénom Nom", or "Non attribué" (class `fst-italic text-muted`) when `pickerId` is null; footer "Total" equal to the summary; `emptyState` when `summary.total === 0`, `exportBtn` disabled then;
  - chart: one Chart.js stacked bar chart created once (`Conformes` green, `Non conformes` red), labels `HHh`, showing only hours from the first to the last hour with `total > 0` (all 24 if none), updated with `chart.update()`;
  - auto-refresh: `setInterval` every 60 s calling `loadStats()` only when `document.visibilityState === 'visible'` and `lastStats.to >= todayInZone`, where `todayInZone` comes from `new Intl.DateTimeFormat('en-CA', {timeZone: lastStats.timeZone}).format(new Date())`; also refresh on `visibilitychange` back to visible under the same condition;
  - wiring on `DOMContentLoaded`: `await requireRole('ADMINISTRATEUR', 'OPERATEUR')`, `loadReaders()`, `loadStats()`; `change` on `periodSelect`/`readerSelect`/dates and click on `refreshBtn` call `loadStats()`.
  Keep `updateDate()` for the header date if the element stays. The page must no longer call `/api/tags` or `/api/pickers`.
- [X] T014 [US1] Add `exportCsv()` in the inline script of `front/index.html`, bound to `exportBtn` (research R10): from `lastStats.pickers` build lines `Cueilleur;Lectures;Conformes;Non conformes;Taux de conformité (%)`, one per row ("Non attribué" for the null row), then `Total`; rate with one decimal and a comma (`toLocaleString('fr-FR', {minimumFractionDigits: 1, maximumFractionDigits: 1, useGrouping: false})`), empty when total is 0; every field wrapped in `"` with inner `"` doubled, and prefixed with `'` when it starts with `=`, `+`, `-`, `@`, tab or CR; join with `;` and `\r\n`; `new Blob(['﻿' + csv], {type: 'text/csv;charset=utf-8'})`; file name `tableau-de-bord_<from>_<to>.csv`, plus `_<reader uid>` (non-alphanumerics replaced by `-`) when a reader is selected.

**Checkpoint**: quickstart scenarios 1-9 pass by hand.

---

## Phase 4: Polish & Cross-Cutting Concerns

- [X] T015 [P] Update `.specify/specs/007-tableau-de-bord/spec.md`: `Status` → "Target implemented (2026-09-25 clarifications)"; acceptance scenarios 3-8 of US1 checked; FR-002/FR-003 "État actuel" wording → "Livré"; Drift table rows for `GET /api/tags` → delivered
- [X] T016 [P] Add to `CLAUDE.md`, section Persistence or a short new line under Commands: `APP_STATION_TIME_ZONE` (default `Europe/Paris`) sets the zone of dashboard days/hours; startup fails for a zone with non-whole-hour offsets
- [X] T017 [P] Delete `doc/sp_cification_bi_pour_claude_code.md` and `doc/tableau_de_bord_bi_analyses.html` (untracked; wrong domain and they contain the compromised reader token — research R1), after confirming with the user
- [X] T018 Run `mvn clean install` and the full quickstart ([quickstart.md](quickstart.md) sections 1-5, including the `TZ=UTC` spot check and the PostgreSQL volume check for SC-002); fix any failure
- [ ] T019 Operational (no code): as Administrateur on production, find the reader whose token is `176c77ca…` (`GET /api/readers`); if it still has that token, regenerate it with `POST /api/readers/{readerId}/token` and update the physical reader's configuration; record the date in FR-003 of `.specify/specs/007-tableau-de-bord/spec.md` ("Jeton régénéré le …")

---

## Dependencies & Execution Order

- **Setup (T001-T002)**: T001 first (generated types are used by everything Java); T002 independent.
- **Foundational (T003-T004)**: after T002 (T003 binds the property); T004 independent.
- **US1 tests (T005-T007)**: after T001 and T003 (they compile against generated models and `StationProperties`). Write them before T008-T010 and see them fail.
- **US1 backend (T008 → T009 → T010 → T011)**: sequential (repository → service → controller → green tests).
- **US1 front (T012 → T013 → T014)**: same file, sequential. Can start after T001 against the contract, but needs T010 to be checked for real.
- **Polish (T015-T019)**: after US1; T018 after T015-T017; T019 is independent of the code and can be done at any time.

### Parallel opportunities

- T002 ‖ T001 (different files).
- T004 ‖ T003.
- T005 ‖ T006 ‖ T007 (three test files).
- Front T012-T014 ‖ backend T008-T011 (different files; only the final check depends on the backend).
- T015 ‖ T016 ‖ T017.

### Parallel example: User Story 1

```text
Task: "T005 RecordStatsServiceTest in src/test/java/com/rfidback/service/RecordStatsServiceTest.java"
Task: "T006 RecordStatsApiTest in src/test/java/com/rfidback/controller/RecordStatsApiTest.java"
Task: "T007 AccessMatrixSecurityTest route in src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java"
# then, in parallel:
Task: "T008-T011 backend (repository → service → controller)"
Task: "T012-T014 front/index.html"
```

## Implementation Strategy

1. **MVP = backend (T001-T011)**: the endpoint alone delivers SC-001 (exact totals, tested) and can be used with `curl`; it is the part with real risk (SQL portability, time zone).
2. **Then the page (T012-T014)**: pure display over a proven contract.
3. **Then polish (T015-T019)**: docs, cleanup of the misleading `doc/` helpers, full build and quickstart.

Commit after each checkpoint. Open the PR with `--base dev`.

## Phase 5: Convergence

- [X] T020 [US1] In `front/index.html` `loadStats()`, number each request (`const requestId = ++statsRequestSeq`) and ignore any response whose `requestId` is no longer the latest, so a slow response or the 60 s auto-refresh can never overwrite the figures of a newer period/reader selection per FR-005(d), FR-008 (partial)
- [X] T021 Map `ResponseStatusException` to a `ProblemDetail` carrying its reason as `detail` (status kept) in `src/main/java/com/rfidback/controller/ApiExceptionHandler.java`, and assert `$.detail` on one `400` case in `src/test/java/com/rfidback/controller/RecordStatsApiTest.java`, so the page shows the server's message next to the dates per tasks T013 (partial)
- [X] T022 [US1] In `front/index.html` `onPeriodChange()`, when "Personnalisée" is chosen without both dates, clear the figures (`lastStats = null`, hide results, disable export) and show "Choisissez une date de début et une date de fin." instead of leaving the previous period's figures per US1 scenario 6 (partial)
