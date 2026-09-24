---

description: "Task list for feature 004 — scan d'un TAG par un lecteur"
---

# Tasks: Scan d'un TAG par un lecteur (déclaration de conformité)

**Input**: Design documents from `.specify/specs/004-scan-tag-conformite/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-tag-scan.md](contracts/openapi-tag-scan.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan names the test classes to add or extend, and every earlier feature ships with its tests.

**Organization**: the spec has a single user story (US1, P1: a reader reports a tag scan). Its phase is split into three increments that can each be checked on their own: blank UID (FR-002), deduplication (FR-008), latency index (SC-004).

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
