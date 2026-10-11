# Implementation Plan: Scan d'un TAG par un lecteur (déclaration de conformité)

**Branch**: `004-scan-tag-conformite` | **Date**: 2026-10-11 (amendment; first plan 2026-09-24) | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/004-scan-tag-conformite/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

The 2026-09-24 plan is delivered: blank UID → `400`, deduplication within 10 s of a Record's creation, the scan index and the
latency measurement (research R0–R6, kept below). This amendment delivers the 2026-10-11 clarifications:

1. **Sliding window** (FR-008): a read is part of the same pass while the same reader keeps reading the tag less than
   `app.scan.duplicate-window` (10 s) apart. The window starts from the **last read**, not from the Record's creation, so a crate
   left in front of a reader is no longer counted again every 10 s, which is the bug that was reported. The Record stores its last read in `record.last_read_at` (R7).
2. **The tag leaves its bucket** (FR-009): a counted scan unlinks the tag from its bucket and points the tag at the Record that
   counted it (`tag.counted_record_id`). Registering the tag into a bucket again (spec `003`) clears that pointer (R8).
3. **Rescan the same day → KO** (FR-009): a tag whose counting Record was created today (station day) gets a new non-compliant Record
   with no picker, whatever the reader says. A counting Record from an earlier day gives an ordinary Record with no picker (R9).
4. **Two tags, one crate** (FR-010): a tag still in bucket B, read by reader R less than `app.scan.crate-window` (2 s) after R
   created a Record for another tag of bucket B, joins that crate's Record: no new Record, and it leaves the bucket as well. The Record
   stores the bucket its tag left (`record.bucket_id`) (R10).
5. **Tests**: `TagServiceTest` with a fixed `Clock` for each rule and their order; `TagScanApiTest` end to end; the
   registration tests check that registering a tag again resets it.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA (Hibernate 6.6), Spring Security 6.5, OpenAPI Generator 7.6.0 (delegate pattern), Lombok. No new dependency.

**Storage**: H2 file (dev), PostgreSQL (prod), `ddl-auto: update`. Three new nullable columns (`record.last_read_at`,
`record.bucket_id`, `tag.counted_record_id`) and one new index. Adding them needs nothing beyond `ddl-auto` ([data-model.md](data-model.md), R11).

**Testing**: JUnit 5 + Mockito with a fixed `Clock` (`TagServiceTest`), `@SpringBootTest` + MockMvc on the `test` profile with real reader tokens (`TagScanApiTest`, `TagRegistrationApiTest`, `RegistrationApiTest`, `OffListTagPurgeTest`).

**Target Platform**: Linux container on the VPS behind Caddy. RFID readers call `/api/tags/scan` with their `x-api-token`.

**Project Type**: web service (Spring Boot REST API). No front change: the buckets page simply shows fewer tags as crates pass.

**Performance Goals**: SC-004 stays at p95 < 200 ms with 1 to 3 readers. A production scan does at most: the tag by unique UID, its counting Record by primary key (locked), one indexed crate lookup when the tag is still in a bucket, the line's activity, one insert and two updates.

**Constraints**: API-first (`api.yaml` before code). Reader devices change nothing: same endpoint, same response shape. Only `message`, which is already optional, takes two new values. The generated delegate signature stays the same. The station day comes from `APP_STATION_TIME_ZONE` through `LineActivityService.startOfToday()`.

**Scale/Scope**: a handful of readers; crates a few seconds apart on a line. 2 entities changed, 2 repositories changed, 1 service rewritten around one method, 1 one-off runner adjusted, 1 property, `api.yaml` descriptions, tests in 4 classes.

No `NEEDS CLARIFICATION` remains: the 2026-10-11 clarifications settled the open points. Research R7–R13 records the design choices.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so the conventions in `CLAUDE.md` are used as gates, as in earlier specs:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: every rule lives in `TagService.registerScan`; the reset on registration in `TagService.registerTagsForBucket`; lookups in `RecordRepository`; `TagController` unchanged |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: descriptions only ([contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md) §4); the delegate signature doesn't change |
| Reader devices stay on the stateless token chain | `CLAUDE.md` | Pass | Pass: no security change |
| Routes follow the spec `008` access matrix | `CLAUDE.md` | Pass | Pass: no new route |
| Schema changes go through entities with `ddl-auto: update`; a constraint `ddl-auto` can't apply gets a startup upgrade | `CLAUDE.md` | Pass | Pass: only nullable columns and a named index, which `ddl-auto` adds to existing tables (R11) |
| One-off data changes are recorded in `data_upgrade` | `CLAUDE.md` | Pass | Pass: none needed (R12). `OffListTagPurge` only gains a foreign-key step |
| `mvn clean test` and `mvn clean install` pass on the `test` profile | `CLAUDE.md` | Pass | Pass (planned) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/004-scan-tag-conformite/
├── spec.md              # Spec + 2026-09-24 and 2026-10-11 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: R0–R6 (delivered), R7–R13 (this amendment)
├── data-model.md        # Phase 1: Tag/Record columns, scan rules in order, configuration
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── openapi-tag-scan.md   # api.yaml descriptions, response table, reader compatibility
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml             # /tags/scan and ScanTagResponse.message descriptions
src/main/resources/application.yml              # + app.scan.crate-window: 2s; duplicate-window now from the last read
src/main/java/com/rfidback/
├── entity/RecordEntity.java                    # + lastReadAt, + bucket; index idx_record_reader_bucket_date replaces idx_record_reader_tag_date
├── entity/TagEntity.java                       # + countedRecord
├── repository/RecordRepository.java            # + locked crate lookup; − findFirstByReaderAndTagAndCreationDateAfter…
├── repository/TagRepository.java               # + clear countedRecord pointing at records being purged
├── service/TagService.java                     # registerScan: duplicate → crate → rescan → count; registerTagsForBucket clears countedRecord
└── configuration/OffListTagPurge.java          # clears tag.counted_record_id before deleting records
src/test/java/com/rfidback/
├── service/TagServiceTest.java                 # sliding window, leaving the bucket, rescan today / earlier day, crate, rule order
├── controller/TagScanApiTest.java              # end to end on H2: one Record per crate, KO rescan, bucket emptied
├── controller/TagRegistrationApiTest.java      # registering a tag again resets it
└── configuration/OffListTagPurgeTest.java      # purge with a tag pointing at a purged record
```

**Structure Decision**: single Spring Boot project with the existing packages. No new package, no front change.

## Complexity Tracking

No Constitution Check violations.

## Follow-ups outside this plan

- **Data already in buckets at deployment**: tags scanned before the deployment are still in their buckets and have no counting Record, so their next scan counts normally once. No data upgrade is planned (R12); say so if those tags should be emptied out of their buckets at deployment.
- **Spec `006` (buckets page)**: a bucket's tag list now shrinks as its crates are scanned. That is the point of FR-009, but whoever fills the buckets should know.
- **Spec `007` / `014` (dashboard)**: a KO rescan is a Record with no picker, so it shows up in the "no picker" row as a non-compliant crate. Records before the deployment are unchanged.
- **Leftover index**: `idx_record_reader_tag_date` stops being declared but `ddl-auto` never drops it. It's harmless; drop it by hand on PostgreSQL if wanted (`DROP INDEX idx_record_reader_tag_date`).
- **Spec `004` once delivered**: mark FR-008's sliding window, FR-009 and FR-010 delivered with their evidence, and update the status line.
- **Tooling**: this plan was run with `SPECIFY_FEATURE_DIRECTORY` set, so `.specify/feature.json` now points at `004` (it pointed at `014`).
