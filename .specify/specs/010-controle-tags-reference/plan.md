# Implementation Plan: Contrôle des tags par rapport à la liste de référence

**Branch**: `010-controle-tags-reference` | **Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/010-controle-tags-reference/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

The 5,008 UIDs of `doc/rfid_tag_list.csv` become a reference list shipped inside the application and flagged
wherever a tag shows up:

1. **The list** (FR-001, FR-002): moved to `src/main/resources/tags/rfid_tag_list.csv`, loaded at startup by a new
   `ReferenceTagList` component into an in-memory set; a missing, empty or malformed list stops the boot (R1–R3).
2. **Registration** (FR-003, FR-004): registration reads carry `offList`; saving a bucket with off-list tags answers
   `409` until the Administrateur resends with `offListConfirmed: true`, a flag separate from `moveConfirmed`. One
   `409` lists both kinds so `tags.html` shows one dialog (R5).
3. **Production** (FR-005–FR-008): scans are unchanged; `RecordSummary` carries `tagOffList`, shown as a badge on the
   line kiosk (`reader.html`). "Off-list" is derived from the list at read time, never stored (R4).
4. **Clean-up view** (FR-009): new `GET /api/tags/off-list` (Administrateur) with bucket, read count and last read,
   shown in a new section of `tags.html` (R6). The spec's FR-008 filter was revised accordingly.

No schema change, no new dependency, no security rule change.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA, Spring Security 6.5, OpenAPI Generator (delegate
pattern), Lombok. Front: plain HTML/JS + Bootstrap. No new dependency.

**Storage**: none new. The list is a classpath resource held in memory; H2 (dev) / PostgreSQL (prod) unchanged.

**Testing**: JUnit 5 + Mockito (`TagServiceTest`, `RegistrationServiceTest`, new `ReferenceTagListTest`),
`@SpringBootTest` + MockMvc on the `test` profile, using the shipped list and a UID helper (R7).

**Target Platform**: the `rfid-backend` container on the VPS; the list travels inside the jar.

**Project Type**: web service (Spring Boot REST API) + static front (`/front`).

**Performance Goals**: SC-004 of spec `004` (scan p95 < 200 ms) unchanged; the scan path does no new work — the list
is only consulted when building answers (one set lookup per UID).

**Constraints**: API-first (`api.yaml` before code); reader firmware unchanged (`ScanTagResponse` untouched); list
changed only by a new version (clarification Q1); one confirmation per kind (FR-004).

**Scale/Scope**: 5,008 reference UIDs; a few thousand known tags per season. 1 new component, 1 new route, 5 schemas
extended, 1 exception renamed, 2 front pages changed, 1 config property, 1 new test class, tests updated.

No `NEEDS CLARIFICATION` remains: the three spec questions were answered on 2026-09-26, the rest is settled in
[research.md](research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in earlier specs the conventions in
`CLAUDE.md` are the gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: list in a `service`-layer component, rules in `TagService` / `RegistrationService` / `RecordService`, grouped count in `RecordRepository`, controllers only delegate |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: [contracts/openapi-tag-reference.md](contracts/openapi-tag-reference.md); `listOffListTags` added to `TagApiDelegate` via `TagController` |
| Reader devices stay on the stateless token chain, unchanged | `CLAUDE.md` | Pass | Pass: `/tags/scan` contract untouched; kiosk route gains one response field |
| Routes follow the spec `008` access matrix | `CLAUDE.md` | Pass | Pass: `GET /api/tags/off-list` falls under the existing `/api/tags/**` → Administrateur rule; added to `AccessMatrixSecurityTest` |
| Schema changes through entities with `ddl-auto: update` | `CLAUDE.md` | Pass | Pass: no schema change (R4) |
| Tests run on the `test` profile; `mvn clean install` passes | `CLAUDE.md` | Pass | Pass (planned): integration tests take in-list UIDs from the shipped file (R7) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/010-controle-tags-reference/
├── spec.md              # Spec + 2026-09-26 clarifications (FR-008/FR-009 revised during planning)
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R7
├── data-model.md        # Phase 1: reference list, derived flags, registration rule
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── openapi-tag-reference.md   # api.yaml changes and 409 table
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
doc/rfid_tag_list.csv                           # moved (untracked today) → src/main/resources/tags/
src/main/resources/tags/rfid_tag_list.csv       # the reference list (5,008 UIDs)
src/main/resources/application.yml              # app.tags.reference-list
src/main/resources/openapi/api.yaml             # GET /tags/off-list, offListConfirmed, offListTags, offList, tagOffList
src/main/java/com/rfidback/
├── service/ReferenceTagList.java               # new: loads and checks the list, contains(uid)
├── service/TagService.java                     # registerTagsForBucket: off-list check + one 409; listOffListTags
├── service/RegistrationService.java            # offList on reads; offListConfirmed passed to TagService
├── service/RecordService.java                  # tagOffList on RecordSummary
├── exception/RegistrationNotConfirmedException.java   # renamed from TagsInOtherBucketsException, + offListTags
├── controller/ApiExceptionHandler.java         # 409 body with both lists
├── controller/TagController.java               # listOffListTags (offListConfirmed travels in RegisterTagsRequest to TagService)
├── repository/TagRepository.java               # findAllWithBucket (fetch join)
└── repository/RecordRepository.java            # count + max(creation_date) grouped by tag, for given tags
front/
├── tags.html                                   # "hors liste" badge, combined confirm dialog, "Tags hors liste" section
└── reader.html                                 # "hors liste" badge on a record box
src/test/resources/tags/                        # malformed-list fixtures for ReferenceTagListTest
src/test/java/com/rfidback/
├── support/ReferenceTagUids.java               # new: hands out unused in-list UIDs, and off-list ones (R7)
├── service/ReferenceTagListTest.java           # new
├── service/TagServiceTest.java, RegistrationServiceTest.java, RecordServiceTest.java   # extended
├── controller/TagRegistrationApiTest.java, RegistrationApiTest.java, RecordApiTest.java # updated UIDs + new cases
├── controller/TagOffListApiTest.java           # new
└── security/AccessMatrixSecurityTest.java      # + GET /api/tags/off-list
CLAUDE.md                                       # one line on the reference list under Persistence
```

**Structure Decision**: the existing single Spring Boot project plus the static `/front`; no new module.

## Complexity Tracking

No constitution violations to justify.
