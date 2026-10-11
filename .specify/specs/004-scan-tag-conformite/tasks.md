---

description: "Task list for feature 004 — scan d'un TAG par un lecteur"
---

# Tasks: Scan d'un TAG par un lecteur (déclaration de conformité)

**Input**: Design documents from `.specify/specs/004-scan-tag-conformite/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan names the test classes to add or extend, and every earlier feature ships with its tests.

**Organization**: the spec has a single user story (US1, P1: a reader reports a tag scan). Its phase is split into three increments that can each be checked on their own: blank UID (FR-002), deduplication (FR-008), latency index (SC-004).

**Amendment 2026-10-11**: Phases 1–4 (T001–T017) are the first delivery, all done. The clarifications of 2026-10-11 (sliding window, the tag leaves its bucket, KO rescan, two tags per crate) are delivered by [Phases 5–8](#amendment-2026-10-11) (T018–T042), still for US1, as three more increments: D, E and F.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to
- Paths are relative to the repository root

## Path Conventions

Single Spring Boot project: `src/main/java/com/rfidback/…`, `src/main/resources/…`, `src/test/java/com/rfidback/…`. Generated OpenAPI code lands in `target/generated-sources/openapi` and is never edited by hand.

---

## Phase 1: Setup

**Purpose**: confirm the starting point is green, so any later failure comes from this feature.

- [X] T001 Run `mvn clean test` from the repository root and confirm all tests pass before any change. If it fails with `NoClassDefFoundError` on a bare class name or "Unresolved compilation problem", that's the VS Code Java extension racing Maven (see `CLAUDE.md`): rerun, don't fix code.

---

## Phase 2: Foundational

**Purpose**: give `TagService` the configuration and clock the deduplication needs (research R3). Blocks the deduplication increment only.

- [X] T002 Add the property to `src/main/resources/application.yml` under the existing `app:` key, next to `registration:`, with a comment in the same style:
  ```yaml
  scan:
    # A scan of the same tag by the same reader within this time after its latest record creates no new record (spec 004, FR-008). 0s disables it.
    duplicate-window: 10s
  ```
  The `test` profile inherits it from `application.yml`; don't add it to `application-test.yml`.
- [X] T003 In `src/main/java/com/rfidback/service/TagService.java`, replace `@RequiredArgsConstructor` with an explicit constructor taking `TagRepository`, `RecordRepository`, `BucketRepository`, `Clock clock` and `@Value("${app.scan.duplicate-window}") Duration duplicateWindow`, stored in `private final` fields. Copy the pattern of `RegistrationService`'s constructor (`src/main/java/com/rfidback/service/RegistrationService.java`, around line 68). Don't change behavior yet. Then update `setUp()` in `src/test/java/com/rfidback/service/TagServiceTest.java` to build it with `Clock.fixed(Instant.parse("2024-01-15T09:30:00Z"), ZoneOffset.UTC)` and `Duration.ofSeconds(10)`, keep the clock in a field for later tests, and confirm `mvn clean test -Dtest=TagServiceTest` still passes.

**Checkpoint**: build green, no behavior change.

---

## Phase 3: User Story 1 - Un lecteur RFID signale la lecture d'un tag (Priority: P1) 🎯 MVP

**Goal**: a production reader's scan creates one Record per pass of a tag, not one per hardware read. Malformed scans get a clean `400`. The lookup stays fast.

**Independent Test**: `mvn clean test -Dtest='TagServiceTest,TagScanApiTest,ReaderScanSecurityTest'` passes, and the manual steps 1–6 of [quickstart.md](quickstart.md) give the results in the [contract response table](contracts/openapi-tag-scan.md#2-responses).

### Increment A — blank UID → `400` (FR-002, research R1)

#### Tests (write first, confirm they fail)

- [X] T004 [P] [US1] Create `src/test/java/com/rfidback/controller/TagScanApiTest.java`, annotated like `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java` (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Transactional`), with a `PRODUCTION` reader saved in `@BeforeEach` and a `scan(token, uid, isCompliant)` helper that posts JSON to `/api/tags/scan` with the `x-api-token` header. Tests:
  - `scan_withEmptyUid_returns400_andCreatesNothing`: `"uid":""` → `400`, `$.detail` contains `uid`; `recordRepository.count()` and `tagRepository.count()` unchanged. **Must fail before T006.**
  - `scan_withWhitespaceUid_returns400_andCreatesNothing`: `"uid":"   "` → `400`, `$.detail` contains `uid`, counts unchanged. **Must fail before T006.**
  - `scan_withMissingIsCompliant_returns400`: body `{"uid":"X-1"}` → `400`, `$.detail` contains `isCompliant`. The status already passes today; the `$.detail` assertion fails until T006.
  - `scan_withSurroundingSpaces_isTrimmed`: `"uid":"  X-2  "` → `200`, `$.uid` = `"X-2"`. Already passes today; pinned so it stays true.
- [X] T005 [P] [US1] In `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java`, add `scan_fromEnregistrementReader_withBlankUid_returns400`: an `ENREGISTREMENT` reader (built like in `scan_fromEnregistrementReader_returns200WithoutRecord`) posts `"uid":"   "` → `400`. This replaces spec `003`'s "read ignored" answer for blank UIDs (research R1).

#### Implementation

- [X] T006 [US1] In `src/main/resources/openapi/api.yaml`, apply §1 of [contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md): add `pattern: '.*\S.*'` and the new description to `ScanTagRequest.uid` (around line 900), append the text to `post.description` of `/tags/scan` (around line 263), and replace the description of `ScanTagResponse.message` (around line 928). Keep the YAML style of the neighbouring entries. Run `mvn generate-sources` and check that `target/generated-sources/openapi/src/main/java/com/rfidback/generated/model/ScanTagRequest.java` now has `@Pattern(regexp = ".*\\S.*")` on `getUid()`.
- [X] T007 [US1] In `src/main/java/com/rfidback/controller/ApiExceptionHandler.java`, add a handler next to `handleConstraintViolation` (research R1, contract §1 "ApiExceptionHandler"):
  ```java
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ProblemDetail> handleInvalidBody(MethodArgumentNotValidException exception) {
      String detail = exception.getBindingResult().getFieldErrors().stream()
              .map(error -> error.getField() + ": " + error.getDefaultMessage())
              .collect(Collectors.joining(", "));
      return ResponseEntity.badRequest()
              .body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail));
  }
  ```
  Import `org.springframework.web.bind.MethodArgumentNotValidException` and `java.util.stream.Collectors`, and extend the class Javadoc with one sentence: invalid `@Valid` bodies get a `ProblemDetail` naming the fields, since Spring Boot's default error body leaves out the message. Then run T004 and T005: they must pass. Run the whole suite too (`mvn clean test`): other `@Valid` routes now return this body for a `400`, and existing tests must still pass.

**Checkpoint A**: blank UIDs get `400` with a `detail` naming `uid` in both reader modes, and nothing is written.

### Increment B — deduplication (FR-008, research R2)

#### Tests (write first, confirm they fail)

- [X] T008 [US1] In `src/test/java/com/rfidback/service/TagServiceTest.java` (after T003), add, using the fixed clock at `2024-01-15T09:30:00Z` and a 10 s window:
  - `registerScan_duplicateWithinWindow_returnsExistingRecordWithoutSaving`: existing tag; `recordRepository.findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(reader, tag, cutoff)` returns a Record with `compliant = true` and `creationDate = 09:29:55Z`. The request says `isCompliant = false`. Assert: `saveAndFlush` never called; response `isCompliant = true`, `processedAt = 09:29:55Z`, `message = "Duplicate read ignored"`. Capture the cutoff argument and assert it equals `09:29:50Z`.
  - `registerScan_nonCompliantRepeatOfCompliantRecord_lowersIt`: the lookup returns a compliant Record, the request says `isCompliant = false` → the Record passed to `recordRepository.saveAndFlush` has `compliant = false` and is **the same instance** (no new Record); response `isCompliant = false`, `message = "Duplicate read ignored"`.
  - `registerScan_compliantRepeatOfNonCompliantRecord_keepsIt`: the lookup returns a non-compliant Record, the request says `isCompliant = true` → `saveAndFlush` never called; response `isCompliant = false`.
  - `registerScan_noRecordInWindow_createsRecord`: the lookup returns `Optional.empty()` → `saveAndFlush` called once, `message` null.
  - `registerScan_newTag_skipsDuplicateLookup`: `findByUid` returns empty → the lookup method is never called and a Record is saved.
  - `registerScan_zeroWindow_skipsDuplicateLookup`: build a second `TagService` with `Duration.ZERO` → the lookup is never called and a Record is saved.
- [X] T009 [US1] In `src/test/java/com/rfidback/controller/TagScanApiTest.java` (after T004), add:
  - `scan_sameTagTwiceBySameReader_createsOneRecord`: two scans of `"D-1"` (the second with `isCompliant:false`) → both `200`. The second has `$.message` = `"Duplicate read ignored"`, `$.isCompliant` = `true` and the same `$.processedAt` as the first. `recordRepository.count()` went up by 1.
  - `scan_nonCompliantRepeat_lowersExistingRecord`: scan `"D-3"` with `isCompliant:true`, then with `isCompliant:false` → second response `$.isCompliant` = `false` and `$.message` = `"Duplicate read ignored"`; count up by 1; the Record for `D-3` (latest by `creationDate` for this reader) has `compliant = false`. A third scan with `isCompliant:true` still answers `$.isCompliant` = `false`.
  - `scan_sameTagByTwoReaders_createsTwoRecords`: a second `PRODUCTION` reader scans `"D-2"` right after the first one → no `message` on either, count up by 2.

#### Implementation

- [X] T010 [P] [US1] In `src/main/java/com/rfidback/repository/RecordRepository.java`, add `Optional<RecordEntity> findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(ReaderEntity reader, TagEntity tag, OffsetDateTime cutoff);` with the imports. Replace the existing fully-qualified `java.util.List` with an import while you're in the file.
- [X] T011 [US1] In `src/main/java/com/rfidback/service/TagService.java` `registerScan` (depends on T003, T010), after resolving the tag:
  - Note whether the tag was just created. Replace `orElseGet(() -> tagRepository.save(...))` with an explicit `Optional` check.
  - If the tag already existed and `duplicateWindow` is positive, compute `cutoff = OffsetDateTime.now(clock).minus(duplicateWindow)` and call the new repository method.
  - If a Record is found and the request says `isCompliant = false` while `record.isCompliant()` is `true`, call `record.setCompliant(false)` and `recordRepository.saveAndFlush(record)` (research R2: the non-compliant verdict wins within the window). Never raise a non-compliant Record back to compliant.
  - If a Record is found, return a `ScanTagResponse` built from it, after that update, (`uid` = tag uid, `isCompliant` = `record.isCompliant()`, `processedAt` = `record.getCreationDate()`, `message` = a `private static final String DUPLICATE_READ_IGNORED = "Duplicate read ignored"` constant) without saving anything.
  - Otherwise keep today's code path unchanged. Keep `Assert.notNull(reader, …)` and the blank-UID `Assert.isTrue` as defensive guards.
  - Keep this in a small private method (for example `ignoreDuplicate(RecordEntity existing, boolean isCompliant)`) so `registerScan` stays readable.
  - Run T008 and T009: both must pass.

**Checkpoint B**: repeats from one reader within 10 s create no Record, and a non-compliant repeat lowers the Record's verdict; other readers and later passes still create Records.

### Increment C — index for the lookup (SC-004, research R4)

- [X] T012 [P] [US1] In `src/main/java/com/rfidback/entity/RecordEntity.java`, change `@Table(name = "record")` to `@Table(name = "record", indexes = @Index(name = "idx_record_reader_tag_date", columnList = "reader_id, tag_id, creation_date"))` and import `jakarta.persistence.Index`. Check the column name Hibernate generates for `creationDate` in the H2 console (`creation_date` expected, the default naming strategy). If it differs, use the real name in `columnList`. Run `mvn clean test` (the test profile builds the schema from scratch, so a wrong column name fails at startup).

**Checkpoint C**: the full story is in place. Run the independent test above.

---

## Phase 4: Polish & Cross-Cutting Concerns

- [X] T013 Run `mvn clean install` from the repository root: full build, OpenAPI regeneration, all tests green.
- [X] T014 Follow "Schema upgrade on an existing database" in [quickstart.md](quickstart.md): start the app on the existing `./data/rfidbackdb.mv.db` **without deleting it**, check the index exists, restart once more and check it is not created twice. Report the result. Don't touch the prod database.
- [X] T015 Run the manual steps 1–6 (with 3b) and the latency run of [quickstart.md](quickstart.md) against the local app: 3 readers in parallel (SC-004's load). Write down the two p95 figures (distinct UIDs, repeated UID) in the final report. Put `app.scan.duplicate-window` and `show-sql` back to their original values afterwards.
- [X] T016 [P] Update `.specify/specs/004-scan-tag-conformite/spec.md`:
  - FR-002: mark the blank-UID rejection **Livré** (contract `pattern`, research R1).
  - FR-008: mark **Livré** (`TagService.registerScan`, research R2).
  - SC-004: add "mesuré via quickstart.md" with the figures from T015.
  - "Relectures en rafale" edge case: describe the delivered behavior.
  - Remove the sentence saying no HTTP test covers the blank UID or deduplication, now that `TagScanApiTest` does.
  - Refresh the header (`Feature Branch`: `004-scan-tag-conformite`, `Status`) and the evidence line numbers in US1 and the FRs (`TagController.java`, `TagService.java`, `api.yaml`) against the delivered code.
- [X] T017 [P] In `.specify/specs/003-enregistrement-tags-seau/spec.md`, add a note wherever blank reads by an `ENREGISTREMENT` reader are described: since spec `004` (research R1), a blank UID gets `400` before reaching the registration session. Change nothing else there.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001)**: none.
- **Foundational (T002–T003)**: after T001. Blocks Increment B only; Increment A can start right after T001.
- **US1**:
  - Increment A: T004, T005 → T006 → T007
  - Increment B: T008 (after T003), T009 (after T004, same file), T010 → T011
  - Increment C: T012
- **Polish (T013–T017)**: after US1. T014 and T015 need T012; T016 needs T015's figures.

### Within User Story 1

- Tests before implementation in each increment; they must fail first (T004/T005 fail with `500`/`200` today, T008/T009 fail because the repository method doesn't exist or two Records are created).
- `api.yaml` (T006) before anything that relies on the generated `@Pattern`.
- Repository (T010) before service (T011).
- T004 → T009 and T003 → T008 → T011 touch the same files in sequence; don't parallelize them.
- T007 (`ApiExceptionHandler`) can be written alongside T006, but T004's `$.detail` assertions only pass once both are done.

### Parallel Opportunities

- T004 and T005 (different test files).
- T010 and T012 (repository and entity), and both alongside T008.
- T016 and T017 (different spec files).

---

## Parallel Example: User Story 1

```bash
# Increment A tests together:
Task: "T004 Create TagScanApiTest blank-UID cases in src/test/java/com/rfidback/controller/TagScanApiTest.java"
Task: "T005 Add ENREGISTREMENT blank-UID case in src/test/java/com/rfidback/security/ReaderScanSecurityTest.java"

# Increment B/C persistence pieces together:
Task: "T010 Add findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc in src/main/java/com/rfidback/repository/RecordRepository.java"
Task: "T012 Add idx_record_reader_tag_date in src/main/java/com/rfidback/entity/RecordEntity.java"
```

---

## Implementation Strategy

### MVP First

1. T001 (baseline), then Increment A (T004–T007): the smallest shippable change (contract plus one exception handler), and it removes the `500` on blank UIDs.
2. **Validate**: `mvn clean test -Dtest='TagScanApiTest,ReaderScanSecurityTest'`.

### Incremental Delivery

1. Increment A → blank UIDs handled.
2. Foundational + Increment B → deduplication. This is the change that fixes pickers' counts.
3. Increment C → index, then Polish (build, schema upgrade, latency figures, spec updates).

Ship B and C together in production: B adds a lookup per scan, and C is what keeps it within SC-004 on a large `record` table.

---

## Notes

- Commit after each increment (A, B, C, Polish), on the `004-scan-tag-conformite` branch; the PR targets `dev`.
- Don't edit files under `target/generated-sources`; change `api.yaml` and regenerate.
- Out of scope (plan "Follow-ups"): cleaning up duplicates already in `record`, the hard-coded path in `ReaderApiTokenAuthenticationFilter`, and spec `005`'s conformity history.

---

# Amendment 2026-10-11

Design: [plan.md](plan.md) Summary 1–5, [research.md](research.md) R7–R13, [data-model.md](data-model.md), [contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md) §2 and §4.
The rules are numbered as in [data-model.md](data-model.md) §Scan rules: **1** same pass, **2** second tag of a crate, **3** rescan today, **4** count.

## Phase 5: Setup

- [X] T018 On branch `004-scan-tag-conformite` (fast-forwarded to `origin/dev`), run `mvn clean test` from the repository root and confirm everything passes before any change. Same `NoClassDefFoundError` caveat as T001.

---

## Phase 6: Foundational

**Purpose**: the schema, configuration, repository methods and contract text that the three increments share. No behavior change: the build stays green at the checkpoint.

- [X] T019 [P] In `src/main/resources/application.yml`, under `app.scan`, reword the comment of `duplicate-window` to "A read of a tag by the same reader less than this after the last read of the record that counted it is the same pass, no new record (spec 004, FR-008). 0s disables it." and add after it:
  ```yaml
    # A tag of the same bucket first read by the same reader less than this after a record counted another tag of that bucket is the crate's second tag (spec 004, FR-010). 0s disables it.
    crate-window: 2s
  ```
- [X] T020 [P] In `src/main/java/com/rfidback/entity/RecordEntity.java` (data-model §Record, research R7, R10, R11):
  - add `@Column(name = "last_read_at") private OffsetDateTime lastReadAt;` (nullable; comment: "Last read this record absorbed: set at creation, then by each read of the same pass or of the crate's second tag (spec 004, FR-008/FR-010); null before 2026-10-11.");
  - add `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "bucket_id") private BucketEntity bucket;` (comment: "The bucket the counted tag left; null for a tag without a bucket and for a rescan (spec 004, FR-009/FR-010).");
  - in `@Table(indexes = …)`, replace `idx_record_reader_tag_date` (`reader_id, tag_id, creation_date`) with `@Index(name = "idx_record_reader_bucket_date", columnList = "reader_id, bucket_id, creation_date")` and update the first line of the comment above `@Table` to say it serves the crate lookup of a scan (spec 004, FR-010).
- [X] T021 [P] In `src/main/java/com/rfidback/entity/TagEntity.java`, add `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "counted_record_id") private RecordEntity countedRecord;` with the comment "The record that counted this tag since it was last registered into a bucket: its own, its crate's or its rescan's; null once registered again (spec 004, FR-009, research R8)."
- [X] T022 In `src/main/java/com/rfidback/repository/RecordRepository.java` (after T020), add next to the existing duplicate lookup, which stays until T029:
  ```java
  /**
   * The newest record of this reader that counted another tag of this bucket after {@code cutoff}: the crate a second
   * tag joins (spec 004, FR-010). Row-locked like the duplicate lookup (spec 005, FR-006).
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RecordEntity> findFirstByReaderAndBucketAndTagNotAndCreationDateAfterOrderByCreationDateDesc(
          ReaderEntity reader, BucketEntity bucket, TagEntity tag, OffsetDateTime cutoff);
  ```
- [X] T023 In `src/main/java/com/rfidback/repository/TagRepository.java` (after T021), add:
  ```java
  /** Unlinks the tags counted by records of these tags, before those records are deleted (spec 004, research R11). */
  @Modifying
  @Query("update TagEntity t set t.countedRecord = null"
          + " where t.countedRecord.id in (select r.id from RecordEntity r where r.tag.id in :tagIds)")
  int clearCountedRecordOfTags(@Param("tagIds") Collection<UUID> tagIds);
  ```
  Then in `src/main/java/com/rfidback/configuration/OffListTagPurge.java`, in the chunk loop, call it first, before `deleteByRecordTagIdIn`, and extend the foreign-key comment: "a tag can point to a record (spec 004)". Don't count it in the log line.
- [X] T024 In `src/main/java/com/rfidback/service/TagService.java`, add the constructor parameter `@Value("${app.scan.crate-window}") Duration crateWindow` after `duplicateWindow`, stored in a `private final` field (not used yet). In `src/test/java/com/rfidback/service/TagServiceTest.java` `setUp()`, pass `CRATE_WINDOW = Duration.ofSeconds(2)` and add `when(lineActivityService.startOfToday()).thenReturn(OffsetDateTime.parse("2024-01-15T00:00:00Z"));` (the station day of the fixed clock; rule 3 will need it). Run `mvn clean test -Dtest=TagServiceTest`.
- [X] T025 [P] In `src/main/resources/openapi/api.yaml`, apply §4 of [contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md): replace the deduplication text in `post.description` of `/tags/scan` and the description of `ScanTagResponse.message`. Keep the YAML style of the neighbouring entries. No schema change: `mvn generate-sources` must produce the same `TagApiDelegate` signature.

**Checkpoint**: `mvn clean test` green. The new columns and index exist on the `test` schema; behavior is unchanged.

---

## Phase 7: User Story 1 - Un lecteur RFID signale la lecture d'un tag (Priority: P1) — amendment

**Goal**: one Record per crate, wherever it waits and however many tags it carries; a counted tag leaves its bucket; reusing it the same day gives a KO Record.

**Independent Test**: `mvn clean test -Dtest='TagServiceTest,TagScanApiTest,TagRegistrationApiTest,OffListTagPurgeTest'` passes, and the manual steps 1–6 of [quickstart.md](quickstart.md) give the results of the [contract response table](contracts/openapi-tag-scan.md#2-responses-production-reader-after-the-amendment).

### Increment D — count, leave the bucket, sliding window (rules 1 and 4; FR-008, FR-009; research R7, R8)

#### Tests (write first, confirm they fail)

- [X] T026 [US1] In `src/test/java/com/rfidback/service/TagServiceTest.java`, replace the helpers and tests of the "registerScan duplicates" section, which stub `findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc`, with ones built on the tag's counting Record. Add helpers `countedRecord(ReaderEntity reader, String createdAt, String lastReadAt, boolean compliant)` (a `RecordEntity` with an id, that reader, an existing tag, those dates) and `countedTag(RecordEntity record)` (a tag with no bucket and `countedRecord = record`, returned by `tagRepository.findByUid`), and stub `recordRepository.findWithLockById(record.getId())` to return it. Clock `09:30:00Z`, window 10 s:
  - `registerScan_tagInBucket_countsIt_andTheTagLeavesTheBucket`: tag in bucket `12` with a picker → the saved Record has that picker, `bucket` = the bucket, `lastReadAt` = `09:30:00Z`; afterwards `tag.getBucket()` is `null` and `tag.getCountedRecord()` is the saved Record; no `message`.
  - `registerScan_samePass_measuredFromTheLastRead`: counted Record by the same reader created at `09:29:00Z` (60 s ago), last read `09:29:55Z`; request compliant → no new Record (`saveAndFlush` never called with another instance), the Record's `lastReadAt` = `09:30:00Z`, response `processedAt` = `09:29:00Z`, `message` = `"Duplicate read ignored"`.
  - `registerScan_samePass_lastReadNull_isNotTheSamePass`: same but `lastReadAt` `null` and the Record created on `2024-01-14` → a new Record is created (rule 4, the day is earlier, so no KO).
  - `registerScan_zeroWindow_neverTheSamePass`: second `TagService` with `Duration.ZERO`, Record from `2024-01-14`, last read `09:29:59Z` → a new Record.
  - Port the four verdict tests to the new helpers, keeping their names and assertions: `registerScan_nonCompliantRepeatOfCompliantRecord_lowersIt`, `registerScan_nonCompliantRepeatOfChangedRecord_keepsIt` (`recordConformityChangeRepository.existsByRecord` → `true`), `registerScan_compliantRepeat_skipsChangeLookup`, `registerScan_compliantRepeatOfNonCompliantRecord_keepsIt`. Port `registerScan_duplicate_leavesTheExistingRecordsActivity` too.
  - Replace `registerScan_duplicateWithinWindow_returnsExistingRecordWithoutSaving`, `registerScan_noRecordInWindow_createsRecord`, `registerScan_newTag_skipsDuplicateLookup` and `registerScan_zeroWindow_skipsDuplicateLookup` with the tests above; `registerScan_newTag_skipsDuplicateLookup` becomes "new tag → `findWithLockById` never called, a Record is saved".
  These fail to compile or fail until T028.
- [X] T027 [US1] In `src/test/java/com/rfidback/controller/TagScanApiTest.java`, autowire `BucketRepository`, `PickerRepository` and `JdbcTemplate`; add a helper `tagInBucket(int number)` that saves a picker, a bucket assigned to it and a tag with `ReferenceTagUids.nextInList()` in that bucket, and returns the uid; and a helper `age(RecordEntity record, Duration createdAgo, Duration lastReadAgo)` that runs `update record set creation_date = ?, last_read_at = ? where id = ?` through `JdbcTemplate` (`creation_date` is not updatable through JPA). Then add:
  - `scan_tagInBucket_countsIt_andEmptiesItFromTheBucket`: → `200`, no `message`; the Record has the picker and the bucket; `tagRepository.findByUid(uid)` has no bucket and `countedRecord` = that Record.
  - `scan_crateLeftInFrontOfTheReader_staysOneRecord` (the reported bug): scan, then `age(record, 30 s, 5 s)`, scan again → `"Duplicate read ignored"`, record count up by 1 only, and the Record's `last_read_at` is back to about now.
  These fail until T028.

#### Implementation

- [X] T028 [US1] Rewrite `registerScan` in `src/main/java/com/rfidback/service/TagService.java` (after T022, T024) to apply rules 1 and 4 (rules 2 and 3 come in T033 and T038). Keep the blank and off-list checks first, then find or create the tag, then:
  - **Rule 1**: if `tag.getCountedRecord()` is not null, load it with `recordRepository.findWithLockById(id)`; if its reader is this reader (compare ids), the window is positive, `lastReadAt` is not null and `lastReadAt.isAfter(now.minus(duplicateWindow))`, then set `lastReadAt = now` and return through the existing `ignoreDuplicate` (rename its parameter, keep the non-compliant-wins rule and the `existsByRecord` check). `now = OffsetDateTime.now(clock)`.
  - **Rule 4**: build the Record as today, plus `.bucket(tag.getBucket())` and `.lastReadAt(now)`; after `saveAndFlush`, set `tag.setBucket(null)` and `tag.setCountedRecord(saved)` (the tag is managed; a newly created tag too, since `tagRepository.save` returned it).
  - Split into small private methods (`samePass(...)`, `count(...)`), each with a one-line Javadoc naming its rule and FR, as the existing methods do. Update the Javadoc of `registerScan` to list the rules in order.
  - Delete `findRecentRecord`.
  Run T026 and T027: they pass.
- [X] T029 [US1] Remove `findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc` from `src/main/java/com/rfidback/repository/RecordRepository.java` (no caller left after T028) and from any remaining test stub; `grep -rn findFirstByReaderAndTag src` must return nothing.

**Checkpoint D**: a crate that waits counts once; a counted tag is out of its bucket. `TagScanApiTest.scan_sameTagByTwoReaders_createsTwoRecords` still passes at this point, since rule 3 doesn't exist yet.

### Increment E — rescan the same day, register again (rule 3; FR-009; research R8, R9)

#### Tests (write first, confirm they fail)

- [X] T030 [P] [US1] In `src/test/java/com/rfidback/service/TagServiceTest.java` (start of day stubbed at `2024-01-15T00:00:00Z` by T024):
  - `registerScan_rescanToday_afterTheWindow_createsANonCompliantRecordWithoutPicker`: counted Record by the same reader created `08:00:00Z`, last read `08:00:00Z`; request `isCompliant: true` → a new Record with `compliant = false`, `picker = null`, `bucket = null`, `lastReadAt` = `09:30:00Z`; `tag.getCountedRecord()` is the new Record; the old Record unchanged; response `isCompliant = false`, `message = "Tag already scanned today"`.
  - `registerScan_rescanToday_byAnotherReader_isKO`: counted Record by another reader, last read `09:29:59Z` → same outcome (rule 1 doesn't apply across readers).
  - `registerScan_countedOnAnEarlierDay_createsAnOrdinaryRecordWithoutPicker`: counted Record created `2024-01-14T17:00:00Z` → a new Record with the reader's verdict (send `false`, then a second test or assertion with `true`), `picker = null`, no `message`; `countedRecord` = the new Record.
  - `registerScan_rescanAtStartOfToday_isKO`: counted Record created exactly `2024-01-15T00:00:00Z` → KO (the start of the day is included).
  - `registerTagsForBucket_clearsTheCountedRecord`: an existing tag with no bucket and a `countedRecord` → after `registerTagsForBucket(5, List.of(uid), false)`, the saved tag has bucket `5` and `countedRecord == null`.
- [X] T031 [P] [US1] In `src/test/java/com/rfidback/controller/TagScanApiTest.java`:
  - Rewrite `scan_sameTagByTwoReaders_createsTwoRecords` as `scan_sameTagByAnotherReaderTheSameDay_isKO`: the second reader's scan → `200`, `$.isCompliant` `false`, `$.message` `"Tag already scanned today"`, count up by 2, the second Record has no picker.
  - `scan_rescanAfterTheWindow_sameReader_isKO`: scan a `tagInBucket`, `age(record, 30 s, 30 s)`, scan again with `isCompliant: true` → `$.isCompliant` `false`, `"Tag already scanned today"`, count up by 2; scan once more straight away → `"Duplicate read ignored"`, count unchanged.
  - Check the other tests of the class still hold: any that scans the same uid from two readers or after an `age` now expects rule 3.
- [X] T032 [P] [US1] In `src/test/java/com/rfidback/controller/TagRegistrationApiTest.java`, add `register_aCountedTagAgain_resetsIt_soItsNextScanCountsNormally`: save a `PRODUCTION` reader and a picker assigned to bucket `N`; register a uid into `N` with `register(...)`; scan it (post to `/api/tags/scan` with the reader's `x-api-token`) → its tag has no bucket; register it into `N` again → `200` and no `409`; age the Record by 30 s through `JdbcTemplate` and scan again → no `message`, `$.isCompliant` as sent, and the new Record has the picker.

#### Implementation

- [X] T033 [US1] In `src/main/java/com/rfidback/service/TagService.java` `registerScan`, between rule 1 and rule 4, add **rule 3** (after T028): if the counting Record (already loaded for rule 1) has `creationDate` not before `lineActivityService.startOfToday()`, create a Record with the tag, the reader, `picker(null)`, no bucket, the line's activity (`currentActivity(reader)`), `compliant(false)`, `lastReadAt(now)`; set `tag.setCountedRecord(saved)`; return its values with `message` = a new constant `RESCAN_TODAY = "Tag already scanned today"`. A counting Record from an earlier day falls through to rule 4, which then credits no picker because the tag has no bucket. Private method `rescanToday(...)` with a Javadoc naming FR-009.
- [X] T034 [US1] In `registerTagsForBucket` of the same file, set `tag.setCountedRecord(null)` next to `tag.setBucket(bucket)` (research R8), with a comment: "registering again makes the tag new for the scan rules (spec 004, FR-009)". Run T030–T032: they pass.

**Checkpoint E**: a reused tag gives a KO Record with no picker the same day; registering it again resets it.

### Increment F — two tags, one crate (rule 2; FR-010; research R10)

#### Tests (write first, confirm they fail)

- [X] T035 [P] [US1] In `src/test/java/com/rfidback/service/TagServiceTest.java`:
  - `registerScan_secondTagOfACrate_joinsItsRecord`: tag T2 in bucket B; `findFirstByReaderAndBucketAndTagNotAndCreationDateAfterOrderByCreationDateDesc(reader, B, T2, cutoff)` returns a crate Record C (created `09:29:59Z`, compliant). Capture `cutoff` = `09:29:58Z`. Assert: `saveAndFlush` never called with a new Record; `T2.getBucket()` null; `T2.getCountedRecord()` is C; `C.getLastReadAt()` = `09:30:00Z`; response `processedAt` = C's creation, `isCompliant` true, `message = "Second tag of the crate ignored"`.
  - `registerScan_secondTagOfACrate_nonCompliant_lowersTheCrate` (and unchanged when `existsByRecord(C)` is true).
  - `registerScan_noCrate_countsTheTag`: the lookup returns empty → rule 4, the Record has bucket B.
  - `registerScan_tagWithoutBucket_skipsTheCrateLookup` and `registerScan_zeroCrateWindow_skipsTheCrateLookup` (second `TagService` with `Duration.ZERO` for the crate window): the lookup is never called.
- [X] T036 [P] [US1] In `src/test/java/com/rfidback/controller/TagScanApiTest.java`, with a helper `secondTagIn(bucket)` (a second reference uid in the same bucket):
  - `scan_twoTagsOfOneCrate_countOnce_andBothLeaveTheBucket`: scan T1 then T2 at once → the second has `"Second tag of the crate ignored"` and the first one's `processedAt`; count up by 1; neither tag has a bucket; both `countedRecord` are the same Record. Scanning T2 again straight away → `"Duplicate read ignored"`, count unchanged.
  - `scan_tagsOfTwoBuckets_countTwice`: T1 in bucket A, T2 in bucket B → two Records, no `message`.
  - `scan_secondTagMoreThanTheCrateWindowLater_countsAnotherCrate`: scan T1, `age(record, 3 s, 0 s)`, scan T2 → a second Record (no `message`).
  - `scan_secondTagByAnotherReader_countsAnotherCrate`: T1 by reader 1, T2 by reader 2 → two Records.

#### Implementation

- [X] T037 [US1] Make the crate lookup available: nothing to add, it was declared in T022. Check its derived query on H2 with T036 before writing T038 if it fails to start (an unsupported `TagNot` would show at boot); if so, replace it with an explicit `@Query("select r from RecordEntity r where r.reader = :reader and r.bucket = :bucket and r.tag <> :tag and r.creationDate > :cutoff order by r.creationDate desc")` returning `List<RecordEntity>` with `Pageable` limit 1, keeping the `@Lock`.
- [X] T038 [US1] In `src/main/java/com/rfidback/service/TagService.java` `registerScan`, add **rule 2** after rule 1 and before rule 3 (after T033): if `tag.getBucket()` is not null and `crateWindow` is positive, call the crate lookup with `cutoff = now.minus(crateWindow)`; if it returns C, set `tag.setBucket(null)`, `tag.setCountedRecord(C)`, `C.setLastReadAt(now)`, apply the non-compliant-wins rule to C, and answer with C's values and a new constant `SECOND_TAG_OF_CRATE = "Second tag of the crate ignored"`. Generalise `ignoreDuplicate` into one method taking the message, used by rules 1 and 2. Run T035 and T036: they pass.

**Checkpoint F**: the full amendment is in place. Run the independent test above.

---

## Phase 8: Polish & Cross-Cutting Concerns (amendment)

- [X] T039 [P] In `src/test/java/com/rfidback/configuration/OffListTagPurgeTest.java`, extend `run_deletesOffListDataOnly_thenNeverAgain` (or add a test next to it): an off-list tag with a Record, and an on-list tag whose `countedRecord` points at that Record → the purge succeeds, the Record is gone, and the on-list tag remains with `countedRecord` null (T023, research R11).
- [X] T040 Run `mvn clean test` (whole suite, alphabetical order as on CI) and then `mvn clean install`. The tests that scan tags (`RecordApiTest`, `RecordStatsApiTest`, `ReaderApiTest`, `RegistrationApiTest`, `ReaderScanSecurityTest`) only assert statuses on repeated scans or scan distinct uids; if one fails, it relied on a second scan of the same tag counting normally the same day: change its expectation to rule 3 or give it a fresh uid, and say which in the report.
- [X] T041 Follow [quickstart.md](quickstart.md) "Schema upgrade on an existing database" (columns `last_read_at`, `bucket_id`, `counted_record_id`, index `idx_record_reader_bucket_date`; restart twice), then the manual steps 1–6 and the latency run against the local app. Write down both p95 figures. Put any changed setting back afterwards. Don't touch the prod database.
- [X] T042 [P] Documentation:
  - `.specify/specs/004-scan-tag-conformite/spec.md`: mark FR-008's sliding window, FR-009 and FR-010 **Livré** with their evidence (`TagService.registerScan` rules, research R7–R10), add the T041 figures to SC-004, and update the **Status** line (delivered 2026-10-11 amendment).
  - `CLAUDE.md`, Persistence section: one sentence: a production scan takes the tag out of its bucket (`tag.counted_record_id` points at the record that counted it; registering again clears it); the same day, a rescan gives a non-compliant record with no picker, and two tags of one bucket read by one reader within `app.scan.crate-window` count once (spec 004).

---

## Dependencies & Execution Order (amendment)

- **Setup (T018)**: none.
- **Foundational (T019–T025)**: after T018. T019, T020, T021, T025 in parallel; T022 after T020; T023 after T021; T024 after T019.
- **Increment D (T026–T029)**: after Foundational. T026 and T027 (tests) → T028 → T029.
- **Increment E (T030–T034)**: after T028. T030, T031, T032 in parallel (different files) → T033 → T034.
- **Increment F (T035–T038)**: after T033 (rule 2 sits between rules 1 and 3 in the same method). T035, T036 in parallel → T037 → T038.
- **Polish (T039–T042)**: T039 after T023 (can run alongside the increments); T040 after T038; T041 after T040; T042 after T041.

Same-file sequences (don't parallelize): `TagServiceTest` T024 → T026 → T030 → T035; `TagScanApiTest` T027 → T031 → T036; `TagService` T024 → T028 → T033 → T034 → T038.

## Parallel Example: amendment

```bash
# Foundational, all in different files:
Task: "T019 Add app.scan.crate-window in src/main/resources/application.yml"
Task: "T020 Add lastReadAt, bucket and idx_record_reader_bucket_date in src/main/java/com/rfidback/entity/RecordEntity.java"
Task: "T021 Add countedRecord in src/main/java/com/rfidback/entity/TagEntity.java"
Task: "T025 Update /tags/scan and ScanTagResponse.message descriptions in src/main/resources/openapi/api.yaml"

# Increment E tests, three files:
Task: "T030 Rescan tests in src/test/java/com/rfidback/service/TagServiceTest.java"
Task: "T031 KO rescan API tests in src/test/java/com/rfidback/controller/TagScanApiTest.java"
Task: "T032 Register-again test in src/test/java/com/rfidback/controller/TagRegistrationApiTest.java"
```

## Implementation Strategy (amendment)

1. **MVP = Foundational + Increment D**: it fixes the reported bug (a waiting crate counted again every 10 s) and empties buckets as crates pass. On its own it changes nothing else visible: a rescan still counts as before, with no picker.
2. **Increment E**: the KO rescan. Ship it with D or right after: once D is live, a reused tag counts with no picker, which the dashboard shows but doesn't flag.
3. **Increment F**: two tags per crate.
4. **Polish**, then one PR to `dev` for the whole amendment (commit after each increment).

## Notes (amendment)

- Branch `004-scan-tag-conformite`; the PR targets `dev`.
- Out of scope (plan "Follow-ups"): emptying buckets of tags scanned before the deployment (research R12), dropping the old `idx_record_reader_tag_date` on prod, and any front change.
