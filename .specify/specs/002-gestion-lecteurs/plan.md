# Implementation Plan: Gestion des lecteurs RFID (Reader management)

**Branch**: `feature/002-gestion-lecteurs` | **Date**: 2026-09-24, amended 2026-10-06 (reader deletion) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/002-gestion-lecteurs/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Creating and listing readers already works. Spec `008` already closed the token leak: `GET /api/readers` needs a login and only Administrateurs see `apitoken` (research R0). This plan closes the remaining gaps set by the 2026-09-24 clarifications:

1. `POST /api/readers` trims the `uid`, rejects it with `400` when blank or longer than 50 characters (FR-001a), and returns `409` for a duplicate ignoring case (FR-007) instead of letting the database fail with `500`.
2. Readers get an `active` flag. `PATCH /api/readers/{id}` disables and re-enables a reader, and the scan filter refuses a disabled reader's token with `401` (FR-006).
3. `POST /api/readers/{id}/token` generates a new token, and the old one is refused from the next scan on (FR-006).
4. `GET /api/readers` returns `id` and `active` for every reader, whatever the caller's role (FR-003). The front pages decide what to show.
5. Automated tests cover the reader module (SC-002).

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA (Hibernate 6.6), Spring Security 6.5 (spec `008`), OpenAPI Generator 7.6.0 (delegate pattern), Lombok. No new dependency.

**Storage**: H2 file (dev), PostgreSQL (prod), `ddl-auto: update`. One new column, `reader.active boolean not null default true` ([data-model.md](data-model.md), research R4).

**Testing**: JUnit 5 + Mockito (service), `@SpringBootTest` + MockMvc + `spring-security-test` on the `test` profile (HTTP and security).

**Target Platform**: Linux container behind nginx on the local network; static front in `front/`; RFID reader devices calling `/api/tags/scan`.

**Project Type**: web service (Spring Boot REST API) + static HTML/JS front.

**Performance Goals**: a handful of readers. The scan filter keeps its single lookup by `apitoken`, which the unique constraint indexes, and adds an in-memory check of `active`. No cache, so rotation and deactivation apply to the very next scan (research R3).

**Constraints**: API-first (`api.yaml` before code). The existing `createReader`/`listReaders` delegate signatures and the reader devices' configuration stay the same. The schema change must apply over existing dev and prod data without a migration tool.

**Scale/Scope**: 2 routes changed, 2 routes added, 1 new column, 1 new exception, 1 repository method, 3 front pages touched, 2 new test classes and 2 extended.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in specs `001` and `008`, the conventions in `CLAUDE.md` are used as gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: validation, duplicate check, activation and rotation go in `ReaderService`, the lookup in `ReaderRepository`, and the token generator in `ReaderEntity` |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: see [contracts/openapi-readers.md](contracts/openapi-readers.md); both new routes go through `ReaderApiDelegate` |
| Routes follow the spec `008` access matrix; unlisted routes are denied | `CLAUDE.md` | Pass | Pass: the new routes fall under `/api/readers/**` (Administrateur) with no `SecurityConfig` change, and get rows in `AccessMatrixSecurityTest` |
| Reader devices stay on the stateless token chain | `CLAUDE.md` | Pass | Pass: only `ReaderApiTokenAuthenticationFilter` changes (it also checks `active`); the `@Order(1)` chain is untouched |
| Schema changes go through entities with `ddl-auto: update` | `CLAUDE.md` | Pass | Pass: `@ColumnDefault("true")` lets the NOT NULL column be added over existing rows (research R4) |
| `mvn clean test` and `mvn clean install` pass on the `test` profile | `CLAUDE.md` | Pass | Pass (planned) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/002-gestion-lecteurs/
├── spec.md              # As-is spec + 2026-09-24 and 2026-10-06 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R0-R8, amendment R9-R14
├── data-model.md        # Phase 1: Reader, validation rules, active/disabled states
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── openapi-readers.md  # api.yaml changes, status-code table, front contract
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml             # CreateReader.maxLength, Reader.id/active, UpdateReader,
                                                # PATCH /readers/{id}, POST /readers/{id}/token, 409 on create
src/main/java/com/rfidback/
├── entity/ReaderEntity.java                    # + active (@ColumnDefault, @Builder.Default), newApitoken()
├── repository/ReaderRepository.java            # + existsByNameIgnoreCase
├── service/ReaderService.java                  # trim/blank/duplicate on create; updateReader; rotateToken; id+active in DTO
├── controller/ReaderController.java            # + updateReader, rotateReaderToken
├── security/ReaderApiTokenAuthenticationFilter.java  # 401 when reader is disabled
└── exception/ReaderAlreadyExistsException.java # new, @ResponseStatus(CONFLICT)
src/test/java/com/rfidback/
├── service/ReaderServiceTest.java              # new (Mockito)
├── controller/ReaderApiTest.java               # new (MockMvc, test profile)
└── security/
    ├── ReaderScanSecurityTest.java             # + rotated token → 401, disabled → 401, reactivated → 200
    └── AccessMatrixSecurityTest.java           # + PATCH /api/readers/{id}, POST /api/readers/{id}/token
front/
├── readers.html                                # État column, Désactiver/Réactiver, Régénérer la clé
├── index.html                                  # skip inactive readers when building lines
└── reader.html                                 # label inactive readers
```

**Structure Decision**: single Spring Boot project with the existing packages. No new package.

## Complexity Tracking

No Constitution Check violations.

## Follow-ups outside this plan

- Spec `008`, access-matrix row "`POST /api/readers` (et futures routes de rotation/suppression, changement de mode)": replace "suppression" with "désactivation". Readers are never deleted (spec `002` FR-006). *(Superseded by the 2026-10-06 amendment below: the row gains `DELETE /api/readers/{id}`.)*
- Spec `002` itself: FR-003 and FR-005 still describe the unauthenticated state as current, and the third Drift row still reads as open. Spec `008` delivered both. Mark them delivered, as the 001 follow-up did for its FR-008.
- After deploying, rotate the token formerly hard-coded in `front/index.html` ([quickstart.md](quickstart.md), "After deploying").
- Spec `003` adds `mode` to `UpdateReader` and `ReaderEntity`. The `PATCH` route here is shaped for it (research R2).

---

## Amendment 2026-10-06: deleting a reader (FR-008, User Story 5)

### Summary

An Administrateur can delete a reader that is already deactivated. The delete is logical (soft): the row stays, so the records, conformity changes and activity changes that point to it keep working and keep showing its `uid`. Spec Clarifications 2026-10-06 set four rules:

1. `DELETE /api/readers/{readerId}` → `204`. It answers `409` while the reader is active, and `404` for an unknown or already deleted reader (research R9, R10).
2. The delete removes the line from all its activities, clears its current activity (logged as an activity change by the Administrateur), and discards any open registration session (research R11).
3. A deleted reader disappears from `GET /api/readers` for every role. Every route that names a reader by `id` for management answers `404` for it. Its token stays refused, because it is deactivated and can no longer be reactivated (research R12).
4. The deleted reader's `uid` stays taken: `existsByNameIgnoreCase` still sees the row, so creation answers `409` without any change. Its records still count in `GET /api/records/stats` and stay reachable by `GET /api/records/stats?readerId=` and `GET /api/records/readers/{uid}` (research R13).

### Technical Context (changes only)

**Storage**: one new nullable column, `reader.deleted_at timestamp with time zone`, added by `ddl-auto: update` with no default needed (research R9). No row is ever physically deleted.

**Performance Goals**: unchanged. `GET /api/readers` filters in the query, and the midnight reset query gains a `deleted_at is null` condition.

**Scale/Scope**: 1 route added, 1 column, 1 exception, 2 repository methods changed or added, 4 services touched (`ReaderService`, `ActivityService`, `LineActivityService`, `RegistrationService`), 1 front page (`readers.html`), tests extended in 5 classes.

### Constitution Check (amendment)

| Gate | Pre-design | Post-design |
|---|---|---|
| Layering | Pass | Pass: the delete goes in `ReaderService`, which reuses `LineActivityService.clear` and `RegistrationService.closeForReader` |
| API-first | Pass | Pass: `DELETE /readers/{readerId}` in [contracts/openapi-readers.md](contracts/openapi-readers.md) §5, implemented through `ReaderApiDelegate.deleteReader` |
| Spec `008` access matrix | Pass | Pass: `/api/readers/**` is already Administrateur-only for every method (`SecurityConfig.java:163`), so no change; one new row in `AccessMatrixSecurityTest` |
| Reader devices stay on the token chain | Pass | Pass: no filter change; a deleted reader is always deactivated (research R12) |
| Schema through entities, `ddl-auto: update` | Pass | Pass: a nullable column needs no default and no `ConformityAuthorSchemaUpgrade`-style step |
| `mvn clean test` / `mvn clean install` | Pass | Pass (planned) |

No violations.

### Source Code (amendment)

```text
src/main/resources/openapi/api.yaml             # + DELETE /readers/{readerId}; 404 wording for deleted readers
src/main/java/com/rfidback/
├── entity/ReaderEntity.java                    # + deletedAt (nullable), isDeleted()
├── repository/ReaderRepository.java            # + findAllByDeletedAtIsNull; findIdsWithStaleState skips deleted readers
├── exception/ReaderStillActiveException.java   # new, @ResponseStatus(CONFLICT)
├── service/ReaderService.java                  # + deleteReader; listing, PATCH and rotation treat deleted as 404
├── service/ActivityService.java                # setReaderActivities: deleted reader → 404
├── service/LineActivityService.java            # GET/PUT /lines/{uid}/current-activity: deleted reader → 404
├── service/RegistrationService.java            # start (POST /tags/registration-sessions): deleted reader → 404
└── controller/ReaderController.java            # + deleteReader
src/test/java/com/rfidback/
├── service/ReaderServiceTest.java              # delete: active → 409, cleanup, already deleted → 404
├── controller/ReaderApiTest.java               # 204/404/409, hidden from GET, uid still taken, routes → 404
├── controller/RecordStatsApiTest.java          # deleted reader's records still counted, readerId filter still answers
├── controller/RecordApiTest.java               # GET /records/readers/{uid} still answers for a deleted reader
├── controller/LineActivityApiTest.java         # /lines/{uid}/current-activity → 404 for a deleted reader
├── security/ReaderScanSecurityTest.java        # deleted reader's token → 401 on scan and kiosk routes
└── security/AccessMatrixSecurityTest.java      # + DELETE /api/readers/{id} (ADMIN_ONLY)
front/readers.html                              # "Supprimer" on deactivated rows, with a confirm
```

Other pages (`index.html`, `reader.html`, `activities.html`, `tags.html`) need no change: they build their selectors from `GET /api/readers`, which no longer returns deleted readers (research R14).

### Follow-ups

- Spec `008`, access-matrix row for `/api/readers/{id}`: add `DELETE /api/readers/{id}` (suppression logique, spec `002` FR-008).
- `CLAUDE.md`, Persistence: mention that readers are soft-deleted (`reader.deleted_at`) and never physically removed.
