# Implementation Plan: Activités des lignes de production

**Branch**: `012-activites-lignes` | **Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/012-activites-lignes/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

An **activity** (product type) is chosen per line, a line being a reader in `PRODUCTION` mode:

1. **Catalogue and associations** (FR-001–FR-007, User Story 1): new `activity` table with a unique normalised name
   (R4) and a `reader_activity` join table (R5). Administrateur routes `GET/POST /activities`,
   `PATCH/DELETE /activities/{id}`, `PUT /readers/{readerId}/activities`. Clearing a line's current activity by
   dissociation or deactivation answers `409 LinesLosingActivity` until `confirmed: true` (R6). Delete only if never
   used (R7). New page `front/activities.html`.
2. **Current activity** (FR-008–FR-013, User Story 2): two columns on `reader` (R1). `GET/PUT
   /lines/{readerUid}/current-activity` for the kiosk token (own line only) and logged-in Opérateurs/Administrateurs
   (R9); the reader row is locked on writes. Every change goes to `line_activity_change` with a user, reader or system
   author (R8). Midnight reset in the station time zone: a "set today?" rule on every read plus a scheduled job and a
   startup catch-up that log the reset at the due midnight (R3). Switching a reader to `ENREGISTREMENT` clears it (R12).
3. **Records** (FR-014–FR-017, User Story 3): `record.activity_id` stamped in `TagService.registerScan` from a fresh
   read of the reader's effective activity (R2); never changed afterwards, scan contract unchanged. `RecordSummary`
   gains `activityId`/`activityName` (R11).
4. **Kiosk** (FR-009, FR-009a): `reader.html` shows the activity, a two-tap chooser, a warning banner with the count
   of today's records without activity, and each record's activity (R10, R13).

Dashboard filtering by activity is out of scope (Clarifications 2026-09-26).

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA, Spring Security 6.5, Spring scheduling (in
`spring-context`, enabled with `@EnableScheduling`), OpenAPI Generator (delegate pattern), Lombok. Front: static
HTML + Bootstrap 5, no build. No new dependency.

**Storage**: H2 (dev, test) / PostgreSQL (prod). New tables `activity`, `reader_activity`, `line_activity_change`;
new nullable columns `reader.current_activity_id`, `reader.current_activity_set_at`, `record.activity_id`; new
index `idx_record_activity`. All through `ddl-auto: update`, nothing relaxed (see [data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito (`ActivityServiceTest`, `LineActivityServiceTest`), `@SpringBootTest` + MockMvc on the
`test` profile (`ActivityApiTest`, `LineActivityApiTest`, `ActivityDailyResetTest`; `TagScanApiTest`,
`RecordApiTest`, `AccessMatrixSecurityTest`, `KioskReaderTokenSecurityTest` extended) (R14). A fixed `Clock` bean
drives the midnight rule.

**Target Platform**: the `rfid-backend` and `rfid-web` containers on the VPS; kiosk touch screens on the lines.

**Project Type**: web service (Spring Boot REST API) + static front (`/front`).

**Performance Goals**: the scan adds one indexed read of the reader row (no lock) — negligible next to the existing
tag and duplicate lookups. The kiosk polls `GET /lines/{uid}/current-activity` every 5 s (one row + one indexed
count). Spec volume: a few lines, tens of activities, a few changes per line per day.

**Constraints**: API-first (`api.yaml` before code); scan request/response unchanged, no reader reconfiguration
(FR-016); a scan never fails for lack of activity; the choice from a previous day is never stamped after midnight,
station time (FR-008a); kiosk token limited to its own line (FR-009); no browser dialogs on the kiosk.

**Scale/Scope**: 7 new operations (6 under tag `Activity`, 1 under `Reader`) on 4 paths, `RecordSummary` + 2 fields,
3 entities (1 new join table), 2 services (`ActivityService`, `LineActivityService`), 1 scheduled component, 2
security-matrix changes, 1 new front page, `reader.html` and the navbar of every page, docs (`CLAUDE.md`,
`deploy/INSTALL.md` untouched unless the kiosk launcher changes — it does not).

No `NEEDS CLARIFICATION` remains: spec questions were answered on 2026-09-26; the rest is settled in
[research.md](research.md) (R1–R14).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in earlier specs the conventions in
`CLAUDE.md` are the gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: `ActivityController`/`ReaderController` delegate to `ActivityService` and `LineActivityService`; `TagService` asks `LineActivityService` for the effective activity |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: [contracts/openapi-activities.md](contracts/openapi-activities.md); new tag `Activity` → `ActivityApiDelegate` |
| Reader devices stay on the stateless token chain, scan contract unchanged | `CLAUDE.md`, FR-016 | Pass | Pass: `@Order(1)` chain untouched; `ScanTagRequest`/`ScanTagResponse` unchanged |
| Kiosk token only opens its own reader's data | `CLAUDE.md`, spec `008` FR-005a | Pass | Pass: kiosk chain opens `GET/PUT /api/lines/*/current-activity` only; service returns `403` for another uid (R9) |
| Routes follow the spec `008` access matrix; unlisted routes denied | `CLAUDE.md` | Pass | Pass: matrix rows in the contract, tested in `AccessMatrixSecurityTest` |
| Schema changes through entities with `ddl-auto: update`; no constraint relaxed | `CLAUDE.md` | Pass | Pass: only new tables, nullable columns and indexes (data-model) |
| Station time zone for day boundaries | `CLAUDE.md` (`APP_STATION_TIME_ZONE`) | Pass | Pass: reset and banner count use `StationProperties` (R3, R10) |
| Tests on the `test` profile; `mvn clean install` passes | `CLAUDE.md` | Pass | Pass (planned, R14) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/012-activites-lignes/
├── spec.md              # Spec + 2026-09-26 clarifications (FR-003 refined during planning, research R7)
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R14
├── data-model.md        # Phase 1: tables, state transitions, repository additions
├── quickstart.md        # Phase 1: automated and manual checks
├── contracts/
│   └── openapi-activities.md   # Phase 1: api.yaml changes and access matrix
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml                       # tag Activity, 4 paths (7 operations), 9 schemas; RecordSummary +2; ReaderApiToken text
src/main/java/com/rfidback/
├── configuration/
│   ├── ClockConfig.java                                  # @EnableScheduling
│   └── SecurityConfig.java                               # kiosk chain: GET/PUT /api/lines/*/current-activity; user chain: /api/activities, /api/lines/**
├── controller/
│   ├── ActivityController.java                           # new, implements ActivityApiDelegate
│   ├── ReaderController.java                             # setReaderActivities
│   └── ApiExceptionHandler.java                          # LinesLosingActivityException → 409 body
├── entity/
│   ├── ActivityEntity.java                               # new (+ reader_activity join table)
│   ├── LineActivityChangeEntity.java                     # new
│   ├── ActivityChangeAuthorType.java                     # new enum USER / READER / SYSTEM
│   ├── ReaderEntity.java                                 # currentActivity, currentActivitySetAt
│   └── RecordEntity.java                                 # activity, idx_record_activity
├── exception/
│   ├── ActivityAlreadyExistsException.java               # new → 409
│   ├── ActivityNotFoundException.java                    # new → 404
│   └── LinesLosingActivityException.java                 # new → 409 LinesLosingActivity
├── repository/
│   ├── ActivityRepository.java                           # new
│   ├── LineActivityChangeRepository.java                 # new
│   ├── ReaderRepository.java                             # findWithLockById, findCurrentActivity, reset queries
│   └── RecordRepository.java                             # existsByActivity, banner count, entity graph + activity
└── service/
    ├── ActivityService.java                              # new: catalogue, associations, confirmations, delete
    ├── LineActivityService.java                          # new: effective activity, choice, clear, log
    ├── ActivityDailyReset.java                           # new: @Scheduled midnight + ApplicationReadyEvent catch-up, both call LineActivityService.resetStaleActivities
    ├── TagService.java                                   # registerScan stamps the activity
    ├── RecordService.java                                # RecordSummary activity fields
    └── ReaderService.java                                # mode → ENREGISTREMENT clears the current activity

src/test/java/com/rfidback/
├── controller/ActivityApiTest.java                       # new
├── controller/LineActivityApiTest.java                   # new
├── controller/TagScanApiTest.java                        # activity stamping cases added
├── controller/RecordApiTest.java                         # activity fields in the list
├── service/ActivityServiceTest.java                      # new
├── service/LineActivityServiceTest.java                  # new
├── service/ActivityDailyResetTest.java                   # new
└── security/{AccessMatrix,KioskReaderToken}SecurityTest.java   # new routes

front/
├── activities.html                                       # new Administrateur page
├── reader.html                                           # activity header, chooser, banner, record activity
└── index.html, pickers.html, readers.html, tags.html, buckets.html, users.html   # navbar link "Activités" (data-admin-only)

CLAUDE.md                                                 # kiosk chain routes; activity reset job; domain term "Activity"
```

**Structure Decision**: the existing single Spring Boot project with its static front. `LineActivityService` owns the
"effective activity" rule so the scan, the kiosk and the job share it; `ActivityService` owns the catalogue and
associations and calls `LineActivityService.clear` when a change removes a current activity.

## Complexity Tracking

No violations to justify.
