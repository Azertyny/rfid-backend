# Implementation Plan: Scan d'un TAG par un lecteur (déclaration de conformité)

**Branch**: `004-scan-tag-conformite` | **Date**: 2026-09-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/004-scan-tag-conformite/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

`POST /api/tags/scan` already authenticates readers, routes by reader mode, and creates a `Record` for each production scan (research R0). This plan delivers what the 2026-09-24 clarifications added:

1. **Blank UID → `400`** (FR-002): a `pattern` on `ScanTagRequest.uid` in `api.yaml`, enforced by the generated bean validation before the controller runs, in both reader modes. A new `ApiExceptionHandler` method gives the `400` a `ProblemDetail` naming the field (R1). Today a blank UID reaches `Assert.isTrue` and ends in an unhandled exception.
2. **Deduplication** (FR-008): in `PRODUCTION` mode, a scan of a tag by the same reader within `app.scan.duplicate-window` (default `10s`) of that tag's latest Record creates no Record. The reader gets `200` with the existing Record and `message: "Duplicate read ignored"`. A non-compliant repeat lowers a compliant Record to non-compliant: the non-compliant verdict wins within the window (R2, R3).
3. **Latency** (SC-004, p95 < 200 ms with 1 to 3 readers sending at once): an index on `record (reader_id, tag_id, creation_date)` keeps the new lookup cheap (R4), and the quickstart measures it (R6).
4. **Tests**: a new HTTP test class for the scan route (blank UID, duplicates, two readers), plus `TagServiceTest` cases with a fixed `Clock`.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA (Hibernate 6.6), Spring Security 6.5, OpenAPI Generator 7.6.0 (delegate pattern, bean validation on), Lombok. No new dependency.

**Storage**: H2 file (dev), PostgreSQL (prod), `ddl-auto: update`. One new index on `record`, no new column or table ([data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito with a fixed `Clock` (`TagServiceTest`), `@SpringBootTest` + MockMvc on the `test` profile with real reader tokens (`TagScanApiTest`, `ReaderScanSecurityTest`).

**Target Platform**: Linux container behind nginx on the local network. RFID readers call `/api/tags/scan` with their `x-api-token`.

**Project Type**: web service (Spring Boot REST API). No front change.

**Performance Goals**: SC-004, 95% of scans answered in under 200 ms server-side, deduplication lookup included, with 1 to 3 readers active at once, each sending scans back to back. A production scan does at most: one tag lookup by unique UID, one indexed lookup of the latest Record, and one write (an insert, or an update when a repeat lowers the verdict).

**Constraints**: API-first (`api.yaml` before code). Reader devices change nothing: same endpoint, same response shape, and `message` is an existing optional field. The generated delegate signature stays the same. The index must apply over existing dev and prod data without a migration tool.

**Scale/Scope**: a handful of readers, each sending reads one after the other, and a `record` table that grows all season. 1 route contract tightened, 1 exception handler added, 1 service method changed, 1 repository method, 1 index, 1 property, 1 new test class and 2 extended.

No `NEEDS CLARIFICATION` remains: the spec's open items were settled in the 2026-09-24 clarifications.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in specs `001`, `002`, `003` and `008`, the conventions in `CLAUDE.md` are used as gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: deduplication and the verdict rule live in `TagService.registerScan`, the lookup in `RecordRepository`, error rendering in `ApiExceptionHandler`; `TagController` is unchanged |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: see [contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md); only a `pattern` and descriptions change, the delegate signature doesn't |
| Reader devices stay on the stateless token chain | `CLAUDE.md` | Pass | Pass: no change to the filter or the `@Order(1)` chain. The `400` from validation reaches the reader because `ERROR` dispatches are permitted (R1) |
| Routes follow the spec `008` access matrix | `CLAUDE.md` | Pass | Pass: no new route |
| Schema changes go through entities with `ddl-auto: update` | `CLAUDE.md` | Pass | Pass: a named `@Index` on `RecordEntity`, which Hibernate adds to an existing table (R4) |
| `mvn clean test` and `mvn clean install` pass on the `test` profile | `CLAUDE.md` | Pass | Pass (planned) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/004-scan-tag-conformite/
├── spec.md              # As-is spec + 2026-09-24 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R0-R6
├── data-model.md        # Phase 1: Record index, scan rules, configuration
├── quickstart.md        # Phase 1: validation guide, latency measurement
├── contracts/
│   └── openapi-tag-scan.md   # api.yaml changes, response table, reader compatibility
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml             # ScanTagRequest.uid pattern; /tags/scan and ScanTagResponse.message descriptions
src/main/resources/application.yml              # app.scan.duplicate-window: 10s
src/main/java/com/rfidback/
├── controller/ApiExceptionHandler.java         # + MethodArgumentNotValidException → 400 ProblemDetail (research R1)
├── entity/RecordEntity.java                    # @Table(indexes = idx_record_reader_tag_date)
├── repository/RecordRepository.java            # + findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc
└── service/TagService.java                     # explicit constructor (Clock, window); duplicate check and non-compliant-wins rule in registerScan
src/test/java/com/rfidback/
├── service/TagServiceTest.java                 # new constructor; duplicate within / after window, non-compliant repeat, window 0, new tag skips lookup
├── controller/TagScanApiTest.java              # new: blank and whitespace UID → 400 + detail, repeat → 1 Record, non-compliant repeat, two readers → 2 Records
└── security/ReaderScanSecurityTest.java        # + ENREGISTREMENT reader with blank UID → 400
```

**Structure Decision**: single Spring Boot project with the existing packages. No new package, no front change.

## Complexity Tracking

No Constitution Check violations.

## Follow-ups outside this plan

- **Spec `004` itself**: FR-002's evidence and the "Relectures en rafale" edge case describe today's behavior. Once delivered, mark FR-002 and FR-008 delivered, and point SC-004 at [quickstart.md](quickstart.md) for how it's measured.
- **Spec `003`**: note that a blank UID from an `ENREGISTREMENT` reader now gets `400` rather than "read ignored" (R1).
- **Spec `005`, conformity history**: a non-compliant repeat changes `Record.compliant` (research R2). When `005` adds its history table, this change must be logged there too, with the reader as author.
- **Spec `005` / `007`**: counts and the dashboard now see one Record per pass instead of one per hardware read. Records created before this feature still hold the duplicates. No cleanup is planned; say so if one is wanted.
- **Hard-coded scan path** (spec edge case): `ReaderApiTokenAuthenticationFilter` repeats the exact-path check that `SecurityConfig`'s `securityMatcher` already does. It is harmless while the filter only runs in its chain, so it's left alone here.
- **Tooling**: `.specify/feature.json` pointed at `003-enregistrement-tags-seau` while the branch was `004`. It now points at `004`.
