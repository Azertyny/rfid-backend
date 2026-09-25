# Implementation Plan: Tableau de bord (index.html)

**Branch**: `007-tableau-de-bord` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/007-tableau-de-bord/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

The dashboard page exists but has had no data source since `GET /api/tags` was found not to exist. After the
2026-09-25 clarifications it becomes a fixed dashboard fed by exact server-side totals:

1. **New endpoint `GET /api/records/stats`** (FR-002, FR-007 to FR-009): defined first in `api.yaml`. For a named period
   (`TODAY` by default, `YESTERDAY`, `LAST_7_DAYS`, `CUSTOM` ≤ 31 days) and an optional reader, it returns the summary,
   one row per picker (a "Non attribué" row last) and 24 hourly entries. Three `GROUP BY` queries; days and hours in
   the configurable station zone (`Europe/Paris`), with hours grouped by UTC hour index and mapped in Java so the
   result is DST-safe and portable between H2 and PostgreSQL.
2. **Rewrite of the dashboard in `front/index.html`** (FR-005): filter bar, summary tiles, picker table, hourly stacked
   chart, CSV export; empty/error states with no demo data; auto-refresh only when the period contains today.
3. **Tests (SC-001)**: service unit test (period resolution, DST mapping) and API test on H2 (exact totals and
   invariants); one line in `AccessMatrixSecurityTest`.

The generated helper documents in `doc/` are only partly reused: see [research.md](research.md) R1 for what is kept
and what is dropped (invented crate types and weights, demo data, hardcoded token, browser-side aggregation, XSS).

## Technical Context

**Language/Version**: Java 21 (`pom.xml`); plain HTML/JS for the front, with no build step.

**Primary Dependencies**: Spring Boot 3.5.7 (Hibernate 6), Spring Data JPA, Spring Security 6.5, OpenAPI Generator
(delegate pattern), Lombok. Front: Bootstrap 5.3, Font Awesome, Chart.js 4 (now pinned) from the CDN. No new
dependency.

**Storage**: H2 file (dev), PostgreSQL (prod), `ddl-auto: update`. One new index on `record.creation_date`
([data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito (service, fixed `Clock`); `@SpringBootTest` + MockMvc on the `test` profile with real
H2 (SQL aggregates). The front is checked by hand ([quickstart.md](quickstart.md)).

**Target Platform**: Linux container behind nginx on the local network; static front in `front/` served by
`deploy/front`.

**Project Type**: web service (Spring Boot REST API) + static HTML/JS front.

**Performance Goals**: SC-002 — `GET /api/records/stats` under 1 s for a 31-day range with 200 000 records on
PostgreSQL, checked by [quickstart.md](quickstart.md) section 5 (a large station:
~6 500 scans/day), since the three queries are indexed range scans + `GROUP BY` returning at most a few hundred picker
rows and 745 hour groups. The response stays a few KB whatever the volume.

**Constraints**:
- API-first: `api.yaml` changes before code.
- Days/hours in the station zone, independent of JVM, database session and browser zones.
- Station zone offsets must be whole hours (checked at startup).
- No raw records sent to the browser; no demo data; no `innerHTML` with API values.
- Reads only; no CSRF concern (GET).

**Scale/Scope**:
- Backend: 1 path + 5 schemas in `api.yaml`; `RecordController` (+1 method); new `RecordStatsService`,
  `StationProperties`; `RecordRepository` (+3 queries); `RecordEntity` (+1 index); `application.yml` (+1 property).
- Front: `index.html` dashboard section rewritten (nav bar kept).
- Tests: 2 new classes, 1 updated.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so, as in specs `001`, `006` and `008`, the
conventions in `CLAUDE.md` are used as gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: `RecordController` delegates to `RecordStatsService`; queries in `RecordRepository` |
| API changes start in `api.yaml`; controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: path and schemas in [contracts/openapi-dashboard.md](contracts/openapi-dashboard.md); `getRecordStats` generated in `RecordApiDelegate` |
| Routes follow the spec `008` access matrix; unlisted routes are denied | `CLAUDE.md` | Pass | Pass: covered by `/api/records/**` → Administrateur, Opérateur (matches FR-004); added to `AccessMatrixSecurityTest` |
| Schema changes through entities + `ddl-auto` (no migration tool) | `CLAUDE.md` | Pass | Pass: only an `@Index` on `RecordEntity` |
| Front is static HTML/JS without a build step; calls go through `apiFetch` | `CLAUDE.md` | Pass | Pass: no `package.json`, no JS test runner (the generated doc's Vitest/Jest step is rejected, research R11) |
| `mvn clean test` and `mvn clean install` pass on the `test` profile | `CLAUDE.md` | Pass | Pass (planned) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/007-tableau-de-bord/
├── spec.md              # As-is spec + 2026-09-24/25 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R11 (incl. critique of the doc/ helpers)
├── data-model.md        # Phase 1: fields read, index, period resolution, invariants
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── openapi-dashboard.md  # api.yaml changes + front contract
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/
├── openapi/api.yaml                              # + /records/stats, StatsPeriod, RecordCounts, PickerStats, HourStats, RecordStats
└── application.yml                               # + app.station.time-zone: ${APP_STATION_TIME_ZONE:Europe/Paris}
src/main/java/com/rfidback/
├── configuration/StationProperties.java          # new: @ConfigurationProperties("app.station"), ZoneId, whole-hour check
├── controller/RecordController.java              # + getRecordStats → RecordStatsService
├── service/RecordStatsService.java               # new: period resolution/validation, 3 aggregates, hour mapping
├── repository/RecordRepository.java              # + countSummary, countByPicker, countByUtcHour (JPQL, optional reader)
└── entity/RecordEntity.java                      # + idx_record_creation_date
src/test/java/com/rfidback/
├── service/RecordStatsServiceTest.java           # new (Mockito, fixed Clock)
├── controller/RecordStatsApiTest.java            # new (MockMvc, test profile, H2, JdbcTemplate-set creation_date)
└── security/AccessMatrixSecurityTest.java        # + GET /api/records/stats → LOGGED_IN
front/
└── index.html                                    # dashboard rewritten; pinned Chart.js; no /tags, no /pickers
```

Validation errors throw `ResponseStatusException(HttpStatus.BAD_REQUEST, "<message>")`, as the other services do
(`PickerService.java:202`, `TagService.java:149`); malformed `period`/date/UUID values are rejected by Spring's parameter
binding (`400`). An unknown reader reuses `ReaderNotFoundException` (`404`).

**Structure Decision**: single Spring Boot project with the existing packages; the dashboard stays in
`front/index.html`. A separate `RecordStatsService` keeps `RecordService` focused on single records.

## Complexity Tracking

No Constitution Check violations.

## Follow-ups outside this plan

- `doc/sp_cification_bi_pour_claude_code.md` and `doc/tableau_de_bord_bi_analyses.html` are untracked and describe a
  different domain (crates, washing lines, weights) with a compromised token. Do not commit them as-is; either delete
  them or keep a note pointing to this spec.
- The reader token exposed in git history (FR-003) and repeated in the prototype: the rotation route
  `POST /readers/{readerId}/token` now exists in `api.yaml`; tracked as operational task T019.
- Spec `001`: decide what deleting a picker who has records does (refuse with `409`, or set `record.picker_id` to
  null); today it most likely fails on the foreign key. The dashboard is consistent either way (data-model).
- Pivot builder, saved views, weights by bucket type: a later spec if needed (FR-006), built on `/records/stats`.
- Spec `007` header: switch `Status` to the target once implemented.
