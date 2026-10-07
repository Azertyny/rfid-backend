# Implementation Plan: Heures de travail des cueilleurs et caisses par activité

**Branch**: `014-heures-travail-cueilleurs` | **Date**: 2026-10-07 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/014-heures-travail-cueilleurs/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

The Administrateur enters each picker's work hours per station day, and the dashboard's picker table shows, for the
period, each picker's hours and crates per activity:

1. **Work hours** (FR-001–FR-006, User Stories 1 and 3): new table `picker_work_day` (picker, station date, whole
   minutes, last author and date; R1, R2). New tag `WorkHours`: `GET /work-hours?day=` lists every picker with the
   day's hours, record count and a `missingHours` flag (R4); `PUT /work-hours` saves a whole day in one all-or-nothing
   call, `null` deleting an entry, future days refused (R3). Administrateur only (R8). Deleting a picker deletes their
   hours (R7). New page `front/work-hours.html` for the morning entry and the missing-hours check (R9).
2. **Dashboard** (FR-007–FR-013, User Story 2): `GET /records/stats` gains activity columns, per-picker activity
   counts and hours, the period's total hours and `includesToday` (R5). One more grouped record query (picker ×
   activity) and one sum of hours per picker; pickers with hours and no record join the rows; with a reader the
   hours are null (R6). `front/index.html` renders the new columns, hides Heures for a single line, and exports
   them in the CSV (R9).

No crates-per-hour column (Clarifications 2026-10-07).

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA, Spring Security 6.5, OpenAPI Generator (delegate
pattern), Lombok. Front: static HTML + Bootstrap 5, no build. No new dependency.

**Storage**: H2 (dev, test) / PostgreSQL (prod). One new table `picker_work_day` with a unique `(picker_id,
work_date)` and an index on `work_date`, through `ddl-auto: update`; nothing relaxed ([data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito (`WorkHoursServiceTest`, `RecordStatsServiceTest` extended, `PickerServiceTest`
extended), `@SpringBootTest` + MockMvc on the `test` profile (`WorkHoursApiTest`, `RecordStatsApiTest`,
`AccessMatrixSecurityTest`, `KioskReaderTokenSecurityTest` extended); fixed `Clock` bean for today/future (R10).

**Target Platform**: the `rfid-backend` and `rfid-web` containers on the VPS.

**Project Type**: web service (Spring Boot REST API) + static front (`/front`).

**Performance Goals**: dashboard under 1 s for 31 days and 200 000 records (SC-003): one more aggregate of the same
shape as spec 007's, plus a sum over at most 31 × pickers rows (R11). Hours screen: three small queries.

**Constraints**: API-first (`api.yaml` before code); `GET /records/stats` changes are additive (old page still
works); station time zone for "today" and for the dashboard's days; hours never block a scan; Administrateur only for
hours writes; no browser dialogs on new pages.

**Scale/Scope**: 2 new operations on 1 path (tag `WorkHours`), 6 new schemas, 2 schemas extended; 1 entity, 1
repository, 1 service, 1 controller; 2 queries added to `RecordRepository`; 1 security rule; 1 new front page, the
dashboard table and CSV, a navbar link on every page; `CLAUDE.md` (domain term and route).

No `NEEDS CLARIFICATION` remains: the spec's questions were answered on 2026-10-07; design choices are R1–R11 in
[research.md](research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in earlier specs the conventions in
`CLAUDE.md` are the gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: `WorkHoursController` → `WorkHoursService` → `PickerWorkDayRepository`; `RecordStatsService` reads `PickerWorkDayRepository` |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: [contracts/openapi-work-hours.md](contracts/openapi-work-hours.md); new tag `WorkHours` → `WorkHoursApiDelegate` |
| Reader devices and kiosk unchanged | `CLAUDE.md` | Pass | Pass: `@Order(1)` and `@Order(2)` chains untouched; kiosk token gets `403` on `/api/work-hours` |
| Routes follow the spec `008` access matrix; unlisted routes denied | `CLAUDE.md` | Pass | Pass: one user-chain rule, matrix rows in the contract, tested |
| Schema changes through entities with `ddl-auto: update`; no constraint relaxed | `CLAUDE.md` | Pass | Pass: one new table only |
| Station time zone for day boundaries | `CLAUDE.md` (`APP_STATION_TIME_ZONE`) | Pass | Pass: `work_date` is a station date; "today" and the stats range use `StationProperties` |
| Tests on the `test` profile; `mvn clean install` passes | `CLAUDE.md` | Pass | Pass (planned, R10) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/014-heures-travail-cueilleurs/
├── spec.md              # Spec + 2026-10-07 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R11
├── data-model.md        # Phase 1: table, lifecycle, queries, invariants
├── quickstart.md        # Phase 1: automated and manual checks
├── contracts/
│   └── openapi-work-hours.md   # Phase 1: api.yaml changes and access matrix
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml                       # tag WorkHours, /work-hours (GET, PUT), 6 schemas; PickerStats, RecordStats extended
src/main/java/com/rfidback/
├── configuration/SecurityConfig.java                     # user chain: /api/work-hours → ADMINISTRATEUR
├── controller/WorkHoursController.java                   # new, implements WorkHoursApiDelegate
├── entity/PickerWorkDayEntity.java                       # new, table picker_work_day
├── repository/
│   ├── PickerWorkDayRepository.java                      # new: by day, upsert lookup, sum by picker, deleteByPicker
│   └── RecordRepository.java                             # countByPickerAndActivity (+ForReader)
└── service/
    ├── WorkHoursService.java                             # new: day view, all-or-nothing save, validation
    ├── RecordStatsService.java                           # activity columns, hours, hours-only rows, includesToday
    └── PickerService.java                                # deletePicker removes the picker's hours first

src/test/java/com/rfidback/
├── controller/WorkHoursApiTest.java                      # new
├── controller/RecordStatsApiTest.java                    # new fields
├── service/WorkHoursServiceTest.java                     # new
├── service/RecordStatsServiceTest.java                   # columns, sums, reader case
├── service/PickerServiceTest.java                        # deletion with hours
└── security/{AccessMatrix,KioskReaderToken}SecurityTest.java   # /api/work-hours

front/
├── work-hours.html                                       # new Administrateur page "Heures"
├── index.html                                            # picker table columns, notice, CSV
└── pickers.html, readers.html, tags.html, buckets.html, users.html, activities.html   # navbar link "Heures" (data-admin-only)

CLAUDE.md                                                 # domain term "Work hours", route note
```

**Structure Decision**: the existing single Spring Boot project with its static front. `WorkHoursService` owns
entry and validation; `RecordStatsService` stays the only place computing dashboard figures and reads the hours
through the repository, so the hours screen and the dashboard never disagree on a day's boundaries.

## Complexity Tracking

No violations to justify.
