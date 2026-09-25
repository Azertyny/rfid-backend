# Implementation Plan: Consultation des lectures et bascule de conformité

**Branch**: `005-consultation-lectures-conformite` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/005-consultation-lectures-conformite/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

The last-10 list and the explicit-value PATCH already work, and spec `008` already requires the Administrateur or
Opérateur role on `/api/records/**` (FR-004, FR-005 delivered; research R0). This plan delivers what the 2026-09-24
and 2026-09-25 clarifications added:

1. **Compliance history** (FR-003): a new table `record_conformity_change` holds one row per real change (previous
   value, new value, author, date). The author is a foreign key to `app_user` (R1, R2).
2. **Idempotent PATCH, safe under concurrent clicks** (FR-003): the record is read under a row lock
   (`SELECT … FOR UPDATE`). The same value as the current one writes nothing and still returns `204` (R3).
3. **Changed records are locked for duplicate scans** (FR-006): `TagService.ignoreDuplicate` no longer lowers a record
   that has a change row. The duplicate lookup takes the same row lock (R4).
4. **History read, Administrateur only** (FR-007): `GET /api/records/{recordId}/conformity-history`, oldest first,
   with a matching `SecurityConfig` rule before `/api/records/**` (R5, R6).
5. **Last-10 list fast enough to poll** (SC-003: p95 < 200 ms with 1 to 3 screens polling every 500 ms and a season of
   records): a `(reader_id, creation_date)` index on `record` and the tags fetched in the same query (R11, R12),
   measured by the quickstart (R13).
6. **Tests**: new `RecordServiceTest` and `RecordApiTest`, extended `TagServiceTest` and `AccessMatrixSecurityTest`.
   This also closes spec SC-001/SC-002 (R8).

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA (Hibernate 6.6), Spring Security 6.5, OpenAPI Generator
7.6.0 (delegate pattern, bean validation on), Lombok. No new dependency.

**Storage**: H2 file (dev), PostgreSQL (prod), `ddl-auto: update`. One new table with one index, and one new index on
`record`, created by Hibernate at boot. No column change to existing tables ([data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito (`RecordServiceTest`, `TagServiceTest`), `@SpringBootTest` + MockMvc on the `test`
profile with real users (`RecordApiTest`, `AccessMatrixSecurityTest`).

**Target Platform**: Linux container behind nginx on the local network. Opérateurs use `front/reader.html` on
production-line screens; readers call `/api/tags/scan` with their token.

**Project Type**: web service (Spring Boot REST API). No front change (R9).

**Performance Goals**: SC-003, 95% of `GET /api/records/readers/{uid}` calls answered in under 200 ms server-side, with
1 to 3 screens polling every 500 ms and about 200,000 records for the reader (R13). With the new index the call reads
10 index entries and runs 2 queries (reader check, list with tags) whatever the table size. The PATCH adds one row lock, one user lookup and one insert. A duplicate scan
adds one indexed existence check. Spec `004`'s SC-004 (p95 < 200 ms per scan) must still hold: locks only wait when a
scan and an Opérateur hit the same record at the same moment.

**Constraints**: API-first (`api.yaml` before code). The reader contract and `front/reader.html` are unchanged.
Existing delegate signatures stay the same. The new table must be created over existing dev and prod data without a
migration tool. Row locking must work on both H2 and PostgreSQL (R4 names the fallback).

**Scale/Scope**: a handful of Opérateurs and readers. A few manual changes per day against a `record` table that grows
all season. 1 new route, 2 new schemas, 1 new entity and repository, 3 repository methods changed or added, 1 new
index on `record`, 2 services changed, 1 security rule, 2 new test classes and 2 extended.

No `NEEDS CLARIFICATION` remains: the spec's open items, including SC-003's latency target, were settled in the
2026-09-24 and 2026-09-25 clarifications.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template. As in the earlier specs, the conventions in
`CLAUDE.md` serve as the gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: history rules in `RecordService`, the FR-006 check in `TagService`, queries in repositories; `RecordController` only delegates |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: see [contracts/openapi-records.md](contracts/openapi-records.md); one new delegate method, existing signatures unchanged |
| Reader devices stay on the stateless token chain | `CLAUDE.md` | Pass | Pass: no change to the filter or the `@Order(1)` chain; only `TagService` logic changes |
| Routes follow the spec `008` access matrix | `CLAUDE.md` | Pass | Pass: new Administrateur-only rule before `/api/records/**`, added to `AccessMatrixSecurityTest` and to spec `008`'s matrix (R6) |
| Schema changes go through entities with `ddl-auto: update` | `CLAUDE.md` | Pass | Pass: a new entity with a named `@Index`, and a second named `@Index` on `RecordEntity`; no column added to an existing table |
| `mvn clean test` and `mvn clean install` pass on the `test` profile | `CLAUDE.md` | Pass | Pass (planned) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/005-consultation-lectures-conformite/
├── spec.md              # As-is spec + 2026-09-24 / 2026-09-25 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R0-R13
├── data-model.md        # Phase 1: history entity, record index, compliance states, repositories
├── quickstart.md        # Phase 1: validation guide, SC-003 latency measurement
├── contracts/
│   └── openapi-records.md   # api.yaml changes, response table, access rule
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml                   # + /records/{recordId}/conformity-history, ConformityChange(s)List; 400/401/403 on record routes
src/main/java/com/rfidback/
├── configuration/SecurityConfig.java                 # + GET /api/records/*/conformity-history → ADMINISTRATEUR, before /api/records/**
├── controller/RecordController.java                  # + listRecordConformityChanges
├── entity/RecordConformityChangeEntity.java          # new (table record_conformity_change)
├── entity/RecordEntity.java                          # + idx_record_reader_date (reader_id, creation_date)
├── repository/RecordConformityChangeRepository.java  # new: existsByRecord, findAllByRecordOrderByChangedAtAsc
├── repository/RecordRepository.java                  # + findWithLockById; @Lock on the duplicate lookup; @EntityGraph("tag") on the last-10 list
├── service/RecordService.java                        # lock, no-op, history insert, author lookup; history read
└── service/TagService.java                           # ignoreDuplicate: no lowering when the record has a change
src/test/java/com/rfidback/
├── service/RecordServiceTest.java                    # new
├── service/TagServiceTest.java                       # + duplicate of a changed record is not lowered
├── controller/RecordApiTest.java                     # new: full flow, 403 for Opérateur, 404, last-10 limit/order
└── security/AccessMatrixSecurityTest.java            # + new route, ADMIN_ONLY
```

**Structure Decision**: single Spring Boot project with the existing packages. No new package, no front change.

## Complexity Tracking

No Constitution Check violations.

## Follow-ups outside this plan

- **Spec `005` itself**: FR-004 and FR-005 still describe the pre-`008` state ("no authentication", "not
  implemented"). Mark them delivered by spec `008`. Once this plan is delivered, mark FR-003, FR-006 and FR-007
  delivered, replace SC-001/SC-002's `NEEDS CLARIFICATION` with the new tests, and add the measured SC-003 figure.
- **Spec `008`**: add the `GET /api/records/{id}/conformity-history` row (Administrateur only) to the access matrix,
  and remove "historique des modifications de conformité (`005`)" from its out-of-scope list.
- **Spec `004`**: its plan's follow-up ("log a duplicate-scan lowering in the history with the reader as author") is
  replaced by FR-006: such a scan no longer changes a record that has a change. Note the rule in 004's spec.
- **`doc/20251116-use_cases.md`**: the `## Acteurs` section still lists only the Administrateur (spec edge case).
- **Front**: no page shows the history. Add one later if Administrateurs need it outside the API (clarification B
  kept it out of scope).
- **Tooling**: `.specify/feature.json` pointed at `004-scan-tag-conformite` while the branch was `005`. It now points
  at `005`.
