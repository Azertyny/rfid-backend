# Implementation Plan: Enregistrement des TAGs sur un seau (Tag registration on a bucket)

**Branch**: `feature/003-enregistrement-tags-seau` | **Date**: 2026-09-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/003-enregistrement-tags-seau/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Today `POST /api/tags/buckets/{n}` replaces a bucket's tags with a list of UIDs, and no page uses it. Spec `008` has already restricted it to Administrateurs (research R0). This plan delivers the target set by the 2026-09-24 clarifications:

1. Readers get a `mode` (`PRODUCTION` by default, or `ENREGISTREMENT`), switched from `readers.html` through the existing `PATCH /api/readers/{id}` (FR-007).
2. `POST /api/tags/scan` from an `ENREGISTREMENT` reader stores a temporary read in the reader's open registration session and creates no `Record` and no `Tag`. The reader gets the usual response with `isCompliant: true` (FR-007).
3. Registration sessions: one per reader, owned by the Administrateur who clicked "Start", expired after 5 minutes without activity. Four new routes under `/api/tags/registration-sessions` (FR-008, FR-010).
4. Registering tags becomes add-only, refuses to move tags from another bucket without `moveConfirmed` (`409` with the list), and returns `registeredCount` and `totalCount` (FR-001, FR-009, SC-003).
5. A new page, `front/tags.html`, runs the flow: choose reader → Start → live list → bucket number → Save or Cancel (FR-006).
6. Tests cover `registerTagsForBucket`, which has none today.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA (Hibernate 6.6), Spring Security 6.5, OpenAPI Generator 7.6.0 (delegate pattern), Lombok. Front: Bootstrap 5.3 from CDN, plain JS, `front/auth.js`. No new dependency.

**Storage**: H2 file (dev), PostgreSQL (prod), `ddl-auto: update`. One new column `reader.mode`, two new tables `registration_session` and `registration_read` ([data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito with a fixed `Clock` (services), `@SpringBootTest` + MockMvc + `spring-security-test` on the `test` profile (HTTP, security, full flow including a scan with a reader token).

**Target Platform**: Linux container behind nginx on the local network. Static front in `front/`. RFID readers call `/api/tags/scan`; the registration station has its own reader, registered like any other.

**Project Type**: web service (Spring Boot REST API) + static HTML/JS front.

**Performance Goals**: a handful of readers and one or two registration stations. A registration scan costs one lookup of the session by reader, one existence check, and at most one insert. Page polling at `CONFIG.POLLING_INTERVAL` (500 ms) reads one session and its reads, and writes `last_activity_at` at most every 30 s (research R5). Buckets hold a few tags; no pagination.

**Constraints**: API-first (`api.yaml` before code). Reader devices change nothing (same endpoint, same response shape). The existing delegate signatures stay the same. Schema changes must apply over existing dev and prod data without a migration tool. Browsers cannot reach the reader hardware (plain HTTP, no secure context), so reads always go through the backend.

**Scale/Scope**: 1 route changed (`POST /tags/buckets/{n}`), 1 route extended (`PATCH /readers/{id}`), 4 routes added, 1 column, 2 tables, 1 new service and controller, 3 exceptions, 1 new page and 3 pages touched, 3 new test classes and 4 extended.

No `NEEDS CLARIFICATION` remains: the one open item in the spec (SC-002, volume targets) doesn't block design, and the scale assumptions above cover it.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in specs `001`, `002` and `008`, the conventions in `CLAUDE.md` are used as gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: session rules go in `RegistrationService`, the add-only and move rules in `TagService`, lookups in repositories, and controllers only delegate |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: see [contracts/openapi-tags-registration.md](contracts/openapi-tags-registration.md); the new routes go through a generated `RegistrationApiDelegate` |
| Routes follow the spec `008` access matrix; unlisted routes are denied | `CLAUDE.md` | Pass | Pass: the new routes sit under `/api/tags/**` (Administrateur), which the matrix reserves for them; no `SecurityConfig` change; rows added to `AccessMatrixSecurityTest` |
| Reader devices stay on the stateless token chain | `CLAUDE.md` | Pass | Pass: the filter and the `@Order(1)` chain are unchanged; the mode is read after authentication, in `TagController.scanTag` |
| Schema changes go through entities with `ddl-auto: update` | `CLAUDE.md` | Pass | Pass: `@ColumnDefault("'PRODUCTION'")` for the NOT NULL column over existing readers; new tables are created empty |
| `mvn clean test` and `mvn clean install` pass on the `test` profile | `CLAUDE.md` | Pass | Pass (planned) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/003-enregistrement-tags-seau/
├── spec.md              # As-is spec + 2026-09-24 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R0-R9
├── data-model.md        # Phase 1: Reader.mode, RegistrationSession, RegistrationRead, registration rules
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── openapi-tags-registration.md  # api.yaml changes, status table, front contract
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml             # ReaderMode, Reader.mode, UpdateReader.mode, RegisterTags* fields,
                                                # TagsInOtherBuckets, Registration tag + 4 routes and schemas
src/main/resources/application.yml              # app.registration.session-timeout: 5m
src/main/java/com/rfidback/
├── entity/
│   ├── ReaderMode.java                         # new enum
│   ├── ReaderEntity.java                       # + mode
│   ├── RegistrationSessionEntity.java          # new
│   └── RegistrationReadEntity.java             # new
├── repository/
│   ├── TagRepository.java                      # + findAllByUidIn, countByBucket
│   ├── RegistrationSessionRepository.java      # new
│   └── RegistrationReadRepository.java         # new
├── service/
│   ├── TagService.java                         # add-only registration, move check, counts
│   ├── RegistrationService.java                # new: start, get, cancel, save, recordRead, lazy expiry
│   └── ReaderService.java                      # PATCH mode; close session on PRODUCTION or disable; mode in DTO
├── controller/
│   ├── TagController.java                      # scanTag routes by reader mode (research R2)
│   ├── RegistrationController.java             # new, implements RegistrationApiDelegate
│   └── ApiExceptionHandler.java                # + 409 bodies (TagsInOtherBuckets, ReaderBusy)
├── configuration/ClockConfig.java              # new: Clock bean (research R5)
└── exception/
    ├── TagsInOtherBucketsException.java        # new, carries [{uid, bucketNumber}]
    ├── ReaderBusyException.java                # new, carries startedBy / startedAt
    └── RegistrationSessionNotFoundException.java  # new, @ResponseStatus(NOT_FOUND)
src/test/java/com/rfidback/
├── service/
│   ├── TagServiceTest.java                     # + registerTagsForBucket cases
│   └── RegistrationServiceTest.java            # new (Mockito, fixed Clock)
├── controller/
│   ├── TagRegistrationApiTest.java             # new: POST /tags/buckets/{n} add-only, 400, 409, confirm
│   ├── RegistrationApiTest.java                # new: full flow with a real reader token, owner-only, expiry
│   └── ReaderApiTest.java                      # + mode default and PATCH mode
└── security/
    ├── AccessMatrixSecurityTest.java           # + 4 registration routes
    └── ReaderScanSecurityTest.java             # + ENREGISTREMENT reader scan → 200, no Record
front/
├── tags.html                                   # new page (contract §4)
├── readers.html                                # Mode column and switch; + nav link
├── index.html                                  # skip ENREGISTREMENT readers; + nav link
└── pickers.html, reader.html, users.html       # + nav link "Tags" (data-admin-only)
CLAUDE.md                                       # add Registration to the list of OpenAPI tags
```

**Structure Decision**: single Spring Boot project with the existing packages, plus one static page. No new package.

## Complexity Tracking

No Constitution Check violations.

## Follow-ups outside this plan

- **Removing a tag from a bucket**: FR-001 makes it "a separate action", and nothing builds it yet. Until it exists, a wrong tag can only be moved to another bucket, not detached. It fits spec `006` (bucket management), for example `DELETE /api/buckets/{bucketId}/tags/{uid}`.
- **Spec `003` itself**: FR-005 and the "Aucune authentification" edge case still describe the route as unauthenticated, and the third Drift row still reads as open. Spec `008` delivered this (research R0). Mark them delivered.
- **Spec `004`**: FR-003 already defers to this spec for `ENREGISTREMENT` readers. Once delivered, point it at research R2 here for the response sent to the reader.
- **Spec `007`**: note that the dashboard skips `ENREGISTREMENT` readers (research R9).
- **Tooling**: `.specify/feature.json` pointed at `002-gestion-lecteurs` while the branch is `003`. It now points at `003`. Later features should check it after switching branches.
