---

description: "Task list for feature 010 — contrôle des tags par rapport à la liste de référence"
---

# Tasks: Contrôle des tags par rapport à la liste de référence

**Input**: Design documents from `.specify/specs/010-controle-tags-reference/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-tag-reference.md](contracts/openapi-tag-reference.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan names the test classes to add or extend, and every earlier feature ships with its tests.

**Organization**: one phase per user story of the spec: US1 (P1) registration alert and confirmation, US2 (P2) production scans flagged on the line kiosk, US3 (P3) list of known off-list tags. Each story changes only its own part of `api.yaml`, so each can be built, tested and shipped alone once Phase 2 is done.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to
- Paths are relative to the repository root

## Path Conventions

Single Spring Boot project: `src/main/java/com/rfidback/…`, `src/main/resources/…`, `src/test/java/com/rfidback/…`; static front in `front/`. Generated OpenAPI code lands in `target/generated-sources/openapi` and is never edited by hand: change `src/main/resources/openapi/api.yaml`, then run `mvn generate-sources`. When a schema gains a **required** property, the generator adds it to that model's required-args constructor, so every `new Model(...)` call for it must be updated.

---

## Phase 1: Setup

**Purpose**: green starting point, list in place.

- [X] T001 Run `mvn clean test` from the repository root and confirm all tests pass before any change. If it fails with `NoClassDefFoundError` on a bare class name or "Unresolved compilation problem", that's the VS Code Java extension racing Maven (see `CLAUDE.md`): rerun, don't fix code.
- [X] T002 Move the list into the application: `mkdir -p src/main/resources/tags && mv doc/rfid_tag_list.csv src/main/resources/tags/rfid_tag_list.csv` (the file is untracked, so plain `mv`, not `git mv`). Don't change its content (5,008 lines, CRLF). Confirm `doc/rfid_tag_list.csv` no longer exists (research R1: one copy only).
- [X] T003 Add the property to `src/main/resources/application.yml` under the existing `app:` key, after `scan:`, with a comment in the same style:
  ```yaml
  tags:
    # Tags expected on buckets; any other uid is flagged "hors liste" (spec 010). Changed only by a new version.
    reference-list: classpath:tags/rfid_tag_list.csv
  ```
  Don't add it to `application-test.yml`: tests use the shipped list (research R7).

---

## Phase 2: Foundational

**Purpose**: the reference list itself and the test helper. Blocks all three stories.

- [X] T004 Create `src/main/java/com/rfidback/service/ReferenceTagList.java`, a `@Component` (research R1–R3, [data-model.md](data-model.md#reference-tag-list-new-in-memory)):
  - constructor `ReferenceTagList(@Value("${app.tags.reference-list}") Resource resource)` that reads the resource as UTF-8 with a `BufferedReader`, strips a leading `﻿` on the first line, trims each line, skips blank lines, and adds `line.toUpperCase(Locale.ROOT)` to a `HashSet`; stored as `Set.copyOf(...)` in a `private final Set<String> uids`.
  - throws `IllegalStateException` (message naming `resource.getDescription()`) when the resource does not exist, cannot be read (wrap the `IOException`), holds no UID, or has a non-blank trimmed line not matching `^[0-9A-Fa-f]{24}$` (message includes the line number and the bad value). Duplicates are ignored.
  - logs at INFO `Reference tag list loaded: {} uids from {}` (SLF4J, as other classes do).
  - `public boolean contains(String uid)`: `false` for `null` or blank, else `uids.contains(uid.trim().toUpperCase(Locale.ROOT))`.
  - `public boolean isOffList(String uid)`: `!contains(uid)`; `public int size()`.
  - Class Javadoc: one or two lines saying what it is and pointing to spec 010.
- [X] T005 [P] Create malformed fixtures in `src/test/resources/tags/`: `empty-reference-list.csv` (only blank lines), `bad-line-reference-list.csv` (two valid UIDs from the shipped list, then `NOT-A-TAG`), `bom-lowercase-reference-list.csv` (UTF-8 BOM, then `e2806915200050287477d48c` and a blank line, then `E2806915200040287477D049`, CRLF endings).
- [X] T006 Create `src/test/java/com/rfidback/service/ReferenceTagListTest.java` (plain JUnit 5, no Spring), building `ReferenceTagList` from `new ClassPathResource(...)`:
  - shipped file `tags/rfid_tag_list.csv`: `size()` is 5,008; every line of the file, trimmed, is `contains` (SC-002); `contains` of a line in lower case and with surrounding spaces is `true`; `contains("E2000017221101891400A23G")`, `contains(null)`, `contains("  ")` are `false`.
  - `bom-lowercase-reference-list.csv`: size 2, both UIDs found in upper case.
  - `empty-reference-list.csv`, `bad-line-reference-list.csv` (message contains `NOT-A-TAG`), and `new ClassPathResource("tags/missing.csv")`: constructor throws `IllegalStateException`.
  Run `mvn clean test -Dtest=ReferenceTagListTest`.
- [X] T007 [P] Create `src/test/java/com/rfidback/support/ReferenceTagUids.java` (final class, static methods, research R7): loads `tags/rfid_tag_list.csv` from the classpath once (lazy holder), trims lines, skips blanks; `public static String nextInList()` returns the next UID through a static `AtomicInteger` and throws `IllegalStateException` when all 5,008 are used; `public static String offList()` returns `"OFF-LIST-" + UUID.randomUUID()`.
- [X] T008 Run `mvn clean test` and confirm everything still passes (the app context now loads the list at startup; no behavior changed yet).

**Checkpoint**: the list loads in every Spring test; nothing uses it yet.

---

## Phase 3: User Story 1 - Alerte lors de l'enregistrement de tags sur un seau (Priority: P1) 🎯 MVP

**Goal**: off-list tags are flagged on the registration page, and a bucket with off-list tags is saved only after the Administrateur confirms, separately from the "move from another bucket" confirmation (FR-003, FR-004, SC-001, SC-006).

**Independent Test**: `mvn clean test -Dtest='TagServiceTest,RegistrationServiceTest,TagRegistrationApiTest,RegistrationApiTest'` passes, and quickstart steps 1–2 behave as described.

### Contract

- [X] T009 [US1] Edit `src/main/resources/openapi/api.yaml` as in [contracts/openapi-tag-reference.md](contracts/openapi-tag-reference.md#changed-schemas), US1 part only:
  - `RegisterTagsRequest` and `SaveRegistrationSession`: add `offListConfirmed` (boolean, `default: false`, description "Must be true to register tags that are not in the reference list. Otherwise the request is refused with 409 and nothing is saved.").
  - `TagsInOtherBuckets`: add `offListTags` (array of string, description "Tags not in the reference list"), add it to `required`; change the schema description to "409 body when the registration needs confirmation: tags that would be moved from another bucket, and/or tags not in the reference list. Each list is complete; tags may be empty."
  - `RegistrationRead`: add `offList` (boolean, description "True when the uid is not in the reference list") and add it to `required`.
  - `POST /tags/buckets/{bucketNumber}` and `POST /tags/registration-sessions/{sessionId}/save`: append "Tags not in the reference list are registered only when offListConfirmed is true." to the description; set both `409` descriptions to "Some tags need confirmation (move from another bucket, or not in the reference list); nothing is saved".
  Then run `mvn generate-sources` and check `RegistrationRead`'s required-args constructor now takes `offList`.

### Tests (write first, expect failures)

- [X] T010 [US1] In `src/test/java/com/rfidback/service/TagServiceTest.java`: add a `@Mock ReferenceTagList referenceTagList` and pass it to both `new TagService(...)` calls (`setUp()` and `withoutDeduplication` around line 262; constructor changes in T015); by default stub `referenceTagList.isOffList(anyString())` to `false` with `lenient()` so existing cases keep passing. Add cases under a `// --- registerTagsForBucket: reference list (spec 010) ---` comment:
  - off-list tag, `offListConfirmed=false` → `RegistrationNotConfirmedException` with `getOffListTags()` = that UID and `getTags()` empty; `tagRepository.saveAll` and `bucketRepository.save` never called.
  - off-list tag, `offListConfirmed=true` → saved on the bucket.
  - tag in another bucket **and** an off-list tag, `moveConfirmed=true, offListConfirmed=false` → exception carrying both lists, nothing saved; same with `moveConfirmed=false, offListConfirmed=true`; both flags true → saved.
  - in-list tags only, both flags false → saved (no regression).
  Rename the existing `TagsInOtherBucketsException` references to `RegistrationNotConfirmedException` and update calls to the 4-argument `registerTagsForBucket(bucketNumber, uids, moveConfirmed, offListConfirmed)`.
- [X] T011 [P] [US1] In `src/test/java/com/rfidback/service/RegistrationServiceTest.java`: add a `@Mock ReferenceTagList` passed to the `new RegistrationService(...)` call in `setUp()` (constructor changes in T016); add cases: `get()` returns reads whose `offList` follows `referenceTagList.isOffList(uid)`; `save()` passes `offListConfirmed` from `SaveRegistrationSession` to `tagService.registerTagsForBucket(...)`; when `TagService` throws `RegistrationNotConfirmedException`, the session is not deleted. Update existing `save()` verifications to the 4-argument call.
- [X] T012 [P] [US1] In `src/test/java/com/rfidback/controller/TagRegistrationApiTest.java`: replace every made-up UID with `ReferenceTagUids.nextInList()` (import `com.rfidback.support.ReferenceTagUids`) so existing cases keep testing what they did. Add: registering `ReferenceTagUids.offList()` without `offListConfirmed` → `409`, `$.offListTags[0]` is that UID, `$.tags` empty, and the tag does not exist afterwards (`tagRepository.findByUid`); same request with `offListConfirmed: true` → `200`; a request mixing a tag moved from another bucket and an off-list tag with only `moveConfirmed: true` → `409` with both `$.tags` and `$.offListTags` filled.
- [X] T013 [P] [US1] In `src/test/java/com/rfidback/controller/RegistrationApiTest.java`: make `uniqueUid()` return `ReferenceTagUids.nextInList()`. Add: with an open session, an in-list read and an `offList()` read → `GET` session shows `offList` `false` then `true`; `save` without `offListConfirmed` → `409` with `$.offListTags`, and the session is still readable (`GET` → `200`); `save` with `offListConfirmed: true` → `200` and the session is gone (`404`). Also check FR-005: with an open session, a scan of `ReferenceTagUids.offList()` by the registration reader answers `200` with the same body as an in-list read (`isCompliant: true`, `message` = the existing "read kept" message), and creates no Record and no Tag.

### Implementation

- [X] T014 [US1] Rename `src/main/java/com/rfidback/exception/TagsInOtherBucketsException.java` to `RegistrationNotConfirmedException.java` (`git mv`): constructor `(List<TagInOtherBucket> tags, List<String> offListTags)`, both copied with `List.copyOf`, getters `getTags()` / `getOffListTags()`, message "Some tags need confirmation; resend with moveConfirmed and/or offListConfirmed set to true", Javadoc "Registering these tags needs a confirmation that was not given: a move from another bucket and/or tags not in the reference list (409)." In `src/main/java/com/rfidback/controller/ApiExceptionHandler.java`, rename the handler to `handleRegistrationNotConfirmed(RegistrationNotConfirmedException)` and also `body.setOffListTags(exception.getOffListTags())`.
- [X] T015 [US1] In `src/main/java/com/rfidback/service/TagService.java` ([data-model.md](data-model.md#registration-rule-fr-004-r5)):
  - add `ReferenceTagList referenceTagList` to the constructor and a `private final` field.
  - `registerTagsForBucket(Integer, RegisterTagsRequest)` reads `offListConfirmed` like `moveConfirmed` and calls the new 4-argument overload `registerTagsForBucket(Integer bucketNumber, Collection<String> uids, boolean moveConfirmed, boolean offListConfirmed)`, which replaces the 3-argument one.
  - after building `tagsInOtherBuckets`, build `List<String> offListTags` = UIDs of `uniqueUids` where `referenceTagList.isOffList(uid)`, in request order. Replace the current check with: `if ((!tagsInOtherBuckets.isEmpty() && !moveConfirmed) || (!offListTags.isEmpty() && !offListConfirmed)) throw new RegistrationNotConfirmedException(tagsInOtherBuckets, offListTags);` — before any write, so nothing is saved.
  - update the method Javadoc to mention the off-list confirmation.
- [X] T016 [US1] In `src/main/java/com/rfidback/service/RegistrationService.java`: add `ReferenceTagList referenceTagList` to the constructor and a field; in `toModel`, build each read with `new RegistrationRead(read.getUid(), read.getFirstReadAt(), referenceTagList.isOffList(read.getUid()))` (argument order as generated in T009); in `save`, call `tagService.registerTagsForBucket(request.getBucketNumber(), uids, Boolean.TRUE.equals(request.getMoveConfirmed()), Boolean.TRUE.equals(request.getOffListConfirmed()))`. Update the Javadoc of `save` ("On 409 the session stays open") if needed.
- [X] T017 [US1] Run `mvn clean test -Dtest='TagServiceTest,RegistrationServiceTest,TagRegistrationApiTest,RegistrationApiTest'` until green, then `mvn clean test` to catch any other caller of the renamed exception or the old 3-argument method (`grep -rn "TagsInOtherBucketsException\|registerTagsForBucket(" src`).

### Front

- [X] T018 [US1] In `front/tags.html` (FR-003, FR-004):
  - `renderReads()`: when `read.offList`, show `<span class="badge bg-danger"><i class="fas fa-ban me-1"></i>Hors liste</span>`. If the tag has no bucket (`read.bucketNumber == null`), this badge replaces the green "Nouveau"; otherwise it comes after the bucket badge ("Déjà sur ce seau" or "Seau N"), which still shows.
  - Rework the `#moveModal` into a confirmation modal with two sections, each hidden when its list is empty: the existing "Ces tags seront retirés de leur seau actuel…" list (`#moveSection`, `#moveList`), and a new `#offListSection` with the text "Ces tags ne figurent pas dans la liste de référence des tags achetés. Vérifiez qu'il ne s'agit pas d'un tag étranger avant de confirmer :" and `<ul id="offListList">`. Title: "Confirmation nécessaire"; confirm button: "Confirmer et enregistrer".
  - `saveSession(moveConfirmed, offListConfirmed)` sends both flags. On `409`, fill both lists from `conflict.tags` and `conflict.offListTags`, and store `pendingMove = conflict.tags.length > 0` and `pendingOffList = conflict.offListTags.length > 0`. The confirm button (`confirmMove()`, rename to `confirmSave()`) calls `saveSession(pendingMove, pendingOffList)`, so each flag is sent only for the kind shown to the Administrateur. Update the other `saveSession(...)` call site(s) to pass `false, false`.
  Check it by hand with quickstart steps 1–2.

**Checkpoint**: US1 complete and shippable on its own (MVP).

---

## Phase 4: User Story 2 - Alerte lors d'un scan en production (Priority: P2)

**Goal**: production scans are unchanged, and a record of an off-list tag shows "hors liste" on the line kiosk (FR-005–FR-008, SC-003).

**Independent Test**: `mvn clean test -Dtest='RecordServiceTest,RecordApiTest,TagScanApiTest,ReaderScanSecurityTest'` passes, and quickstart step 3 behaves as described.

- [X] T019 [US2] Edit `src/main/resources/openapi/api.yaml`: add `tagOffList` (boolean, description "True when the record's tag is not in the reference list") to `RecordSummary` and to its `required` list. Leave `ScanTagRequest` / `ScanTagResponse` untouched (FR-006). Run `mvn generate-sources`.
- [X] T020 [P] [US2] In `src/test/java/com/rfidback/service/RecordServiceTest.java`: add a `@Mock ReferenceTagList` and pass it to the `new RecordService(...)` call in `setUp()` (constructor changes in T022; the new field goes last, so it is the last argument); add a case where `listLatestRecordsForReader` returns two records, one whose tag UID the mock reports off-list, and assert `tagOffList` is `true` only for that one.
- [X] T021 [P] [US2] In `src/test/java/com/rfidback/controller/RecordApiTest.java`: add a case that scans `ReferenceTagUids.nextInList()` and `ReferenceTagUids.offList()` with the production reader (existing `scan(...)` helper), then `GET /api/records/readers/{uid}` shows `tagOffList` `false` / `true` for the matching `tagUid` and `isCompliant` as sent (the Record is created as today). Also assert the off-list scan answered `200` with the same fields as the in-list one (`uid`, `isCompliant`, `processedAt`).
- [X] T022 [US2] In `src/main/java/com/rfidback/service/RecordService.java`: add a `private final ReferenceTagList referenceTagList` field after the existing ones (the class uses `@RequiredArgsConstructor`, so it becomes the last constructor argument); in `toRecordSummary`, `model.setTagOffList(tag == null || referenceTagList.isOffList(tag.getUid()))`. Do not touch `TagService.registerScan`. Run the US2 test command above until green.
- [X] T023 [US2] In `front/reader.html` `renderBoxes(records)`: when `record.tagOffList`, add the class `off-list` on the box container and a `<div class="off-list-badge">HORS LISTE</div>` inside `.box-card` under the timestamp; add CSS next to the existing `.box-container.defect` rules (red outline or a red label readable from a distance, consistent with the page's style). Include `tagOffList` in nothing else: `currentRecordsHash` already covers the whole record. Check it by hand with quickstart step 3.

**Checkpoint**: US2 works with or without US1.

---

## Phase 5: User Story 3 - Repérer les tags déjà enregistrés hors liste (Priority: P3)

**Goal**: an Administrateur sees every known off-list tag with its bucket, read count and last read (FR-009, SC-005).

**Independent Test**: `mvn clean test -Dtest='TagOffListApiTest,AccessMatrixSecurityTest'` passes, and quickstart step 4 behaves as described.

- [X] T024 [US3] Edit `src/main/resources/openapi/api.yaml` as in [contracts/openapi-tag-reference.md](contracts/openapi-tag-reference.md#new-route-get-tagsoff-list): add the path `/tags/off-list` (`get`, `operationId: listOffListTags`, `tags: [Tag]`) right after `/tags/buckets/{bucketNumber}`, and the schemas `OffListTag` and `OffListTagsList` next to `TagsInOtherBuckets`. Run `mvn generate-sources` and check `TagApiDelegate` has `listOffListTags()`.
- [X] T025 [P] [US3] Create `src/test/java/com/rfidback/controller/TagOffListApiTest.java`, modelled on `TagRegistrationApiTest` (same class annotations, Administrateur session helper). With an in-list tag on a bucket and two off-list tags — one registered on bucket N with `offListConfirmed: true` and scanned once each by two different production readers (so the duplicate window doesn't merge them), one created by a single production scan — `GET /api/tags/off-list` → `200`, `$.referenceListSize` = 5008, the two off-list UIDs present with `bucketNumber` N / null, `recordCount` 2 / 1, `lastRecordAt` set; the in-list UID absent. An Opérateur session → `403`; a reader token (`x-api-token`) → `403`. Because the database is shared with other test classes, assert on the presence/absence of this test's UIDs, not on the list size.
- [X] T026 [P] [US3] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java`: add `new Route(HttpMethod.GET, "/api/tags/off-list", null, ADMIN_ONLY)` next to the other `/api/tags` routes.
- [X] T027 [P] [US3] In `src/main/java/com/rfidback/repository/TagRepository.java`: add `@Query("select t from TagEntity t left join fetch t.bucket") List<TagEntity> findAllWithBucket();`.
- [X] T028 [P] [US3] In `src/main/java/com/rfidback/repository/RecordRepository.java`: add a projection interface `TagRecordStats { UUID getTagId(); long getRecordCount(); OffsetDateTime getLastRecordAt(); }` (nested in the repository, or in `repository/` if the codebase already keeps projections separate) and `@Query("select r.tag.id as tagId, count(r) as recordCount, max(r.creationDate) as lastRecordAt from RecordEntity r where r.tag.id in :tagIds group by r.tag.id") List<TagRecordStats> findStatsByTagIds(@Param("tagIds") Collection<UUID> tagIds);`.
- [X] T029 [US3] In `src/main/java/com/rfidback/service/TagService.java`, add `@Transactional(readOnly = true) public OffListTagsList listOffListTags()` ([data-model.md](data-model.md#off-list-tag-entry-new-api-shape-fr-009)): `tagRepository.findAllWithBucket()`, keep tags where `referenceTagList.isOffList(uid)`; if none, return an empty list without querying records; else one `recordRepository.findStatsByTagIds(ids)` call mapped by tag id; build `OffListTag` entries (`recordCount` 0 and `lastRecordAt` null for a tag never read); sort by `lastRecordAt` descending with nulls last, then `uid`; set `referenceListSize` to `referenceTagList.size()`.
- [X] T030 [US3] In `src/main/java/com/rfidback/controller/TagController.java`, implement `listOffListTags()` → `ResponseEntity.ok(tagService.listOffListTags())`. Run `mvn clean test -Dtest='TagOffListApiTest,AccessMatrixSecurityTest'` until green.
- [X] T031 [US3] In `front/tags.html`, add a card "Tags hors liste" below the registration area: a "Charger" / "Actualiser" button calling `apiFetch('/tags/off-list')`, a line "Liste de référence : N tags" from `referenceListSize`, and a table with columns Tag (`.tag-uid`), Seau (number or "—"), Lectures, Dernière lecture (`formatTime` or "—"); when empty, a row "Aucun tag hors liste." Escape UIDs with the existing `escapeHtml`. Check it by hand with quickstart step 4.

**Checkpoint**: all three stories work.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T032 [P] In `CLAUDE.md`, section "Persistence", add one sentence: the reference tag list (spec 010) is `src/main/resources/tags/rfid_tag_list.csv`, loaded at startup by `ReferenceTagList` (startup fails on a missing, empty or malformed file); tags not in it are flagged "hors liste", and changing it means a new version.
- [X] T033 [P] In `.specify/specs/010-controle-tags-reference/spec.md`, set **Status** to "Delivered (<date>)" with a short note of what shipped, as specs 004/005 do. In `.specify/specs/003-enregistrement-tags-seau/spec.md` and `.specify/specs/004-scan-tag-conformite/spec.md`, add one line each where registration confirmation / tag creation is described, pointing to spec `010` for the off-list check. Check that spec.md no longer points to `doc/rfid_tag_list.csv` as the current location (Contexte, FR-001, Assumptions).
- [X] T034 Run `mvn clean install` (full build, CI order), then the manual steps 1–5 of [quickstart.md](quickstart.md), including a boot with an empty list (`APP_TAGS_REFERENCELIST=file:/tmp/empty.csv`) that must fail.
- [X] T035 Rerun the scan latency measurement of spec `004` ([quickstart](../004-scan-tag-conformite/quickstart.md)) and confirm p95 < 200 ms (SC-004); note the result in this spec's quickstart.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies. T002 before T003 (the property points at the moved file).
- **Foundational (Phase 2)**: after Setup. T004 blocks everything that injects `ReferenceTagList`; T006 needs T004 and T005; T007 needs T002.
- **US1 (Phase 3)**, **US2 (Phase 4)**, **US3 (Phase 5)**: each needs only Phase 2. They all edit `api.yaml`, and US1 and US3 both edit `TagService.java` and `front/tags.html`, so run them one after another in priority order unless those files are merged carefully.
- **Polish (Phase 6)**: after the stories you ship.

### Within Each Story

- Contract (`api.yaml` + `mvn generate-sources`) first: generated models and delegates change.
- Tests next (they fail), then implementation until they pass, then the front page.

### User Story Dependencies

- **US1 (P1)**: none beyond Phase 2.
- **US2 (P2)**: none beyond Phase 2; its tests use `ReferenceTagUids` only.
- **US3 (P3)**: none beyond Phase 2. T025 registers an off-list tag with `offListConfirmed: true`: without US1, that flag doesn't exist yet and the tag is registered anyway, so the test still holds; with US1, the flag is required.

## Parallel Example: User Story 1

```text
After T009 (contract) and T010 (TagServiceTest):
  T011 RegistrationServiceTest     src/test/java/com/rfidback/service/RegistrationServiceTest.java
  T012 TagRegistrationApiTest      src/test/java/com/rfidback/controller/TagRegistrationApiTest.java
  T013 RegistrationApiTest         src/test/java/com/rfidback/controller/RegistrationApiTest.java
```

## Parallel Example: User Story 3

```text
After T024 (contract):
  T025 TagOffListApiTest           src/test/java/com/rfidback/controller/TagOffListApiTest.java
  T026 AccessMatrixSecurityTest    src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java
  T027 TagRepository               src/main/java/com/rfidback/repository/TagRepository.java
  T028 RecordRepository            src/main/java/com/rfidback/repository/RecordRepository.java
```

## Implementation Strategy

### MVP First (User Story 1 only)

1. Phase 1 and Phase 2: the list loads and is tested (SC-002).
2. Phase 3: off-list tags can't reach a bucket without confirmation (SC-001, SC-006).
3. Stop and validate: US1 test command and quickstart steps 1–2. Shippable on its own.

### Incremental Delivery

1. Setup + Foundational → list loaded, startup guarded.
2. + US1 → registration protected (MVP).
3. + US2 → off-list reads visible on the line.
4. + US3 → clean-up view for tags registered before the feature.
5. Polish → docs, full build, latency check.
