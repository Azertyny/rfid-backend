# Implementation Plan: Statuts de lecture — relecture ignorée, doublon / perdu et cheat

**Branch**: `015-statuts-doublon-cheat` | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/015-statuts-doublon-cheat/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

A record gets a status with four values, three scan rules change, and the Administrateur sets the two delays:

1. **Status** (FR-001, FR-002): `record.conformity` stays and a nullable `record.anomaly` (`DOUBLON_PERDU`, `CHEAT`)
   is added; an anomaly always goes with `conformity = false`: a Cheat is counted with the non-compliant by every
   existing query, and Doublon / perdu records are left out of the dashboard totals by one shared predicate (R1,
   R10). The API exposes the derived `status` next to `isCompliant`.
2. **Scan rules** (FR-003 to FR-007, User Stories 1 to 3): `TagService.registerScan` goes from four rules to five
   (R3). The same pass is unchanged; the second tag of a crate now marks the crate's record Cheat (R5); a counted
   tag read again less than the re-read delay after its last read by any reader is ignored, using a new
   `tag.last_read_at` (R2, R4); a later rescan creates a Doublon / perdu record, whatever the day, until the tag is
   registered into a bucket again (R5).
3. **Settings** (FR-008, FR-012 to FR-014, User Story 5): new table `scan_setting` and tag `ScanSettings`
   (`GET` / `PUT /scan-settings`, Administrateur only), read from the database at every scan so a change applies to
   the next one; the configuration properties become the defaults (R6, R7). New page `front/settings.html`.
4. **Display and correction** (FR-009 to FR-011, User Story 4): the line screen shows the four statuses;
   `PATCH /records/{id}/conformity` on an anomaly record is Administrateur only and clears the anomaly, traced in the
   history with a new `previous_anomaly` (R8). The Administrateur lifts it on the line screen or from a new page
   `front/anomalies.html`, fed by `GET /records/anomalies` over the dashboard's period and reader (FR-015, R9).
   `GET /records/stats` leaves Doublon / perdu records out of every total and counts them apart (`duplicates`);
   `cheats` is added to each count; both are shown on the dashboard and the Cheat column in the CSV (R10).

Revised on 2026-11-15 after three more clarifications (doublon whatever the day, doublons out of the totals, list of
anomalies): R3, R5, R9, R10.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA, Spring Security 6.5, OpenAPI Generator (delegate
pattern), Lombok. Front: static HTML + Bootstrap 5, no build. No new dependency.

**Storage**: H2 (dev, test) / PostgreSQL (prod). Three nullable columns (`record.anomaly`, `tag.last_read_at`,
`record_conformity_change.previous_anomaly`) and one new table `scan_setting`, through `ddl-auto: update`; nothing
relaxed, no data migration ([data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito (`TagServiceTest`, `RecordServiceTest`, `RecordStatsServiceTest` extended,
`ScanSettingsServiceTest` new), `@SpringBootTest` + MockMvc on the `test` profile (`TagScanApiTest`, `RecordApiTest`,
`RecordStatsApiTest`, `AccessMatrixSecurityTest`, `KioskReaderTokenSecurityTest` extended, `ScanSettingsApiTest`
new); time through the `Clock` bean and aged rows (R11).

**Target Platform**: the `rfid-backend` and `rfid-web` containers on the VPS.

**Project Type**: web service (Spring Boot REST API) + static front (`/front`).

**Performance Goals**: scan p95 under 200 ms with 1 to 3 readers (SC-005): one more primary-key read of two rows
and no new write target per scan (R12). Dashboard: one extra count on the indexed period range (R10).

**Constraints**: API-first (`api.yaml` before code); the readers' firmware changes nothing (same request, same
response shape); API changes are additive except the meaning of the dashboard's `total`, which stops counting
Doublon / perdu records (R10); a setting change applies to the next scan with no
restart and never re-evaluates stored records; an anomaly is never set by hand; no browser dialogs on new pages.

**Scale/Scope**: 3 new operations on 2 paths (tag `ScanSettings`, one on tag `Record`), 6 new schemas, 5 schemas
extended; 1 entity, 2 enums, 1 repository, 1 service, 1 controller added; `TagService`, `RecordService`,
`RecordStatsService`, `RecordController`, `RecordRepository`, `SecurityConfig` changed; 2 new front pages, the line
screen, the dashboard and its CSV, a navbar link on every page; `application.yml`, `CLAUDE.md`, and amendment notes in specs 004, 005 and 007.

No `NEEDS CLARIFICATION` remains: the spec's questions were answered on 2026-11-15; design choices are R1–R12 in
[research.md](research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in earlier specs the conventions in
`CLAUDE.md` are the gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: `ScanSettingsController` → `ScanSettingsService` → `ScanSettingRepository`; `TagService` reads the delays through `ScanSettingsService` |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: [contracts/openapi-scan-status.md](contracts/openapi-scan-status.md); new tag `ScanSettings` → `ScanSettingsApiDelegate` |
| Reader devices unchanged | `CLAUDE.md` | Pass | Pass: `@Order(1)` chain, request and response shape untouched; one new `message` value |
| Kiosk limited to its own reader and its listed routes | `CLAUDE.md` | Pass | Pass: `@Order(2)` chain untouched; the kiosk gets `403` on `/api/scan-settings` and on an anomaly record |
| Routes follow the spec `008` access matrix; unlisted routes denied | `CLAUDE.md` | Pass | Pass: two user-chain rules (`/api/records/anomalies` before the generic `/api/records/**`); the state-dependent `403` is in `RecordService`; all in the contract and tested |
| Schema changes through entities with `ddl-auto: update`; no constraint relaxed | `CLAUDE.md` | Pass | Pass: nullable columns and one new table. To check: a generated `check` on the enum columns (R1) |
| Station time zone for day boundaries | `CLAUDE.md` (`APP_STATION_TIME_ZONE`) | Pass | Pass: the scan rules no longer depend on the day; the anomalies list resolves its period like the dashboard |
| Tests on the `test` profile; `mvn clean install` passes | `CLAUDE.md` | Pass | Pass (planned, R11) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/015-statuts-doublon-cheat/
├── spec.md              # Spec + 2026-11-15 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R12
├── data-model.md        # Phase 1: columns, table, status transitions, invariants
├── quickstart.md        # Phase 1: automated and manual checks
├── contracts/
│   └── openapi-scan-status.md   # Phase 1: api.yaml changes and access matrix
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/
├── openapi/api.yaml                                      # tag ScanSettings, /scan-settings (GET, PUT), /records/anomalies (GET), 6 schemas; RecordSummary, RecordCounts, RecordStats, ConformityChange, ScanTagResponse extended
└── application.yml                                       # app.scan.reread-window: 30m; crate-window documented as the Cheat default
src/main/java/com/rfidback/
├── configuration/SecurityConfig.java                     # user chain: /api/scan-settings and /api/records/anomalies → ADMINISTRATEUR
├── controller/ScanSettingsController.java                # new, implements ScanSettingsApiDelegate
├── controller/RecordController.java                      # + listRecordAnomalies
├── entity/
│   ├── RecordAnomaly.java                                # new enum: DOUBLON_PERDU, CHEAT
│   ├── ScanSettingName.java                              # new enum: REREAD_WINDOW, CHEAT_WINDOW
│   ├── ScanSettingEntity.java                            # new, table scan_setting
│   ├── RecordEntity.java                                 # + anomaly
│   ├── TagEntity.java                                    # + lastReadAt
│   └── RecordConformityChangeEntity.java                 # + previousAnomaly
├── repository/
│   ├── ScanSettingRepository.java                        # new
│   └── RecordRepository.java                             # aggregates without DOUBLON_PERDU; COUNTS: + cheats; duplicates count; anomalies page (+ForReader)
└── service/
    ├── ScanSettingsService.java                          # new: delays in force, read, validated save
    ├── TagService.java                                   # five rules; delays from ScanSettingsService; tag.lastReadAt
    ├── RecordService.java                                # status in summaries and history; Administrateur-only lift; anomalies list
    └── RecordStatsService.java                           # cheats in summary, pickers, hours; duplicates; period resolution shared with the list

src/test/java/com/rfidback/
├── controller/ScanSettingsApiTest.java                   # new
├── controller/{TagScan,Record,RecordStats}ApiTest.java   # rules, lift, counts
├── service/ScanSettingsServiceTest.java                  # new
├── service/{Tag,Record,RecordStats}ServiceTest.java      # rules, lift, counts
└── security/{AccessMatrix,KioskReaderToken}SecurityTest.java   # /api/scan-settings, anomaly PATCH

front/
├── settings.html                                         # new Administrateur page "Réglages"
├── anomalies.html                                        # new Administrateur page: Doublon / perdu and Cheat of a period, lift
├── reader.html                                           # four statuses; lift offered to an Administrateur only
├── index.html                                            # two anomaly figures linking to anomalies.html, Cheat column, CSV
└── pickers.html, work-hours.html, readers.html, activities.html, tags.html, buckets.html, users.html   # navbar link "Réglages" (data-admin-only)

CLAUDE.md                                                 # record status, scan rules, scan settings
.specify/specs/{004,005,007}-*/spec.md                    # notes: FR amended by spec 015
```

**Structure Decision**: the existing single Spring Boot project with its static front. `TagService.registerScan`
stays the only place deciding what a scan becomes; `ScanSettingsService` owns the two delays, so the scan and the
settings page read the same value; `RecordService` stays the only writer of manual changes.

## Complexity Tracking

No violations to justify.
