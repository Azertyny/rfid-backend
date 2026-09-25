---

description: "Task list for feature 005 — consultation des lectures et bascule de conformité"
---

# Tasks: Consultation des lectures et bascule de conformité

**Input**: Design documents from `.specify/specs/005-consultation-lectures-conformite/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-records.md](contracts/openapi-records.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan (research R8) names the test classes to add or extend, the spec lists missing tests as gaps (SC-001, SC-002), and every earlier feature ships with its tests.

**Organization**: one phase per user story of the spec. US1 (P1, last-10 list) already works: its phase pins it with tests and makes it fast enough to poll (SC-003). US2 (P1, compliance toggle) gets the history write, idempotency and the FR-006 lock. US3 (P2, history read) adds the Administrateur-only route.

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

**Purpose**: the history entity and its repository, used by US2 (write, FR-006 check) and US3 (read). Blocks US2 and US3, not US1.

- [X] T002 Create `src/main/java/com/rfidback/entity/RecordConformityChangeEntity.java` per [data-model.md](data-model.md), with the same annotations and Lombok style as `src/main/java/com/rfidback/entity/RecordEntity.java` (`@Entity`, `@Getter`, `@Setter`, `@NoArgsConstructor`, `@AllArgsConstructor`, `@Builder`):
  - `@Table(name = "record_conformity_change", indexes = @Index(name = "idx_record_conformity_change_record_date", columnList = "record_id, changed_at"))`, with a one-line comment above it saying the index serves the history read and the duplicate-scan check (spec 005, FR-006/FR-007).
  - `@Id @GeneratedValue(strategy = GenerationType.UUID) UUID id`.
  - `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "record_id", nullable = false) RecordEntity record`.
  - `@Column(name = "previous_conformity", nullable = false) boolean previousCompliant`.
  - `@Column(name = "new_conformity", nullable = false) boolean newCompliant`.
  - `@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "author_id", nullable = false) UserEntity author`.
  - `@CreationTimestamp @Column(name = "changed_at", nullable = false, updatable = false) OffsetDateTime changedAt`.
  - Don't add any collection to `RecordEntity` or `UserEntity` (research R1: unidirectional).
- [X] T003 Create `src/main/java/com/rfidback/repository/RecordConformityChangeRepository.java` (`extends JpaRepository<RecordConformityChangeEntity, UUID>`), in the style of `src/main/java/com/rfidback/repository/RecordRepository.java`, with a one-line Javadoc on each method:
  - `boolean existsByRecord(RecordEntity record);` — "Whether an Opérateur already changed this record: duplicate scans then leave it alone (spec 005, FR-006)."
  - `@EntityGraph(attributePaths = "author") List<RecordConformityChangeEntity> findAllByRecordOrderByChangedAtAsc(RecordEntity record);` — "Compliance history of a record, oldest first (spec 005, FR-007)."
  Then run `mvn clean test`: the `test` profile builds the schema from scratch, so a mapping mistake fails at startup.

**Checkpoint**: build green, new table created, no behavior change.

---

## Phase 3: User Story 1 - Consulter les 10 dernières lectures d'un lecteur (Priority: P1)

**Goal**: pin today's behavior with tests (spec SC-001, SC-002) and keep the list fast on a season-sized table (SC-003: p95 < 200 ms with 1 to 3 screens polling every 500 ms; research R11, R12). The response itself doesn't change.

**Independent Test**: `mvn clean test -Dtest=RecordApiTest` passes the list cases; `GET /api/records/readers/{uid}` returns at most 10 records, newest first, with their tag uid, and `404` for an unknown reader. The latency run of [quickstart.md](quickstart.md) (T025) gives p95 < 200 ms.

### Tests

- [X] T004 [US1] Create `src/test/java/com/rfidback/controller/RecordApiTest.java`, annotated like `src/test/java/com/rfidback/controller/TagScanApiTest.java` (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Transactional`), with a Javadoc "HTTP-level checks of the /api/records routes (spec 005)". Autowire `MockMvc`, `ObjectMapper`, `ReaderRepository`, `RecordRepository`, `TagRepository`, `UserRepository`, `PasswordEncoder`. In `@BeforeEach`:
  - save a reader `"Reader record test"` (`ReaderEntity.builder().name(...).build()`, as in `TagScanApiTest`);
  - save two users with `UserEntity.builder()`: `"record-admin"` role `Role.ADMINISTRATEUR` and `"record-op"` role `Role.OPERATEUR`, both `enabled(true)`, `passwordHash(passwordEncoder.encode("irrelevant"))`;
  - add helpers `asAdmin()` / `asOperator()` returning `SecurityMockMvcRequestPostProcessors.user("record-admin").roles("ADMINISTRATEUR")` / `user("record-op").roles("OPERATEUR")` (same approach as `src/test/java/com/rfidback/security/ReaderTokenVisibilityTest.java`; the username must match a saved user, since US2 resolves the author by name);
  - add `RecordEntity saveRecord(String tagUid, boolean compliant)` that saves a `TagEntity` with that uid and a `RecordEntity` for the reader with `saveAndFlush`.
  Tests:
  - `listLatest_returnsAtMostTenNewestFirst`: save 12 records → `GET /api/records/readers/Reader record test` (URL-encode the space, or use `get("/api/records/readers/{id}", reader.getName())`) with `asOperator()` → `200`, `$.records.length()` = 10, and each `creationDate` ≥ the next one. `@CreationTimestamp` may give equal timestamps: assert non-increasing order, not strict. Also assert every `$.records[*].tagUid` is one of the saved uids (it pins the tag mapping whose loading T007 changes).
  - `listLatest_unknownReader_returns404`: `GET /api/records/readers/unknown-reader` → `404`.
  Both pass today; they pin the behavior.

### Implementation

- [X] T005 [US1] In `src/main/resources/openapi/api.yaml`, `/records/readers/{readerId}` → `get.responses` (around line 420): add `"401": { $ref: "#/components/responses/Unauthorized" }` and `"403": { $ref: "#/components/responses/Forbidden" }` before `"404"`, as on the other routes (contract §4). Run `mvn generate-sources`: the `RecordApiDelegate.listLatestRecordsForReader` signature must not change.
- [X] T006 [P] [US1] In `src/main/java/com/rfidback/entity/RecordEntity.java`, turn the single index into a list and add the one for the last-10 list (research R11):
  ```java
  @Table(name = "record", indexes = {
          @Index(name = "idx_record_reader_tag_date", columnList = "reader_id, tag_id, creation_date"),
          @Index(name = "idx_record_reader_date", columnList = "reader_id, creation_date") })
  ```
  Replace the comment above it with two lines: the first index serves the duplicate-read lookup of a scan (spec 004, FR-008); the second serves the last 10 records of a reader (spec 005, SC-003). Keep spec 004's index unchanged. Run `mvn clean test` (the `test` profile builds the schema from scratch, so a wrong column name fails at startup).
- [X] T007 [P] [US1] In `src/main/java/com/rfidback/repository/RecordRepository.java`, annotate `findTop10ByReader_NameOrderByCreationDateDesc` with `@EntityGraph(attributePaths = "tag")` (import `org.springframework.data.jpa.repository.EntityGraph`) and add the Javadoc "The 10 newest records of a reader, with their tag loaded in the same query (spec 005, SC-003)." Don't fetch `picker`: `RecordService.toRecordSummary` only reads its id, which needs no query (research R12). Run T004: it must still pass.

**Checkpoint**: US1 covered by tests, and the list reads 10 index entries in 2 queries whatever the table size.

---

## Phase 4: User Story 2 - Basculer la conformité d'une lecture (Priority: P1) 🎯 MVP

**Goal**: each real change is recorded (previous value, new value, author, date), the same value twice writes nothing, and a record an Opérateur changed is no longer lowered by duplicate scans (FR-003, FR-006; research R2–R4).

**Independent Test**: `mvn clean test -Dtest='RecordServiceTest,RecordApiTest,TagServiceTest,TagScanApiTest'` passes; quickstart steps 3–5, 7 and 8 give the expected results (step 4 checked in the database, since the read route is US3).

### Tests (write first, confirm they fail)

- [X] T008 [P] [US2] Create `src/test/java/com/rfidback/service/RecordServiceTest.java`, in the style of `src/test/java/com/rfidback/service/TagServiceTest.java` (plain JUnit 5 + `Mockito.mock` in `@BeforeEach`, no Spring context). Mocks: `RecordRepository`, `ReaderRepository`, `RecordConformityChangeRepository`, `UserRepository`; build `new RecordService(recordRepository, readerRepository, recordConformityChangeRepository, userRepository)` (the field order T012 must follow). In `@BeforeEach` set `SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("op1", null, "ROLE_OPERATEUR"))` and stub `userRepository.findByUsername("op1")` to return a `UserEntity` `op1`; clear the context in `@AfterEach`. Tests:
  - `updateConformity_differentValue_updatesRecordAndWritesOneChange`: `recordRepository.findWithLockById(id)` returns a compliant record → call with `false` → the record's `compliant` is `false`; `recordConformityChangeRepository.save` called once with an entity whose `record` is that record, `previousCompliant = true`, `newCompliant = false`, `author` = `op1` (capture it with `ArgumentCaptor`).
  - `updateConformity_sameValue_writesNothing`: the record is non-compliant, call with `false` → `recordConformityChangeRepository.save`, `recordRepository.save` and `userRepository.findByUsername` never called.
  - `updateConformity_unknownRecord_throwsNotFound`: `findWithLockById` returns empty → `RecordNotFoundException`, nothing saved.
  Fails to compile until T011/T012 exist, which counts as failing.
- [X] T009 [US2] In `src/test/java/com/rfidback/controller/RecordApiTest.java` (after T004), autowire `RecordConformityChangeRepository` and add a `patch(RecordEntity record, Boolean isCompliant, RequestPostProcessor who)` helper that sends `PATCH /api/records/{id}/conformity` with `.with(csrf())` and a JSON body (`{}` when `isCompliant` is `null`). Tests:
  - `patchConformity_differentValue_returns204_andRecordsChange`: compliant record, Opérateur sends `false` → `204`; `recordRepository.findById(...)` is non-compliant; `recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(record)` has one entry, `previousCompliant = true`, `newCompliant = false`, author username `record-op`.
  - `patchConformity_sameValueTwice_recordsOneChange`: send `false` twice → both `204`, one change in total.
  - `patchConformity_backAndForth_recordsTwoChangesInOrder`: send `false` then `true` → two changes, the first `true → false`, the second `false → true`.
  - `patchConformity_unknownRecord_returns404`: random UUID → `404`.
  - `patchConformity_missingValue_returns400`: body `{}` → `400`.
  - `duplicateScanAfterChange_doesNotLowerRecord` (FR-006, end to end): scan `"L-1"` compliant through `POST /api/tags/scan` with the reader's `x-api-token` header (copy the `scan` helper of `TagScanApiTest`), find its record, Opérateur PATCHes `false` then `true`, scan `"L-1"` again with `isCompliant:false` → `200`, `$.message` = `"Duplicate read ignored"`, `$.isCompliant` = `true`; the record is still compliant and still has exactly 2 changes.
  - `duplicateScanWithoutChange_stillLowersRecord`: scan `"L-2"` compliant, then again with `isCompliant:false` → the record is non-compliant and has no change (spec `004` behavior kept).
- [X] T010 [US2] In `src/test/java/com/rfidback/service/TagServiceTest.java`: add a `RecordConformityChangeRepository` mock in `setUp()` and pass it to the `TagService` constructor (new parameter after `BucketRepository`, see T014); update any other `new TagService(...)` call in the file (e.g. the zero-window test). Then add, next to the other duplicate tests (after `registerScan_nonCompliantRepeatOfCompliantRecord_lowersIt`):
  - `registerScan_nonCompliantRepeatOfChangedRecord_keepsIt`: `recentRecord(reader, tag, true)`, `recordConformityChangeRepository.existsByRecord(record)` returns `true`, request `isCompliant = false` → `saveAndFlush` never called; response `isCompliant = true`, `message = "Duplicate read ignored"`.
  - In `registerScan_nonCompliantRepeatOfCompliantRecord_lowersIt`, stub `existsByRecord` to `false` explicitly (Mockito's default is already `false`, but it states the precondition).
  - `registerScan_compliantRepeat_skipsChangeLookup`: compliant repeat of a compliant record → `existsByRecord` never called (the check only runs when the record would be lowered).

### Implementation

- [X] T011 [P] [US2] In `src/main/java/com/rfidback/repository/RecordRepository.java` (research R3, R4):
  - add `@Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from RecordEntity r where r.id = :id") Optional<RecordEntity> findWithLockById(@Param("id") UUID id);` with the Javadoc "The record, row-locked until the transaction ends, so concurrent compliance changes are applied one after the other (spec 005)."
  - add `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc` and extend its Javadoc: "Row-locked, so an Opérateur's change and a duplicate scan of the same record never interleave (spec 005, FR-006)."
  - imports: `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock`, `…Query`, `org.springframework.data.repository.query.Param`.
  - Run `mvn clean test -Dtest=TagScanApiTest`: its duplicate tests now run the locked lookup on H2. If H2 rejects `FOR UPDATE` with this `ORDER BY … LIMIT` query, apply research R4's fallback: remove `@Lock` from the lookup; in `TagService.ignoreDuplicate` (T014), when the record would be lowered, first call `recordRepository.findWithLockById(existing.getId())` to take the row lock, then run the `existsByRecord` check (a fresh query, so it sees a change committed before the lock). Note the fallback in research R4.
- [X] T012 [US2] In `src/main/java/com/rfidback/service/RecordService.java` (depends on T002, T003, T011):
  - add fields `private final RecordConformityChangeRepository recordConformityChangeRepository;` and `private final UserRepository userRepository;` after `readerRepository` (keep `@RequiredArgsConstructor`; field order = the constructor order T008 uses).
  - rewrite `updateRecordConformity(UUID recordId, boolean isCompliant)` (import `java.util.UUID` instead of the fully-qualified name): load with `recordRepository.findWithLockById(recordId)` (else `RecordNotFoundException` as today); if `record.isCompliant() == isCompliant`, return (idempotent, FR-003); otherwise resolve the author with `userRepository.findByUsername(currentUsername())` (`.orElseThrow(() -> new IllegalStateException("Logged-in user %s not found".formatted(...)))`), save a `RecordConformityChangeEntity` built with `record`, `previousCompliant = record.isCompliant()`, `newCompliant = isCompliant`, `author`, then `record.setCompliant(isCompliant)`. Drop the explicit `recordRepository.save(record)`: the entity is managed. Add a one-line comment above the method: "Locks the record so two Opérateurs clicking together give one change, not two (spec 005, research R3)."
  - add `private static String currentUsername()` copied from `src/main/java/com/rfidback/service/RegistrationService.java` (around line 265).
  - Run T008 and the PATCH cases of T009: they must pass.
- [X] T013 [P] [US2] In `src/main/resources/openapi/api.yaml`, `/records/{recordId}/conformity` → `patch` (around line 430): replace `description` with the text of contract §3, and add the `400`, `401`, `403` responses before `"404"`. Run `mvn generate-sources`: the `updateRecordConformity` delegate signature must not change.
- [X] T014 [US2] In `src/main/java/com/rfidback/service/TagService.java` (depends on T003):
  - add a `RecordConformityChangeRepository recordConformityChangeRepository` constructor parameter and `private final` field, right after `BucketRepository`.
  - in `ignoreDuplicate`, change the lowering condition to `!isCompliant && existing.isCompliant() && !recordConformityChangeRepository.existsByRecord(existing)` (keep the cheap checks first so the query only runs when the record would be lowered). Update the method's Javadoc: "…a non-compliant one still lowers the Record (never raises it), unless an Opérateur already changed it (spec 005, FR-006)."
  - Run T010 and the scan cases of T009: they must pass. Also rerun `TagScanApiTest` to confirm spec `004` behavior is unchanged.

**Checkpoint**: every real change leaves a row with its author, repeated values write nothing, and duplicate scans respect the Opérateur's decision.

---

## Phase 5: User Story 3 - Consulter l'historique de conformité d'une lecture (Priority: P2)

**Goal**: an Administrateur reads a record's changes, oldest first; an Opérateur gets `403` (FR-007; research R5, R6).

**Independent Test**: `mvn clean test -Dtest='RecordServiceTest,RecordApiTest,AccessMatrixSecurityTest'` passes; quickstart steps 2, 4, 6 and 9 give the expected results.

### Tests (write first, confirm they fail)

- [X] T015 [P] [US3] In `src/test/java/com/rfidback/service/RecordServiceTest.java` (after T008), add:
  - `listConformityChanges_mapsChangesInRepositoryOrder`: `recordRepository.findById(id)` returns a record, `recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(record)` returns two changes (authors `op1`, `admin1`; set `changedAt`) → the `ConformityChangesList` has two items in the same order with `previousIsCompliant`, `newIsCompliant`, `authorUsername`, `changedAt` copied.
  - `listConformityChanges_unknownRecord_throwsNotFound`.
- [X] T016 [US3] In `src/test/java/com/rfidback/controller/RecordApiTest.java` (after T009), add:
  - `history_afterTwoChanges_returnsThemOldestFirst`: Opérateur PATCHes `false` then `true` → admin `GET /api/records/{id}/conformity-history` → `200`, `$.changes.length()` = 2, `$.changes[0].previousIsCompliant` = `true`, `$.changes[0].newIsCompliant` = `false`, `$.changes[0].authorUsername` = `"record-op"`, `$.changes[1].newIsCompliant` = `true`, `changedAt` present.
  - `history_neverChanged_returnsEmptyList`: `$.changes.length()` = 0.
  - `history_unknownRecord_returns404`.
  - `history_asOperator_returns403`.
- [X] T017 [P] [US3] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java`, add `new Route(HttpMethod.GET, "/api/records/" + ID + "/conformity-history", null, ADMIN_ONLY)` right after the PATCH conformity route (around line 81).

### Implementation

- [X] T018 [US3] In `src/main/resources/openapi/api.yaml` (after T013, same file): add the `/records/{recordId}/conformity-history` path of contract §1 right after `/records/{recordId}/conformity`, and the `ConformityChange` and `ConformityChangesList` schemas of contract §2 right after `UpdateRecordConformityRequest` (around line 1146). Run `mvn generate-sources` and check that `RecordApiDelegate` now has `listRecordConformityChanges(UUID recordId)` and that `com.rfidback.generated.model.ConformityChangesList` exists.
- [X] T019 [US3] In `src/main/java/com/rfidback/service/RecordService.java` (after T012, T018), add `@Transactional(readOnly = true) public ConformityChangesList listConformityChanges(UUID recordId)`: `recordRepository.findById` (else `RecordNotFoundException`, same message as the PATCH), then map `recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(record)` with a private `toConformityChange` method, in the style of `toRecordSummary` (`authorUsername` = `change.getAuthor().getUsername()`).
- [X] T020 [US3] In `src/main/java/com/rfidback/controller/RecordController.java`, implement `listRecordConformityChanges(UUID recordId)` returning `ResponseEntity.ok(recordService.listConformityChanges(recordId))`. Replace the fully-qualified `java.util.UUID` in the file with an import.
- [X] T021 [US3] In `src/main/java/com/rfidback/configuration/SecurityConfig.java`, add `.requestMatchers(path(HttpMethod.GET, "/api/records/*/conformity-history")).hasRole(ADMINISTRATEUR)` on the line **before** `.requestMatchers(path(null, "/api/records/**"))` (around line 126), with a trailing comment `// spec 005, FR-007`. Run T015, T016 and T017: they must pass.

**Checkpoint**: the whole feature is in place. Run all three independent tests above.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T022 Run `mvn clean install` from the repository root: full build, OpenAPI regeneration, all tests green.
- [X] T023 Follow "Schema upgrade on an existing database" in [quickstart.md](quickstart.md): start the app on the existing `./data/rfidbackdb.mv.db` **without deleting it**, check the table `RECORD_CONFORMITY_CHANGE` and its index exist, restart once more, check nothing is created twice. Report the result. Don't touch the prod database.
- [X] T024 Run the manual steps 1–9 of [quickstart.md](quickstart.md) against the local app, and the optional concurrency check. Put `app.scan.duplicate-window` back to its original value afterwards. Report any step that doesn't match.
- [X] T025 Run "Latency of the last-10 list (SC-003)" in [quickstart.md](quickstart.md): load 200,000 records for one reader, poll from 3 loops every 500 ms for 2 minutes, and write down the p95 (target: under 0.200 s). Check once with `show-sql: true` that the list runs one query joining `tag`. Then rerun spec `004`'s latency run with a repeated UID (`.specify/specs/004-scan-tag-conformite/quickstart.md`, "Latency"), since the duplicate lookup now takes a row lock (research R4): p95 must still be under 0.200 s. Delete the load data and put `show-sql` back afterwards. Report both figures.
- [X] T026 [P] Update `.specify/specs/005-consultation-lectures-conformite/spec.md`:
  - FR-003, FR-006, FR-007: mark **Livré**, citing `RecordService.java`, `TagService.java`, `SecurityConfig.java`.
  - User Story 3: remove "non implémenté" from its title.
  - SC-003: add "mesuré via quickstart.md" with the p95 from T025.
  - Key entities: the history entity is `RecordConformityChangeEntity` (table `record_conformity_change`); remove "non implémentée".
  - SC-001, SC-002: replace the `[NEEDS CLARIFICATION]` with the tests that now cover them (`RecordApiTest`, `RecordServiceTest`); remove the "Aucun test" edge case.
  - Edge cases "Jeton envoyé mais non vérifié" and "Absence de traçabilité": describe the delivered behavior.
  - Drift table: update the "(implicite) opérateur identifié" row (delivered by spec `008`).
  - Refresh the header (`Feature Branch`: `005-consultation-lectures-conformite`, `Status`) and the evidence line numbers against the delivered code.
- [X] T027 [P] Update `.specify/specs/008-authentification-roles/spec.md`: add a row for `GET /api/records/{id}/conformity-history` (Administrateur only, spec `005`) to the access matrix just above `/api/records/**` (around line 122), and remove "historique des modifications de conformité (`005`)" from the out-of-scope line (around line 148).
- [X] T028 [P] In `.specify/specs/004-scan-tag-conformite/spec.md`, where the non-compliant repeat rule of FR-008 is described, add: since spec `005` (FR-006), a duplicate scan no longer changes a record an Opérateur has changed. In `.specify/specs/004-scan-tag-conformite/plan.md`, mark the "Spec `005`, conformity history" follow-up as superseded by spec `005` FR-006.
- [X] T029 [P] In `doc/20251116-use_cases.md`, add "Opérateur" to the `## Acteurs` section (lines 1–3), with one line: consults the last reads and toggles compliance on the production line (spec `005`, `008`).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (T001)**: none.
- **Foundational (T002 → T003)**: after T001. Blocks US2 and US3.
- **US1 (T004–T007)**: after T001 only. Can run alongside Foundational.
- **US2 (T008–T014)**: after T003. T009 needs T004 (same file).
- **US3 (T015–T021)**: after US2's T012 (same service) and T013 (same `api.yaml`). T015 needs T008, T016 needs T009 (same files).
- **Polish (T022–T029)**: after US3. T025 needs T006 and T007.

### Within Each User Story

- Tests before implementation; they must fail first (compile errors count).
- `api.yaml` before code relying on generated types (T018 before T019/T020).
- Repositories (T003, T011) before services (T012, T014).
- Same-file chains, never parallel: `RecordApiTest` T004 → T009 → T016; `RecordServiceTest` T008 → T015; `RecordService` T012 → T019; `api.yaml` T005 → T013 → T018; `RecordRepository` T007 → T011.

### Parallel Opportunities

- US1 (T004–T007) alongside Foundational (T002, T003); within US1, T005, T006 and T007 touch different files.
- T008 and T010 (different test files); T011 and T013 (repository and contract).
- T015 and T017 (different test files).
- T026–T029 (different doc files).

---

## Parallel Example: User Story 1

```bash
Task: "T005 Add 401/403 to the list route in src/main/resources/openapi/api.yaml"
Task: "T006 Add idx_record_reader_date in src/main/java/com/rfidback/entity/RecordEntity.java"
Task: "T007 Add @EntityGraph on the last-10 list in src/main/java/com/rfidback/repository/RecordRepository.java"
```

## Parallel Example: User Story 2

```bash
# Tests together:
Task: "T008 Create RecordServiceTest in src/test/java/com/rfidback/service/RecordServiceTest.java"
Task: "T010 Add FR-006 cases in src/test/java/com/rfidback/service/TagServiceTest.java"

# Then independent pieces together:
Task: "T011 Add findWithLockById and @Lock in src/main/java/com/rfidback/repository/RecordRepository.java"
Task: "T013 Update PATCH description and responses in src/main/resources/openapi/api.yaml"
```

## Parallel Example: User Story 3

```bash
Task: "T015 Add history cases in src/test/java/com/rfidback/service/RecordServiceTest.java"
Task: "T017 Add the history route to src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java"
```

---

## Implementation Strategy

### MVP First

1. T001, then Foundational (T002–T003) and US2 (T008–T014): changes are recorded from day one, even before anyone can read them through the API. A history that starts late can't be rebuilt.
2. **Validate**: `mvn clean test -Dtest='RecordServiceTest,RecordApiTest,TagServiceTest,TagScanApiTest'`.

US1 (T004–T007) can land at any point; doing it first gives `RecordApiTest` its setup, and its index should be in production before the table grows.

### Incremental Delivery

1. US1 → list behavior pinned, list fast on a season-sized table.
2. Foundational + US2 → history recorded, idempotent toggle, FR-006 lock.
3. US3 → history readable by Administrateurs.
4. Polish → build, schema upgrade, manual checks, latency figures, spec and doc updates.

US2 and US3 can ship separately: US2 alone changes nothing visible for Opérateurs and starts filling the table.
