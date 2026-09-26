# Implementation Plan: Contrôle des tags par rapport à la liste de référence (révision)

**Branch**: `010-rejet-tags-hors-liste` (from `011-envoi-lot-tags-enregistrement`, whose batch route it changes) |
**Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/010-controle-tags-reference/spec.md`, revised on 2026-09-26
(Clarifications, "révision : tags hors liste écartés sans affichage").

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.
The first delivery of this feature (branch `010-controle-tags-reference`, PRs #34 and #36) flagged off-list tags; this
plan replaces that behaviour. Its tasks (T001–T040 in [tasks.md](tasks.md)) are history; `/speckit-tasks` regenerates
the file for this revision.

## Summary

The reference list (5,008 UIDs, last-12-character match) stays as delivered. What changes is what happens to a tag
that is not in it: instead of being flagged, it is kept out of the database, and the ones already stored are deleted
once.

1. **Production scan** (FR-006, R4): `TagService.registerScan` checks the list before touching the database; an
   off-list UID gets `200`, `isCompliant: true`, "Tag not in reference list, ignored", and nothing is written.
2. **Registration reads** (FR-003, FR-005, R5): tag-by-tag and batch (spec `011`) reads drop off-list UIDs before
   they reach the session; the reader's answers keep their shape.
3. **Bucket association** (FR-004, R6): an off-list UID refuses the whole request with `400` naming it, on both the
   direct route and the session save; `offListConfirmed` goes away, the `409` is about moves only again.
4. **One-off purge** (FR-007, R7, R8): a startup runner deletes off-list tags with their records, conformity changes
   and bucket link, plus off-list registration reads, in one transaction, then writes a marker row in a new
   `data_upgrade` table so it never runs again.
5. **Removal** (FR-008, FR-009, R9): the off-list tags route and page section, and every "hors liste" field and badge.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA, Spring Security 6.5, OpenAPI Generator (delegate
pattern), Lombok. Front: plain HTML/JS + Bootstrap. No new dependency.

**Storage**: H2 (dev) / PostgreSQL (prod). One new table `data_upgrade` (entity, `ddl-auto: update`); rows deleted
once from `record_conformity_change`, `record`, `tag`, `registration_read`. Recovery: the pre-deployment backup.

**Testing**: JUnit 5 + Mockito (`TagServiceTest`, `RegistrationServiceTest`), `@SpringBootTest` + MockMvc on the
`test` profile with in-list UIDs from the shipped file (`ReferenceTagUids`), and `OffListTagPurgeTest` on its own
in-memory database (R10).

**Target Platform**: the `rfid-backend` container on the VPS; the purge runs at the first startup of the new image,
after `deploy.sh`'s backup.

**Project Type**: web service (Spring Boot REST API) + static front (`/front`).

**Performance Goals**: SC-004 of spec `004` (scan p95 < 200 ms) unchanged; the off-list path is cheaper than a normal
scan. The purge touches a few thousand tags once, inside startup.

**Constraints**: API-first (`api.yaml` before code); reader firmware unchanged (`ScanTagRequest`/`ScanTagResponse`,
`RegistrationReads*` schemas untouched); the purge runs once and only once (clarification révision Q4); no
confirmation can let an off-list tag in (FR-004).

**Scale/Scope**: 5,008 reference UIDs; a few thousand known tags per season. 2 new classes (`OffListTagPurge`,
`DataUpgradeEntity`) + 1 repository, 4 repository delete methods, 3 services changed, 1 route and 2 schemas removed,
5 schemas trimmed, 2 front pages trimmed, 1 test class added, 1 removed, ~9 updated.

No `NEEDS CLARIFICATION` remains: the four revision questions were answered on 2026-09-26; logging of ignored scans
(left open by `/speckit-clarify`) is settled in R4 (`DEBUG`).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in earlier specs the conventions in
`CLAUDE.md` are the gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: entry rules in `TagService` / `RegistrationService`, bulk deletes in repositories, the one-off runner in `configuration` next to `ConformityAuthorSchemaUpgrade`; controllers only delegate |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: [contracts/openapi-tag-reference.md](contracts/openapi-tag-reference.md); `listOffListTags` removed from `TagController` with the route |
| Reader devices stay on the stateless token chain, unchanged | `CLAUDE.md` | Pass | Pass: reader schemas untouched, only messages and what is stored differ |
| Routes follow the spec `008` access matrix | `CLAUDE.md` | Pass | Pass: one route removed, none added; `AccessMatrixSecurityTest` loses its row |
| Schema changes through entities with `ddl-auto: update`; one-off changes as startup runners | `CLAUDE.md` | Pass | Pass: `data_upgrade` is an entity; the purge is a runner, idempotent through its marker (R8) |
| Tests run on the `test` profile; `mvn clean install` passes; no reliance on test order | `CLAUDE.md` | Pass | Pass (planned): the purge test uses its own database so alphabetical order can't matter (R10) |

**Irreversible data change**: the purge deletes production rows. Not a gate violation (the spec requires it), but the
plan relies on `deploy/vps/deploy.sh` taking its backup before the new image starts (it stops on backup failure), and
on the whole purge being one transaction.

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/010-controle-tags-reference/
├── spec.md              # Spec, revised 2026-09-26 (clarifications "révision")
├── plan.md              # This file
├── research.md          # R1–R3 kept; R4–R10 for the revision
├── data-model.md        # list, data_upgrade, invariants, entry rules, purge steps
├── quickstart.md        # validation guide for the revision
├── contracts/
│   └── openapi-tag-reference.md   # api.yaml changes from the delivered version
├── checklists/
│   └── requirements.md
└── tasks.md             # first delivery's tasks; regenerated by /speckit-tasks
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml               # scan/batch descriptions; 400 on registration; removals (contract)
src/main/resources/application.yml                # comment on app.tags.reference-list: "refused", not "flagged"
src/main/java/com/rfidback/
├── entity/DataUpgradeEntity.java                 # new: table data_upgrade (name PK, applied_at)
├── repository/DataUpgradeRepository.java         # new
├── configuration/OffListTagPurge.java            # new: one-off purge at startup (R7, R8)
├── repository/TagRepository.java                 # + deleteByIdIn; − findAllWithBucket
├── repository/RecordRepository.java              # + deleteByTagIdIn; − count/last date grouped by tag
├── repository/RecordConformityChangeRepository.java  # + deleteByRecordTagIdIn
├── repository/RegistrationReadRepository.java    # + findDistinctUids, deleteByUidIn
├── service/TagService.java                       # registerScan ignores off-list; registerTagsForBucket 400; − listOffListTags
├── service/RegistrationService.java              # recordRead / recordReads drop off-list; − offList on reads, − offListConfirmed
├── service/RecordService.java                    # − tagOffList, − ReferenceTagList dependency
├── exception/RegistrationNotConfirmedException.java   # − offListTags
├── controller/ApiExceptionHandler.java           # 409 body without offListTags
└── controller/TagController.java                 # − listOffListTags
front/
├── tags.html                                     # − badge, − off-list dialog part, − "Tags hors liste" section
└── reader.html                                   # − "HORS LISTE" badge and CSS
src/test/java/com/rfidback/
├── configuration/OffListTagPurgeTest.java        # new, own H2 database
├── controller/TagOffListApiTest.java             # removed
├── controller/TagScanApiTest.java, RecordApiTest.java, RecordStatsApiTest.java    # in-list UIDs; off-list scan case
├── controller/TagRegistrationApiTest.java, RegistrationApiTest.java, RegistrationReadsApiTest.java  # 400 / filtered reads
├── security/ReaderScanSecurityTest.java          # in-list UIDs
├── security/AccessMatrixSecurityTest.java        # − GET /api/tags/off-list
└── service/TagServiceTest.java, RegistrationServiceTest.java, RecordServiceTest.java   # new rules; off-list cases replaced
CLAUDE.md                                         # reference-list sentence: off-list tags refused and purged once; data_upgrade
```

**Structure Decision**: the existing single Spring Boot project plus the static `/front`; no new module.

## Complexity Tracking

No constitution violations to justify.
