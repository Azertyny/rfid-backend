# Implementation Plan: Gestion des cueilleurs (Picker management)

**Branch**: `001-gestion-cueilleurs` (work currently on `feature/authentication`) | **Date**: 2026-09-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/001-gestion-cueilleurs/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Picker CRUD already works. This plan closes the gaps between current behaviour and the target set by the 2026-09-24 clarifications:

1. `GET /api/pickers` honours `sort`. The service parses it against an allow-list (`lastname`, `firstname`, `creationDate` × `asc`/`desc`) and answers `400` to anything else (FR-003).
2. `DELETE /api/pickers/{id}` answers `409` while the picker still has a bucket (FR-006).
3. The missing `DELETE /api/buckets/{id}/picker` route (spec `006` FR-007) is added so that bucket can be unassigned first.
4. Out-of-range `page`/`size` values answer `400` instead of `500`, and the dashboard stops requesting `size=500`, which is broken today (research R5).
5. Automated tests finally cover this module (SC-003).

Authentication (FR-008) is already delivered by spec `008`. The `bucketNumbers` list (FR-004) is left to spec `006`.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA, Spring Security 6.5 (spec `008`), OpenAPI Generator 7.6.0 (delegate pattern), Lombok. No new dependency.

**Storage**: H2 file (dev), PostgreSQL (prod), `ddl-auto: update`. No schema change ([data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito (service), `@SpringBootTest` + MockMvc + `spring-security-test` on the `test` profile (HTTP).

**Target Platform**: Linux container behind nginx on the local network; static front in `front/`.

**Project Type**: web service (Spring Boot REST API) + static HTML/JS front.

**Performance Goals**: fewer than 200 pickers (SC-004). Every list call is a single paged query plus one bucket lookup. No index needed.

**Constraints**: API-first (`api.yaml` before code); generated delegate signatures stay the same for the picker routes; the `sort=lastname,asc` value the front already sends must keep working.

**Scale/Scope**: 3 backend routes changed or added, 1 new exception, 1 new exception handler, 2 front pages touched, 2 new test classes.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in spec `008`, the conventions in `CLAUDE.md` are used as gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: sort parsing and the delete check go in `PickerService`, the lookup in `BucketRepository`, and the HTTP mapping in `controller` |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: see [contracts/openapi-pickers.md](contracts/openapi-pickers.md); the new route goes through `BucketApiDelegate` |
| Routes follow the spec `008` access matrix; unlisted routes are denied | `CLAUDE.md` | Pass | Pass: the new route falls under `/api/buckets/**` (Administrateur) and is added to `AccessMatrixSecurityTest` |
| `mvn clean test` and `mvn clean install` pass on the `test` profile | `CLAUDE.md` | Pass | Pass (planned) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/001-gestion-cueilleurs/
├── spec.md              # As-is spec + 2026-09-24 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R8
├── data-model.md        # Phase 1: Picker, sortable fields, delete lifecycle
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── openapi-pickers.md  # api.yaml changes + front contract
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml             # PageSort description, Conflict response, DELETE /buckets/{id}/picker
src/main/java/com/rfidback/
├── controller/
│   ├── PickerController.java                   # pass `sort` through to the service
│   ├── BucketController.java                   # + unassignBucketFromPicker
│   └── ApiExceptionHandler.java                # new: ConstraintViolationException → 400
├── service/
│   ├── PickerService.java                      # listPickers(page, size, sort) + allow-list; deletePicker checks buckets
│   └── BucketService.java                      # + unassignBucketFromPicker
├── repository/
│   └── BucketRepository.java                   # + existsByPicker
└── exception/
    └── PickerHasBucketsException.java          # new, @ResponseStatus(CONFLICT)
src/test/java/com/rfidback/
├── service/PickerServiceTest.java              # new (Mockito)
├── controller/PickerApiTest.java               # new (MockMvc, test profile)
└── security/AccessMatrixSecurityTest.java      # + DELETE /api/buckets/{id}/picker row
front/
├── pickers.html                                # confirmDelete: 409 message, 404 refresh
└── index.html                                  # page through /pickers?size=100 instead of size=500
```

**Structure Decision**: single Spring Boot project with the existing packages. No new package: `ApiExceptionHandler` goes in `controller` because it is HTTP mapping.

## Complexity Tracking

No Constitution Check violations.

## Follow-ups outside this plan

- Update spec `001` FR-008, the "Authentification absente" edge case and the first Drift row: they still say auth is missing, but spec `008` delivered it.
- Spec `006` still owns FR-004 (`bucketNumbers` list, and the `500` on the list/detail when a picker has two buckets). Its FR-007 is delivered here and should be marked so when 006 is planned.
