---

description: "Task list for feature 010 (révision) — tags hors liste écartés sans affichage, purge unique"
---

# Tasks: Contrôle des tags par rapport à la liste de référence (révision)

**Input**: Design documents from `.specify/specs/010-controle-tags-reference/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-tag-reference.md](contracts/openapi-tag-reference.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan names the test classes to add, change or remove (research R10), and every feature in this repo ships with its tests.

**Organization**: one phase per user story of the revised spec: US1 (P1) off-list tags dropped at registration and refused on a bucket, US2 (P2) off-list production scans ignored, US3 (P3) one-off purge of stored off-list data and removal of the off-list tags page. Each story edits its own part of `api.yaml` and its own methods; US1 and US2 both edit `TagService.java` (different methods), so they are done one after the other.

**History**: the first delivery's tasks (T001–T040, all done, PRs #34 and #36) are in git history (`git log -p -- .specify/specs/010-controle-tags-reference/tasks.md`). This list starts again at T001.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to
- Paths are relative to the repository root

## Path Conventions

- Backend: `src/main/java/com/rfidback/`, tests `src/test/java/com/rfidback/`, API `src/main/resources/openapi/api.yaml`
- Front: `front/`
- Run tests on the `test` profile only (`mvn clean test ...`); regenerate sources after every `api.yaml` edit (`mvn generate-sources`, or any `mvn` build)

---

## Phase 1: Setup

**Purpose**: start from a green build on the branch that carries spec 011.

- [X] T001 On branch `010-rejet-tags-hors-liste`, run `mvn clean test` and confirm every test passes before any change (baseline, includes spec 011's `RegistrationReadsApiTest`)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: nothing new blocks the stories. `ReferenceTagList.isOffList(uid)` (last-12-character rule, research R3) and the test helper `src/test/java/com/rfidback/support/ReferenceTagUids.java` (`nextInList()`, `nextInListAsReaderSends()`, `offList()`) already exist and are reused as they are.

**Checkpoint**: T001 green → user stories can start.

---

## Phase 3: User Story 1 - Tags hors liste écartés à l'enregistrement sur un seau (Priority: P1) 🎯 MVP

**Goal**: an off-list read never enters a registration session (tag by tag or batch) and never shows on `tags.html`; a bucket registration containing an off-list UID is refused whole with `400`, with no confirmation to override it (FR-003, FR-004, FR-005, SC-001, SC-006; research R5, R6).

**Independent Test**: open a registration session, send one in-list and one off-list UID tag by tag and as a batch: only the in-list one is in the session and on the page; saving registers it with no confirmation dialog. `POST /api/tags/buckets/{n}` with an off-list UID answers `400` naming it, even with `moveConfirmed: true` (quickstart steps 3–4).

### Tests for User Story 1 ⚠️ write first, make sure they fail

- [X] T002 [P] [US1] In `src/test/java/com/rfidback/service/TagServiceTest.java`, replace the four off-list confirmation tests (`registerTagsForBucket_offListTag_withoutConfirmation_throwsConflictAndWritesNothing`, `..._offListTag_withConfirmation_registersIt`, `..._moveAndOffList_eachConfirmationCoversOnlyItsKind`, `..._moveAndOffList_bothConfirmed_registersAll`) with: (a) an off-list UID (`when(referenceTagList.isOffList("OFF")).thenReturn(true)`) throws `ResponseStatusException` with status `400` whose reason contains `"Tags not in the reference list"` and `"OFF"`, and nothing is saved (`verify(tagRepository, never()).save(any())` / `saveAll`, `bucketRepository` untouched); (b) an off-list UID plus a tag in another bucket with `moveConfirmed = true` still throws the `400`, not `RegistrationNotConfirmedException`; (c) a move without off-list UIDs still throws `RegistrationNotConfirmedException` listing the moved tag. Drop the `exception.getOffListTags()` assertion near line 372. Calls use the new 3-argument `registerTagsForBucket(bucketNumber, uids, moveConfirmed)`
- [X] T003 [P] [US1] In `src/test/java/com/rfidback/service/RegistrationServiceTest.java`: remove `get_flagsOffListReads` and `save_passesOffListConfirmationToTagService`; turn `save_offListNotConfirmed_keepsSession` into `save_offListTag_keepsSession` (TagService mock throws `ResponseStatusException(BAD_REQUEST, ...)` → rethrown, session not deleted); add `recordRead_offListUid_isIgnoredWithoutTouchingSession` (message `"Registration read ignored: tag not in reference list"`, `isCompliant` true, no `readRepository.saveAndFlush`, no `sessionRepository.touch`, no `findByReader`) and `recordReads_dropsOffListUids_countsThemAsReceivedOnly` (2 in-list + 1 off-list → `receivedCount` 3, `addedCount` 2, only the in-list UIDs passed to `saveAllAndFlush`) and `recordReads_onlyOffListUids_returns200WithNothingAdded`. Adjust `save` verifications to the 3-argument `tagService.registerTagsForBucket`
- [X] T004 [P] [US1] In `src/test/java/com/rfidback/controller/TagRegistrationApiTest.java`, replace `register_offListTag_returns409UntilConfirmed` with `register_offListTag_returns400NamingIt_andSavesNothing` (also sending `"offListConfirmed": true` must still give `400`: unknown property ignored), and replace `register_moveAndOffList_moveConfirmationAloneIsNotEnough` with `register_moveAndOffList_returns400EvenWithMoveConfirmed` (`$.detail` contains the off-list UID; the moved tag stays on its first bucket). Keep every other test on `ReferenceTagUids.nextInList()` UIDs; update the class Javadoc (no more off-list confirmation)
- [X] T005 [P] [US1] In `src/test/java/com/rfidback/controller/RegistrationApiTest.java`, replace `offListRead_isFlaggedAndNeedsConfirmationToSave` with `offListRead_isNotKept_andSaveRegistersOnlyInListTags`: scan an in-list and an off-list UID with the registration reader (the off-list answer carries message `"Registration read ignored: tag not in reference list"`), `GET` the session shows only the in-list read (`$.reads.length()` 1, no `offList` field: `jsonPath("$.reads[0].offList").doesNotExist()`), save without any flag → `200`, off-list UID has no Tag. Remove the `$.reads[0].offList` assertion near line 256 and the off-list mention in the class Javadoc
- [X] T006 [P] [US1] In `src/test/java/com/rfidback/controller/RegistrationReadsApiTest.java`, in `batch_keepsEveryTagInOrder_touchesSession_andCreatesNothingElse` keep the 4 in-list UIDs + 1 off-list: expect `receivedCount` 5, `addedCount` 4, `readUids(session)` equal to the 4 in-list UIDs in order, and the polled session's `$.reads[*].uid` equal to those 4 (drop `$.reads[4].offList`); add `batch_ofOffListUidsOnly_keepsNothing` (`200`, `sessionOpen` true, `addedCount` 0, session has no read). Update the comment near line 346

### Implementation for User Story 1

- [X] T007 [US1] In `src/main/resources/openapi/api.yaml`, per [contracts](contracts/openapi-tag-reference.md): remove `offListConfirmed` from `RegisterTagsRequest` (~line 1120) and `SaveRegistrationSession` (~line 1311); remove `offListTags` from `TagsInOtherBuckets` (~line 1174) and from its `required`, restore its description to "409 body: tags linked to another bucket"; remove `offList` from `RegistrationRead` (~line 1248) and from its `required`; in `POST /tags/buckets/{bucketNumber}` (~line 226) and `POST /tags/registration-sessions/{sessionId}/save` (~line 397) replace the off-list sentence with "A uid not in the reference list refuses the whole request with 400, naming those uids; nothing is saved (on save, the session stays open)." and set the `409` description to "Some tags are linked to another bucket; nothing is saved. Resend with moveConfirmed true to move them."; in `POST /tags/registration-reads` add "Uids not in the reference list are never kept; they still count in receivedCount." and update `RegistrationReadsResponse.addedCount`'s description; in `POST /tags/scan` add the ENREGISTREMENT half of the reference-list sentence (message "Registration read ignored: tag not in reference list"). Run `mvn generate-sources`
- [X] T008 [US1] In `src/main/java/com/rfidback/exception/RegistrationNotConfirmedException.java`, remove `offListTags` (field, constructor parameter, getter); message back to "Some tags are linked to another bucket; resend with moveConfirmed set to true". In `src/main/java/com/rfidback/controller/ApiExceptionHandler.java` (~line 60) drop `body.setOffListTags(...)`
- [X] T009 [US1] In `src/main/java/com/rfidback/service/TagService.java`: make `registerTagsForBucket(Integer bucketNumber, List<String> uids, boolean moveConfirmed)` (drop `offListConfirmed`); after the existing input checks and before computing `tagsInOtherBuckets`, collect `uniqueUids` that are `referenceTagList.isOffList(...)` and, if any, throw `new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tags not in the reference list: " + String.join(", ", offList))` before any write; the conflict condition becomes `!tagsInOtherBuckets.isEmpty() && !moveConfirmed` → `new RegistrationNotConfirmedException(tagsInOtherBuckets)`. In the request-level overload (~line 139) stop reading `request.getOffListConfirmed()`. Update the method Javadoc (spec 010 révision, FR-004)
- [X] T010 [US1] In `src/main/java/com/rfidback/service/RegistrationService.java`: add `static final String READ_OFF_LIST = "Registration read ignored: tag not in reference list"`; in `recordRead`, right after the blank-uid check, `if (referenceTagList.isOffList(uid)) return scanResponse(uid, now, READ_OFF_LIST);` (before `findByReader`); in `recordReads`, after all validation, build `uids` from `distinct` minus off-list UIDs and pass `distinct.size()` as the received count to `storeReads` (change its signature to `storeReads(reader, uids, receivedCount)` and use `receivedCount` in both `readsResponse` calls); in `save` call the 3-argument `tagService.registerTagsForBucket` and update the Javadoc ("On 400 or 409 the session stays open"); in `toModel` build `new RegistrationRead(read.getUid(), read.getFirstReadAt())` without the off-list flag
- [X] T011 [US1] In `front/tags.html`: remove the "Hors liste" badge in the reads table (~lines 341–343, keep the bucket badge logic); remove `offListSection` / `offListList` from the confirmation modal (~lines 170–173) and `pendingOffList` (~line 195); `saveSession(moveConfirmed)` sends only `{ bucketNumber, moveConfirmed }` and the `409` handler reads only `conflict.tags` (~lines 354–408); make sure a `400` on save shows the server's `detail` through the existing `showMessage('danger', ...)` path. Rename the modal comment (~line 157) to "CONFIRMATION (DÉPLACEMENT D'UN AUTRE SEAU)"
- [X] T012 [US1] Run `mvn clean test -Dtest='TagServiceTest,RegistrationServiceTest,TagRegistrationApiTest,RegistrationApiTest,RegistrationReadsApiTest'` and fix until green; then check the page by hand per [quickstart](quickstart.md) step 3

**Checkpoint**: registration can no longer bring an off-list tag into the database; the page never shows one.

---

## Phase 4: User Story 2 - Tags hors liste ignorés lors d'un scan en production (Priority: P2)

**Goal**: a `PRODUCTION` scan of an off-list UID answers `200` with `isCompliant: true` and "Tag not in reference list, ignored", creates no Tag and no Record, and nothing shows on `reader.html` (FR-006, FR-010, SC-003, SC-004; research R4, R9).

**Independent Test**: scan `E2000017221101891400A23G` with a production token: `200`, the message, no tag, no record, no box on `reader.html`; scan `E2806915000040287477C993`: record created as before (quickstart step 2).

### Tests for User Story 2 ⚠️ write first, make sure they fail

- [X] T013 [P] [US2] In `src/test/java/com/rfidback/controller/TagScanApiTest.java`, replace the made-up UIDs (`"X-2"`, `"D-1"`, etc.) with `ReferenceTagUids.nextInList()` (the trim test sends `"  " + uid + "  "` and expects `uid`); add `scan_offListUid_returns200Ignored_andCreatesNothing`: production reader, `ReferenceTagUids.offList()`, `isCompliant: false` → `200`, `$.isCompliant` true, `$.message` `"Tag not in reference list, ignored"`, `$.uid` the UID, `tagRepository.findByUid(uid)` empty, `recordRepository.count()` unchanged
- [X] T014 [P] [US2] In `src/test/java/com/rfidback/controller/RecordApiTest.java`, replace `"LIST-" + i` UIDs (~line 99) with `ReferenceTagUids.nextInList()`; remove `listLatest_flagsOffListTags_andOffListScanIsRecordedAsUsual`; in `listLatest_uidInTheFormTheReadersSend_isNotFlagged` rename to `scan_uidInTheFormTheReadersSend_isRecorded` and assert the record is listed (drop the `tagOffList` assertion). Any other scan through the API in this class uses `nextInList()`
- [X] T015 [P] [US2] In `src/test/java/com/rfidback/controller/RecordStatsApiTest.java`, create `dialloTag`, `moreauTag` (and `looseTag`) with `ReferenceTagUids.nextInList()` instead of `"STATS-DIALLO"` / `"STATS-MOREAU"` / `"STATS-LOOSE"` (~lines 101–103), and in `aScanStaysWithThePickerOfItsBucketAtScanTime` (~line 208) send `dialloTag.getUid()` in the scan body
- [X] T016 [P] [US2] In `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java`, make the production-scan request builder `scan()` use a `ReferenceTagUids.nextInList()` UID, so the security cases keep exercising a scan that is stored
- [X] T017 [P] [US2] In `src/test/java/com/rfidback/service/TagServiceTest.java`, add `registerScan_offListUid_returnsIgnored_withoutTouchingTheDatabase` (`isOffList` true → response `isCompliant` true, message `TagService.OFF_LIST_SCAN_IGNORED`, `verifyNoInteractions(tagRepository, recordRepository, recordConformityChangeRepository)`); a blank UID still throws before the list is consulted
- [X] T018 [P] [US2] In `src/test/java/com/rfidback/service/RecordServiceTest.java`, remove `list_flagsRecordsOfOffListTags` and the `ReferenceTagList` mock/constructor argument (~lines 48, 58–60, 148–161)

### Implementation for User Story 2

- [X] T019 [US2] In `src/main/resources/openapi/api.yaml`: in `POST /tags/scan` complete the reference-list sentence per [contracts](contracts/openapi-tag-reference.md) (PRODUCTION: message "Tag not in reference list, ignored", nothing created); remove `tagOffList` from `RecordSummary` (~line 1388) and from its `required`. Run `mvn generate-sources`
- [X] T020 [US2] In `src/main/java/com/rfidback/service/TagService.java`, add `static final String OFF_LIST_SCAN_IGNORED = "Tag not in reference list, ignored"` next to `DUPLICATE_READ_IGNORED`; in `registerScan`, right after `Assert.isTrue(StringUtils.hasText(uid), ...)`, if `referenceTagList.isOffList(uid)`: `log.debug("Scan of {} by reader {} ignored: not in the reference list", uid, reader.getName())` and return a `ScanTagResponse` with `uid`, `isCompliant` true, `processedAt` `OffsetDateTime.now(clock)`, message `OFF_LIST_SCAN_IGNORED` (add `@Slf4j` if the class has no logger). Update the method Javadoc (FR-006 révision)
- [X] T021 [US2] In `src/main/java/com/rfidback/service/RecordService.java`, remove `model.setTagOffList(...)` (~line 75), the `ReferenceTagList` field and constructor parameter and its import
- [X] T022 [P] [US2] In `front/reader.html`, remove the `.off-list` / `.off-list-badge` CSS (~lines 73–74), the `record.tagOffList ? 'off-list' : ''` class (~line 243) and the `HORS LISTE` badge (~line 254)
- [X] T023 [US2] Run `mvn clean test -Dtest='TagScanApiTest,RecordApiTest,RecordStatsApiTest,ReaderScanSecurityTest,TagServiceTest,RecordServiceTest'` and fix until green

**Checkpoint**: no request can create an off-list tag any more (US1 + US2); the line screen never shows "hors liste".

---

## Phase 5: User Story 3 - Suppression des tags hors liste déjà en base (Priority: P3)

**Goal**: at the first startup of this version, off-list tags are deleted with their records, the conformity history of those records, their bucket link and off-list registration reads, in one transaction, and a marker makes sure it never runs again; the off-list tags page, which only served this clean-up, is removed (FR-007, FR-008, FR-009, SC-005; research R7, R8, R9).

**Independent Test**: `OffListTagPurgeTest` on its own database: seeded off-list data disappears, in-list data and buckets stay, a second run deletes nothing (quickstart step 1 by hand).

### Tests for User Story 3 ⚠️ write first, make sure they fail

- [X] T024 [P] [US3] Create `src/test/java/com/rfidback/configuration/OffListTagPurgeTest.java`: `@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:off-list-purge;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")`, `@ActiveProfiles("test")`, own database so deletions can't touch other classes' data (research R10). Test `run_deletesOffListDataOnly_thenNeverAgain`: delete the marker row (`dataUpgradeRepository.deleteById("010-off-list-tag-purge")`); seed through repositories a bucket with one in-list tag (`ReferenceTagUids.nextInList()`) and one off-list tag (`ReferenceTagUids.offList()`), a reader, one record per tag, one `RecordConformityChangeEntity` per record (author = a saved user), a registration session with one in-list and one off-list `RegistrationReadEntity`; call `offListTagPurge.run(null)`; assert the off-list tag, its record, its conformity change and the off-list read are gone, and the in-list tag (still on the bucket), its record, its change, the in-list read, the bucket, the reader and the session remain; the marker exists. Then seed another off-list tag with a record, call `run(null)` again, assert it is still there (marker present, clarification révision Q4). Second test `run_onEmptyDatabase_writesMarker`
- [X] T025 [P] [US3] Delete `src/test/java/com/rfidback/controller/TagOffListApiTest.java`; in `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java` remove the `GET /api/tags/off-list` route (~line 83)

### Implementation for User Story 3

- [X] T026 [P] [US3] Create `src/main/java/com/rfidback/entity/DataUpgradeEntity.java` (`@Entity @Table(name = "data_upgrade")`, `@Id @Column(length = 100) String name`, `@Column(nullable = false) OffsetDateTime appliedAt`, Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` like the other entities) and `src/main/java/com/rfidback/repository/DataUpgradeRepository.java` (`JpaRepository<DataUpgradeEntity, String>`), per [data-model](data-model.md)
- [X] T027 [P] [US3] Add the bulk deletes (each `@Modifying @Query`, returning `int`): `RecordConformityChangeRepository.deleteByRecordTagIdIn(Collection<UUID> tagIds)` = `delete from RecordConformityChangeEntity c where c.record.id in (select r.id from RecordEntity r where r.tag.id in :tagIds)` in `src/main/java/com/rfidback/repository/RecordConformityChangeRepository.java`; `RecordRepository.deleteByTagIdIn` = `delete from RecordEntity r where r.tag.id in :tagIds` in `src/main/java/com/rfidback/repository/RecordRepository.java`; `TagRepository.deleteByIdIn` = `delete from TagEntity t where t.id in :ids` in `src/main/java/com/rfidback/repository/TagRepository.java`; `RegistrationReadRepository.findDistinctUids()` = `select distinct r.uid from RegistrationReadEntity r` and `deleteByUidIn(Collection<String> uids)` = `delete from RegistrationReadEntity r where r.uid in :uids` in `src/main/java/com/rfidback/repository/RegistrationReadRepository.java`
- [X] T028 [US3] Create `src/main/java/com/rfidback/configuration/OffListTagPurge.java` (`@Slf4j @Component @RequiredArgsConstructor`, `implements ApplicationRunner`, class comment in the style of `ConformityAuthorSchemaUpgrade`: one-off, spec 010 révision, remove once a migration tool exists, the marker stays). `static final String MARKER = "010-off-list-tag-purge"`. `@Override @Transactional public void run(ApplicationArguments args)`: if `dataUpgradeRepository.existsById(MARKER)` → `log.info("Off-list tag purge already applied")` and return; else collect ids of `tagRepository.findAll()` whose uid `referenceTagList.isOffList(...)`; for each chunk of 1,000 ids call, in this order, `recordConformityChangeRepository.deleteByRecordTagIdIn`, `recordRepository.deleteByTagIdIn`, `tagRepository.deleteByIdIn`, summing the counts; delete `registrationReadRepository.findDistinctUids()` filtered by `isOffList` with `deleteByUidIn` (chunked the same way); save `new DataUpgradeEntity(MARKER, OffsetDateTime.now(clock))`; `log.info("Off-list tag purge (spec 010): {} tags, {} records, {} conformity changes, {} registration reads deleted", ...)`. No try/catch: a failure rolls back and stops startup (research R7)
- [X] T029 [US3] In `src/main/resources/openapi/api.yaml`, remove the `/tags/off-list` path (~line 261) and the `OffListTag` / `OffListTagsList` schemas (~lines 1184–1222). Run `mvn generate-sources`
- [X] T030 [US3] Remove the off-list listing code: `listOffListTags` from `src/main/java/com/rfidback/controller/TagController.java` (~lines 43–44, and the `OffListTagsList` import); `listOffListTags` and its now-unused imports (`OffListTag`, `OffListTagsList`, `TagRecordsView`, `Comparator`, `HashMap`… only if unused) from `src/main/java/com/rfidback/service/TagService.java`; `findAllWithBucket` from `src/main/java/com/rfidback/repository/TagRepository.java` (~line 26); `findStatsByTagIds` and the `TagRecordsView` projection from `src/main/java/com/rfidback/repository/RecordRepository.java` (~lines 81–83)
- [X] T031 [US3] In `front/tags.html`, remove the "TAGS HORS LISTE" card (~lines 126–155) and `loadOffListTags` (~lines 433–460)
- [X] T032 [US3] Run `mvn clean test -Dtest='OffListTagPurgeTest,AccessMatrixSecurityTest,TagServiceTest'` and fix until green

**Checkpoint**: after deployment the database holds no off-list tag, and nothing in the application refers to off-list tags any more.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T033 [P] Run `grep -rniE "offlist|off-list|off_list|hors liste" src front` and remove any leftover (comments, Javadoc, test names); only `ReferenceTagList`, `OffListTagPurge`, `ReferenceTagUids.offList()` and the "not in reference list" messages may remain
- [X] T034 [P] In `src/main/resources/application.yml` (~line 23), change the comment to "Tags expected on buckets; any other uid is refused and never stored (spec 010). Changed only by a new version."
- [X] T035 [P] In `CLAUDE.md` (Persistence paragraph), replace "tags not in it are flagged \"hors liste\"" with "a uid not in it is never stored: scans and registration reads of it are ignored, a bucket registration with it is refused (`400`)"; add after the `ConformityAuthorSchemaUpgrade` sentence: "One-off data changes run as startup runners recorded in table `data_upgrade` so they run once (`configuration/OffListTagPurge`, spec 010 révision: deleted the off-list tags already stored)."
- [X] T036 Run `mvn clean install` (full build, test classes in alphabetical order as on CI) and fix until green
- [X] T037 Walk through [quickstart](quickstart.md) steps 1–5 on the dev profile (step 1 on a copy of `./data/rfidbackdb.mv.db`, never the original); check the purge `INFO` line and that a restart logs "already applied"
- [X] T038 Update the spec's **Status** line in `.specify/specs/010-controle-tags-reference/spec.md` to say the revision is implemented (date, this task range)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none.
- **Foundational (Phase 2)**: empty; T001 green is enough.
- **US1 (Phase 3)**: after T001.
- **US2 (Phase 4)**: after US1 in practice — T020 and T009 both edit `TagService.java`, and T019 and T007 both edit `api.yaml`. Functionally independent: US2 could ship first if done alone.
- **US3 (Phase 5)**: after US1 and US2. Functionally it needs them: purging while scans and registrations can still create off-list tags would leave new ones behind. T029/T030 also edit `api.yaml` and `TagService.java`.
- **Polish (Phase 6)**: after all stories.

### Within Each User Story

- Tests first (they must fail), then `api.yaml` → `mvn generate-sources` → services → front → story test run.
- US1: T007 before T008–T011 (generated models change); T008 before T009 (constructor of the exception).
- US2: T019 before T021 (`RecordSummary` loses `tagOffList`).
- US3: T026, T027 before T028; T029 before T030.

### Parallel Opportunities

- US1 tests T002–T006: five different files.
- US2 tests T013–T018: six different files; T022 (front) in parallel with T020–T021.
- US3: T024, T025, T026, T027 in parallel; T031 in parallel with T028–T030.
- Polish: T033–T035.

---

## Parallel Example: User Story 1

```text
Task: "T002 [US1] off-list 400 cases in src/test/java/com/rfidback/service/TagServiceTest.java"
Task: "T003 [US1] off-list reads dropped in src/test/java/com/rfidback/service/RegistrationServiceTest.java"
Task: "T004 [US1] 400 cases in src/test/java/com/rfidback/controller/TagRegistrationApiTest.java"
Task: "T005 [US1] read not kept in src/test/java/com/rfidback/controller/RegistrationApiTest.java"
Task: "T006 [US1] batch counts in src/test/java/com/rfidback/controller/RegistrationReadsApiTest.java"
```

## Parallel Example: User Story 3

```text
Task: "T024 [US3] OffListTagPurgeTest on its own H2 database"
Task: "T025 [US3] delete TagOffListApiTest, AccessMatrixSecurityTest row"
Task: "T026 [US3] DataUpgradeEntity + DataUpgradeRepository"
Task: "T027 [US3] bulk delete queries in four repositories"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. T001 baseline.
2. Phase 3 (US1): registration no longer lets an off-list tag onto a bucket.
3. Stop and validate with quickstart steps 3–4. Deployable alone: production scans still create (and still flag) off-list tags until US2.

### Incremental Delivery

1. US1 → registration closed to off-list tags.
2. US2 → production scans closed too; nothing can create an off-list tag.
3. US3 → purge of what was stored before, and removal of the off-list page. **Deploy US3 only together with or after US1 + US2** (see Dependencies); in practice ship all three in one release, since the purge runs only once.

### Deployment note

The purge deletes production rows at the first startup of the new image. `deploy/vps/deploy.sh` takes its backup before starting it and stops if the backup fails; that backup is the recovery path (spec assumption). Check the purge's `INFO` line in the container log after deployment.

## Phase 7: Convergence

- [X] T039 Reconcile the registration-mode answer to an off-list read with FR-005 ("la même réponse qu'aujourd'hui, que le tag soit dans la liste ou non") and US1/AC2: `RegistrationService.recordRead` answers `"Registration read ignored: tag not in reference list"` (`READ_OFF_LIST`) where an in-list read gets `"Registration read"`, as research R5 and the contract chose. Either make the off-list read answer `READ_KEPT` (and update `RegistrationServiceTest.recordRead_offListUid_isIgnoredWithoutTouchingSession`, `RegistrationApiTest.offListRead_isNotKept_andSaveRegistersOnlyInListTags`, the `POST /tags/scan` description in `src/main/resources/openapi/api.yaml`, contracts and quickstart step 3), or have the spec owner confirm that "même réponse" means same status, fields and `isCompliant` and record that reading in FR-005 per FR-005, US1/AC2 (contradicts)
- [X] T040 Rerun the scan latency measurement of spec `004` ([quickstart](quickstart.md) "Latency") with in-list and off-list UIDs and record the p95 in `quickstart.md`; it must stay under 200 ms per SC-004, FR-010 (partial)
- [X] T041 Open `tags.html` (registration session with one in-list and one off-list read: only the in-list one listed, no badge, save without dialog; a `400` shows the server's reason) and `reader.html` (no "HORS LISTE", no box for an off-list scan) in a browser on the dev profile; T012 and T037 checked these flows through the API only per US1/AC1, US1/AC2, US1/AC3, US2/AC2 (partial)
