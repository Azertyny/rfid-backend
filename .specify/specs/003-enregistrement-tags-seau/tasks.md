---
description: "Task list for 003 — Enregistrement des TAGs sur un seau"
---

# Tasks: Enregistrement des TAGs sur un seau (Tag registration on a bucket)

**Input**: Design documents from `.specify/specs/003-enregistrement-tags-seau/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included. The spec notes that nothing tests `registerTagsForBucket` (edge case "Aucun test", SC-002), and the plan lists the test classes. In each increment, write the tests first and check that they fail before implementing.

**Organization**: the spec has a single user story (US1: associate a list of tags with a bucket number), so every story task carries `[US1]`. It is split into three increments, each testable on its own:

- **Increment A (MVP)**: add-only registration through the API, with move confirmation and the two counts (FR-001–FR-004, FR-009, SC-001, SC-003).
- **Increment B**: reader mode, registration sessions and scan routing (FR-007, FR-008, FR-010).
- **Increment C**: the web pages (FR-006).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: User story (US1)
- Paths are relative to the repository root. Java sources: `src/main/java/com/rfidback/`; tests: `src/test/java/com/rfidback/`; front: `front/`.

## Conventions used by every task

- API-first: contract changes go into `src/main/resources/openapi/api.yaml` first, then `mvn generate-sources`; controllers implement the generated `*ApiDelegate`.
- Follow the existing style: Lombok (`@RequiredArgsConstructor`, `@Builder`, `@Getter`/`@Setter`), exceptions annotated with `@ResponseStatus` (see `exception/PickerHasBucketsException.java`), `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` for request-shape errors (see `service/ReaderService.java`), and duplicate inserts caught as `DataIntegrityViolationException` (see `ReaderService.createReader`).
- Name clash: the generated `com.rfidback.generated.model.ReaderMode` has the same simple name as the new entity enum `com.rfidback.entity.ReaderMode`. Import the entity one and fully qualify the generated one, as `UserService` does for `Role` (`com.rfidback.generated.model.Role.fromValue(...)`).
- Service unit tests: plain Mockito, no Spring context, mocks built in `@BeforeEach` and the service built with its constructor (see `service/TagServiceTest.java`). Time comes from a fixed `Clock` (`Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC)`), advanced by building a new service or using a mutable test clock.
- HTTP tests: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")` + `@Transactional`, with `user(name).roles("ADMINISTRATEUR")` and `csrf()` from `spring-security-test` (see `controller/ReaderApiTest.java`, `security/ReaderScanSecurityTest.java`). **Registration sessions look up the caller in `app_user`**, so each test that starts a session saves a `UserEntity` with a unique username (e.g. `"admin-" + UUID.randomUUID()` cut to 50 characters, role `ADMINISTRATEUR`, `enabled = true`, any `passwordHash`) and passes that username to `user(...)`. Reader and tag names are unique per test too.
- If a run fails with `NoClassDefFoundError` or "Unresolved compilation problem", it is the VS Code Java extension racing Maven (`CLAUDE.md`): rerun `mvn clean test`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: apply the contract changes and the shared configuration.

- [X] T001 Apply [contracts/openapi-tags-registration.md](contracts/openapi-tags-registration.md) §1–§2 to `src/main/resources/openapi/api.yaml`. Schemas: add `ReaderMode` (enum `PRODUCTION`, `ENREGISTREMENT`, with the contract's description); add `mode` (`$ref ReaderMode`) to `UpdateReader`; add `mode` to the second `allOf` member of `Reader` and to its `required` list; add `moveConfirmed` (boolean, default `false`, with the contract's description) to `RegisterTagsRequest`; in `RegisterTagsResponse`, change `registeredCount`'s description to "Number of unique, non-blank tag uids in this request", add `totalCount` (integer, "Number of tags linked to the bucket after this registration") and make it required; add `TagInOtherBucket`, `TagsInOtherBuckets`, `StartRegistrationSession`, `RegistrationRead` (`bucketNumber` nullable), `RegistrationSession`, `ReaderBusy` and `SaveRegistrationSession` exactly as in the contract. Paths: on `/tags/buckets/{bucketNumber}`, replace the description with the contract's add-only wording and add `"401"`, `"403"`, and `"409"` with content `TagsInOtherBuckets`; on `/tags/scan`, append to the description "For a reader in ENREGISTREMENT mode, the read is kept for tag registration (or ignored when no registration session is open), no record is created, and isCompliant is always true."; add `/tags/registration-sessions` (`post`, `operationId: startRegistrationSession`), `/tags/registration-sessions/{sessionId}` (uuid path param; `get` `getRegistrationSession`, `delete` `cancelRegistrationSession`) and `/tags/registration-sessions/{sessionId}/save` (`post` `saveRegistrationSession`), all with `tags: [Registration]` and the responses in the contract (`409` on start with content `ReaderBusy`, `409` on save with content `TagsInOtherBuckets`, `401`/`403` refs everywhere); add a `Registration` entry to the top-level `tags:` list if the file has one
- [X] T002 Run `mvn generate-sources` then `mvn -q compile`. Expected: compile succeeds; `RegistrationApiDelegate` exists with `startRegistrationSession`, `getRegistrationSession`, `cancelRegistrationSession`, `saveRegistrationSession`; `RegisterTagsRequest` has `getMoveConfirmed()`, `RegisterTagsResponse` has `setTotalCount(Integer)`, `Reader` has `setMode(...)`. Write down the exact generated signatures and use them in later tasks
- [X] T003 [P] Add `app.registration.session-timeout: 5m` under the existing `app:` key in `src/main/resources/application.yml` (research R5)
- [X] T004 [P] Create `src/main/java/com/rfidback/configuration/ClockConfig.java`: `@Configuration` with `@Bean Clock clock()` returning `Clock.systemDefaultZone()`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the schema ([data-model.md](data-model.md)), repositories, exceptions and error bodies that every increment uses.

**⚠️ CRITICAL**: complete before the increment phases.

- [X] T005 Create `src/main/java/com/rfidback/entity/ReaderMode.java` (`public enum ReaderMode { PRODUCTION, ENREGISTREMENT }`), and in `src/main/java/com/rfidback/entity/ReaderEntity.java` add `@Builder.Default @ColumnDefault("'PRODUCTION'") @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private ReaderMode mode = ReaderMode.PRODUCTION;` with a comment that the inner quotes make it a SQL string literal for `ddl-auto` (research R1)
- [X] T006 [P] Create `src/main/java/com/rfidback/entity/RegistrationSessionEntity.java`: table `registration_session`; `UUID id` (`@GeneratedValue(strategy = GenerationType.UUID)`); `@ManyToOne(fetch = LAZY) @JoinColumn(name = "reader_id", nullable = false, unique = true) ReaderEntity reader`; `@ManyToOne(fetch = LAZY) @JoinColumn(name = "started_by_id", nullable = false) UserEntity startedBy`; `OffsetDateTime startedAt` and `OffsetDateTime lastActivityAt` (both `nullable = false`, set by the service, not by `@CreationTimestamp`); `@OneToMany(mappedBy = "session", cascade = CascadeType.ALL, orphanRemoval = true) @OrderBy("firstReadAt ASC") @Builder.Default List<RegistrationReadEntity> reads = new ArrayList<>()`; Lombok as in `TagEntity`
- [X] T007 [P] Create `src/main/java/com/rfidback/entity/RegistrationReadEntity.java`: table `registration_read` with `@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"session_id", "uid"}))`; `UUID id`; `@ManyToOne(fetch = LAZY) @JoinColumn(name = "session_id", nullable = false) RegistrationSessionEntity session`; `@Column(nullable = false, length = 50) String uid`; `@Column(nullable = false) OffsetDateTime firstReadAt`
- [X] T008 [P] Create `src/main/java/com/rfidback/repository/RegistrationSessionRepository.java` (`JpaRepository<RegistrationSessionEntity, UUID>` with `Optional<RegistrationSessionEntity> findByReader(ReaderEntity reader)` and `@Transactional @Modifying @Query("update RegistrationSessionEntity s set s.lastActivityAt = :now where s.id = :id") int touch(@Param("id") UUID id, @Param("now") OffsetDateTime now)`) and `src/main/java/com/rfidback/repository/RegistrationReadRepository.java` (`JpaRepository<RegistrationReadEntity, UUID>` with `boolean existsBySessionAndUid(RegistrationSessionEntity session, String uid)`)
- [X] T009 [P] In `src/main/java/com/rfidback/repository/TagRepository.java` add `List<TagEntity> findAllByUidIn(Collection<String> uids)` and `long countByBucket(BucketEntity bucket)`
- [X] T010 [P] Create the exceptions in `src/main/java/com/rfidback/exception/`: `TagsInOtherBucketsException` (RuntimeException holding `List<TagInOtherBucket>` from the generated model, with a getter), `ReaderBusyException` (holding `String startedBy` and `OffsetDateTime startedAt`, with getters), and `RegistrationSessionNotFoundException` (`@ResponseStatus(HttpStatus.NOT_FOUND)`, message constructor, like `ReaderNotFoundException`)
- [X] T011 In `src/main/java/com/rfidback/controller/ApiExceptionHandler.java` add two `@ExceptionHandler`s: `TagsInOtherBucketsException` → `409` with a generated `TagsInOtherBuckets` body (`message` = "Some tags belong to another bucket; confirm the move with moveConfirmed=true", `tags` = the exception's list); `ReaderBusyException` → `409` with a generated `ReaderBusy` body (`message` = "This reader is already used by another registration session", `startedBy`, `startedAt`). Update the class Javadoc to say it also renders the 409 bodies that carry data
- [X] T012 Run `mvn clean test`. Expected: the whole existing suite is green, the app context starts with the two new tables, and existing readers get `mode = PRODUCTION`

**Checkpoint**: schema and shared types are in place; nothing behaves differently yet.

---

## Phase 3: User Story 1, increment A — add-only registration API (Priority: P1) 🎯 MVP

**Goal**: `POST /api/tags/buckets/{n}` adds tags without detaching the bucket's other tags, refuses (`409`) to move tags from another bucket unless `moveConfirmed` is `true`, answers `400` when no UID is usable, and returns `registeredCount` and `totalCount` (FR-001–FR-004, FR-009, SC-001, SC-003; research R7, R8).

**Independent Test**: `mvn clean test -Dtest='TagServiceTest,TagRegistrationApiTest'`. Covered: add-only (bucket with T1, register T2 → both linked, `registeredCount 1`, `totalCount 2`), bucket auto-created, dedup and blanks, `400` on all-blank, `409` listing `{uid, bucketNumber}` with nothing written, `200` with `moveConfirmed: true`, and re-registering a tag already on the same bucket is not a move.

### Tests for increment A (write first, make sure they fail)

- [X] T013 [P] [US1] In `src/test/java/com/rfidback/service/TagServiceTest.java` add `registerTagsForBucket` tests with the existing mocks: `addsTagsWithoutDetachingExistingOnes` (no `setBucket(null)` on the bucket's other tags; `countByBucket` stubbed to 2 → `totalCount 2`, `registeredCount 1`), `createsBucketWhenUnknown`, `dedupsAndIgnoresBlankUids` (`[" A ", "A", "", "B"]` → `registeredCount 2`), `allBlankUids_throws400` (`ResponseStatusException` with `BAD_REQUEST`, nothing saved), `tagInOtherBucket_withoutConfirmation_throwsConflictAndWritesNothing` (`TagsInOtherBucketsException` whose list holds the uid and the other bucket's number; `verify(tagRepository, never()).saveAll(any())` and no bucket saved), `tagInOtherBucket_withConfirmation_movesIt`, `tagAlreadyInSameBucket_isNotAConflict`
- [X] T014 [P] [US1] Create `src/test/java/com/rfidback/controller/TagRegistrationApiTest.java` (MockMvc, `@Transactional`, admin via `user("admin").roles("ADMINISTRATEUR")` plus `csrf()`; no `app_user` row needed for this route). Use bucket numbers chosen at random above 100000 so tests don't collide. Cases: `POST /api/tags/buckets/{n}` twice with different UIDs → second response `registeredCount 1`, `totalCount 2`, and `GET /api/buckets/{id}` (find the id via `BucketRepository.findByNumber`) lists both tags; `{"uids":["  "]}` → `400`; a tag on bucket n1 registered on bucket n2 → `409`, the body's `tags[0].uid` and `tags[0].bucketNumber == n1`, and the tag is still on n1; the same with `"moveConfirmed": true` → `200` and the tag is on n2

### Implementation for increment A

- [X] T015 [US1] Rewrite `registerTagsForBucket` in `src/main/java/com/rfidback/service/TagService.java` following [data-model.md](data-model.md) §"Tag and Bucket", steps 1–5: add a public overload `RegisterTagsResponse registerTagsForBucket(Integer bucketNumber, Collection<String> uids, boolean moveConfirmed)` holding the logic (Save will reuse it in increment B), and make the existing `(Integer, RegisterTagsRequest)` method delegate to it with `Boolean.TRUE.equals(request.getMoveConfirmed())`. Load existing tags with one `findAllByUidIn` call instead of one `findByUid` per UID. Compute conflicts before any write: a tag whose `bucket` is non-null and whose `bucket.getNumber()` differs from `bucketNumber` (compare numbers, since the target bucket may not exist yet). Then find or create the bucket, set `bucket` on every tag, `saveAll`, and set `totalCount` from `countByBucket(bucket)` after the save. Remove the block that detached `previousTags`
- [X] T016 [US1] Run `mvn clean test -Dtest='TagServiceTest,TagRegistrationApiTest,AccessMatrixSecurityTest'`. Expected: green. The existing matrix row `POST /api/tags/buckets/9999` still returns `200` for the admin (it re-registers `MATRIX-TAG` on the same bucket, which is not a move)

**Checkpoint**: increment A works on its own: any API client gets add-only registration with move protection.

---

## Phase 4: User Story 1, increment B — reader mode, registration sessions, scan routing (Priority: P1)

**Goal**: an Administrateur switches a reader to `ENREGISTREMENT`. Its scans then go into the open registration session of that reader, with no `Record` and no `Tag`. One session per reader, owned by whoever started it, expiring after the timeout of inactivity. Save registers the reads through increment A and closes the session (FR-007, FR-008, FR-010; research R1–R6, R9).

**Independent Test**: `mvn clean test -Dtest='RegistrationServiceTest,RegistrationApiTest,ReaderApiTest,ReaderScanSecurityTest,AccessMatrixSecurityTest'`. The rows of the status table in [contracts/openapi-tags-registration.md](contracts/openapi-tags-registration.md) §3 all pass, including a full flow with a real reader token: mode → Start → two scans of the same tag plus one other → `GET` shows two reads → Save → bucket holds both tags, `RecordRepository.count()` unchanged, session gone.

### Tests for increment B (write first, make sure they fail)

- [X] T017 [P] [US1] Create `src/test/java/com/rfidback/service/RegistrationServiceTest.java` (Mockito; mocks for `RegistrationSessionRepository`, `RegistrationReadRepository`, `ReaderRepository`, `UserRepository`, `TagRepository`, `TagService`; fixed `Clock`; timeout `Duration.ofMinutes(5)`; set the current user with `SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("alice", null, List.of()))` and clear it in `@AfterEach`). Cases: `start_createsSession` (reader active + `ENREGISTREMENT`, no existing session); `start_readerInProduction_throws400`; `start_readerDisabled_throws400`; `start_unknownReader_throwsReaderNotFound`; `start_readerBusyWithLiveSession_throwsReaderBusy` (carries the owner's username and `startedAt`); `start_readerWithExpiredSession_deletesItAndCreatesNew` (`lastActivityAt` 6 minutes before now); `recordRead_noSession_returnsIgnored` (no save); `recordRead_storesUidOnceAndTouchesSession` (second call with `existsBySessionAndUid` true → no second save; `lastActivityAt` = now); `recordRead_blankUid_ignored`; `recordRead_expiredSession_deletesItAndReturnsIgnored`; `recordRead_concurrentDuplicateInsert_isIgnored` (`existsBySessionAndUid` false but `saveAndFlush` throws `DataIntegrityViolationException` → response "Registration read", `touch` still called); `start_concurrentInsert_throwsReaderBusyWithWinnerOwner` (`saveAndFlush` throws `DataIntegrityViolationException`, the second `findByReader` returns the other Administrateur's session → `ReaderBusyException` carrying that owner); `start_concurrentInsert_winnerAlreadyGone_retriesOnce` (second `findByReader` empty → one more insert, which succeeds); `get_byOtherUser_throws403`; `get_expired_throws404AndDeletes` (only checks that `delete` is called; T019 checks that the deletion is really committed); `get_updatesLastActivityOnlyWhenOlderThan30s`; `get_flagsCurrentBucketOfEachRead` (`findAllByUidIn` returns a tag on bucket 7 → that read's `bucketNumber == 7`, others null); `cancel_deletesSession`; `save_registersReadsThroughTagServiceAndDeletesSession` (verify `tagService.registerTagsForBucket(n, uids, moveConfirmed)`); `save_noReads_throws400`; `save_conflict_keepsSession` (`TagService` throws `TagsInOtherBucketsException` → rethrown, `delete` never called); `closeForReader_deletesOpenSession`
- [X] T018 [P] [US1] In `src/test/java/com/rfidback/controller/ReaderApiTest.java` add: `create_defaultsToProductionMode` (`mode == "PRODUCTION"` in the `201` body and in `GET /api/readers`); `patch_mode_switchesToEnregistrementAndBack`; `patch_emptyBody_returns400` still passes with the new field (adjust its expected message if it asserts one); `patch_modeToProduction_closesOpenSession` (save a `RegistrationSessionEntity` for the reader directly through the repository, PATCH `{"mode":"PRODUCTION"}`, then `RegistrationSessionRepository.findByReader` is empty); `patch_disable_closesOpenSession`
- [X] T019 [P] [US1] Create `src/test/java/com/rfidback/controller/RegistrationApiTest.java` (MockMvc, `@Transactional`; helpers: `UserEntity admin()` saving a unique Administrateur, `ReaderEntity registrationReader()` saving an active reader with `mode(ReaderMode.ENREGISTREMENT)`, `scan(token, uid)` posting to `/api/tags/scan` with `x-api-token`). Cases: `fullFlow` (as described in the Independent Test above; also check the scan responses have `isCompliant: true` even when the request sent `false`, and that `TagRepository.findByUid` is empty for the scanned UIDs before Save); `scanWithoutSession_isIgnoredAndCreatesNothing`; `start_onProductionReader_returns400`; `start_onBusyReader_returns409WithStartedBy` (second Administrateur); `get_bySecondAdministrator_returns403`; `cancel_thenGet_returns404`; `save_withTagInOtherBucket_returns409ThenConfirmedReturns200AndClosesSession`; `save_withoutReads_returns400`; `expiredSession_canBeReplaced` (save a session directly with `lastActivityAt` 10 minutes ago, then `POST` start → `201`); `get_expiredSession_returns404AndDeletionIsCommitted`, annotated `@Transactional(propagation = Propagation.NOT_SUPPORTED)` so it runs outside the class's test transaction (inside it, a rollback would go unnoticed because the test reads its own uncommitted changes): save an expired session, `GET` it → `404`, then `RegistrationSessionRepository.findById` is empty; delete the rows it created (session, reader, user) in a `finally` block
- [X] T020 [P] [US1] In `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java` add `scan_fromEnregistrementReader_returns200WithoutRecord`: save a reader with `mode(ReaderMode.ENREGISTREMENT)`, scan with its token and `"isCompliant":false` → `200`, `isCompliant` true, no `Set-Cookie`, and `RecordRepository.count()` unchanged
- [X] T021 [P] [US1] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java` add `ADMIN_ONLY` rows for `POST /api/tags/registration-sessions` (body `{"readerId":"<ID>"}`), `GET` and `DELETE /api/tags/registration-sessions/<ID>`, and `POST /api/tags/registration-sessions/<ID>/save` (body `{"bucketNumber":1}`). The admin outcome is a `404` or `400` since `<ID>` does not exist; check how the class asserts "allowed" (anything but `401`/`403`) and follow it

### Implementation for increment B

- [X] T022 [US1] Create `src/main/java/com/rfidback/service/RegistrationService.java` (`@Service`, `@RequiredArgsConstructor`; dependencies: the two registration repositories, `ReaderRepository`, `UserRepository`, `TagRepository`, `TagService`, `Clock`, and `@Value("${app.registration.session-timeout}") Duration sessionTimeout` — use an explicit constructor if Lombok cannot carry the `@Value`). Public methods: `RegistrationSession start(UUID readerId)`; `RegistrationSession get(UUID sessionId)`; `void cancel(UUID sessionId)`; `RegisterTagsResponse save(UUID sessionId, SaveRegistrationSession request)`; `ScanTagResponse recordRead(ReaderEntity reader, String uid)`; `void closeForReader(ReaderEntity reader)`. **Transactions** (research R3a): `get`, `cancel` and `save` are `@Transactional(noRollbackFor = RegistrationSessionNotFoundException.class)`, so deleting an expired session is committed even though the method then answers `404`; `closeForReader` is `@Transactional`; `start` and `recordRead` are **not** `@Transactional`: they chain repository calls that each run in their own transaction, so a unique-constraint failure only rolls back the insert that caused it. Never keep using a transaction after catching a `DataIntegrityViolationException` inside it: the repository has already marked it rollback-only, and the commit would fail with `UnexpectedRollbackException` (`500`). Rules, from [data-model.md](data-model.md) and research R3–R6: current user = `userRepository.findByUsername(SecurityContextHolder...getName())`; a session is expired when `lastActivityAt.plus(sessionTimeout)` is before `OffsetDateTime.now(clock)`, and an expired session is deleted (then `flush()`) wherever it is found; `start` validates the reader (`ReaderNotFoundException`, then `400` if inactive or not `ENREGISTREMENT`), throws `ReaderBusyException` for a live session, and deletes an expired one (its own transaction) before inserting; if the insert (`saveAndFlush`, its own transaction) throws `DataIntegrityViolationException`, another Administrateur started first: call `findByReader` again (a fresh transaction) and throw `ReaderBusyException` with that session's owner and `startedAt`, or, if it is already gone, retry the insert once; `get`/`cancel`/`save` load the session (`RegistrationSessionNotFoundException` if missing or expired) and throw `ResponseStatusException(FORBIDDEN)` when `startedBy` is not the current user; `get` sets `lastActivityAt` only when it is more than 30 s old; `recordRead` trims the UID, ignores blank ones, finds the reader's session (none or expired → response with message "Registration read ignored: no session started"), inserts the read with `saveAndFlush` unless `existsBySessionAndUid` (a `DataIntegrityViolationException` here means a concurrent read of the same tag won; ignore it, which is safe because that insert ran in its own transaction), then sets `lastActivityAt` = now with `RegistrationSessionRepository.touch(sessionId, now)` (an update query, so it does not depend on entities loaded before the failed insert), and returns `uid`, `isCompliant` = `true`, `processedAt` = now, `message` = "Registration read"; `save` throws `400` when the session has no read, calls `tagService.registerTagsForBucket(request.getBucketNumber(), uids, Boolean.TRUE.equals(request.getMoveConfirmed()))`, and deletes the session only after that call returns, so a `409` keeps it; the `RegistrationSession` DTO lists reads oldest first and fills `bucketNumber` from one `tagRepository.findAllByUidIn(uids)` call
- [X] T023 [US1] Create `src/main/java/com/rfidback/controller/RegistrationController.java` implementing `RegistrationApiDelegate` (`@Service`, `@RequiredArgsConstructor`, like `TagController`): `startRegistrationSession` → `201`, `getRegistrationSession` → `200`, `cancelRegistrationSession` → `204`, `saveRegistrationSession` → `200`, each a single call to `RegistrationService`
- [X] T024 [US1] In `src/main/java/com/rfidback/controller/TagController.java`, make `scanTag` call `registrationService.recordRead(reader, scanTagRequest.getUid())` when `reader.getMode() == ReaderMode.ENREGISTREMENT`, and `tagService.registerScan` otherwise. Add a one-line comment that the branch lives here because `RegistrationService` depends on `TagService` (research R2)
- [X] T025 [US1] In `src/main/java/com/rfidback/service/ReaderService.java`: inject `RegistrationService`; in `updateReader`, require at least one of `active`/`mode` (`400` "Provide an active state and/or a mode"), apply `mode` when present (map with `ReaderMode.valueOf(request.getMode().name())`), and call `registrationService.closeForReader(readerEntity)` when the reader ends up disabled or in `PRODUCTION`; in `toModel`, set `mode` with `com.rfidback.generated.model.ReaderMode.fromValue(entity.getMode().name())`
- [X] T026 [US1] If `src/test/java/com/rfidback/service/ReaderServiceTest.java` builds `ReaderService` with its constructor, add a mocked `RegistrationService` argument. Then run `mvn clean test`. Expected: the whole suite is green

**Checkpoint**: the full registration flow works through the API with a real reader token; production readers behave as before.

---

## Phase 5: User Story 1, increment C — web pages (Priority: P1)

**Goal**: a logged-in Administrateur registers tags from `front/tags.html`, switches reader modes from `front/readers.html`, and the dashboard ignores registration readers (FR-006; contract §4).

**Independent Test**: [quickstart.md](quickstart.md) "Manual" steps 1–7 and 9 in a browser against the local app.

- [X] T027 [US1] Create `front/tags.html` following [contracts/openapi-tags-registration.md](contracts/openapi-tags-registration.md) §4. Copy the head, navbar and script includes (`config.js`, `auth.js`) from `front/readers.html`, with the new "Tags" nav button marked `btn-primary`. States: **idle** (reader `<select>` filled from `GET /readers` filtered on `active && mode === 'ENREGISTREMENT'`; if none, an alert with a link to `readers.html` and Démarrer disabled; if one, preselected), **running** (bucket number input, `min=1`; table of reads with a warning badge "Seau N" when `bucketNumber` is set and differs from the typed number; Enregistrer and Annuler buttons; polling `GET /tags/registration-sessions/{id}` with `apiFetch(..., { silent: true })` every `CONFIG.POLLING_INTERVAL`, without starting a new poll while one is in flight). Start: on `409`, show "Lecteur utilisé par {startedBy} depuis {startedAt}" (format with `toLocaleTimeString`). Poll `404`: stop polling, show "Session expirée", back to idle. Save: `POST …/save` with `moveConfirmed: false`; on `409`, a Bootstrap modal listing each `uid → Seau bucketNumber`, whose confirm button resends with `moveConfirmed: true`; on `200`, a success alert "{registeredCount} tag(s) enregistré(s) — seau N contient maintenant {totalCount} tag(s)", back to idle. Cancel: `DELETE`, back to idle. On `pagehide` with a session open: `fetch(CONFIG.API_URL + '/tags/registration-sessions/' + id, { method: 'DELETE', keepalive: true, credentials: 'same-origin', headers: { 'X-XSRF-TOKEN': getCookie('XSRF-TOKEN') } })`. Guard with `await requireRole('ADMINISTRATEUR')`. Escape tag UIDs and usernames before inserting them into HTML
- [X] T028 [P] [US1] In `front/readers.html`: add a Mode column (badge "Production" or "Enregistrement") and a button that calls `apiFetch('/readers/' + id, { method: 'PATCH', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({ mode }) })` to switch to the other mode, then reloads the list; add the "Tags" nav link
- [X] T029 [P] [US1] In `front/index.html`, in the reader filter at the line that keeps `r.active !== false`, also skip readers with `r.mode === 'ENREGISTREMENT'`; add the "Tags" nav link
- [X] T030 [P] [US1] Add the nav link `<a href="tags.html" data-admin-only class="btn btn-sm btn-outline-light border-0">` (with an icon matching the other links, e.g. `fa-tags`, and the label "Tags") to `front/pickers.html`, `front/reader.html` and `front/users.html`, in the same position as in the other pages. *Done for `pickers.html` and `users.html` (and `index.html` in T029); `reader.html` has no navigation bar, so no link was added there*

**Checkpoint**: quickstart manual steps 1–7 and 9 pass in the browser.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T031 [P] In `CLAUDE.md`, add `Registration` to the list of OpenAPI tags in the "API-first via OpenAPI codegen" section
- [X] T032 [P] In `.specify/specs/003-enregistrement-tags-seau/spec.md`, mark FR-005, the "Aucune authentification" edge case and the third Drift row as delivered by spec `008`; mark FR-001, FR-006–FR-010, SC-003 and the "Aucun test" edge case as delivered by this feature, citing the new files, the same way spec `006` marks FR-007 "Livré par la feature `001`"
- [X] T033 [P] In `.specify/specs/004-scan-tag-conformite/spec.md` FR-003 and `.specify/specs/007-tableau-de-bord/spec.md`, add a one-line note pointing to research R2 (response sent to a registration reader) and R9 (dashboard skips registration readers) of spec `003`
- [X] T034 Run `mvn clean install`. Expected: build succeeds and every test is green
- [X] T035 Run the [quickstart.md](quickstart.md) checks that need a running app: "Schema upgrade on an existing database" against the existing `./data/rfidbackdb.mv.db`, then the manual steps 1–9. Report any step that fails instead of marking this task done
  - *Status 2026-09-24*: schema upgrade checked on a copy of the dev database (existing reader → `PRODUCTION`, both tables created); quickstart steps 2–6, 8 and 9 checked with curl against the running jar, plus 80 concurrent scans (all `200`, each tag stored once). Browser steps 1 and 3–7 (including session expiry, with the timeout set to 1 minute) passed in a manual run by the user, the same day.

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001–T004)**: T002 needs T001. T003 and T004 are independent.
- **Foundational (T005–T012)**: needs Setup. T005 before T006 (the session entity references the reader). T006–T010 can run in parallel after T005 and T002. T011 needs T010. T012 closes the phase.
- **Increment A (T013–T016)**: needs Foundational (T009, T010, T011).
- **Increment B (T017–T026)**: needs increment A, because `RegistrationService.save` calls the new `TagService.registerTagsForBucket(Integer, Collection, boolean)` overload (T015).
- **Increment C (T027–T030)**: needs increment B's API. T028–T030 are front files independent of each other and of T027.
- **Polish (T031–T035)**: after increment C. T034 and T035 last.

### Within each increment

- Tests first (they must fail), then implementation, then the increment's test run.
- Increment B: T022 (service) → T023 (controller) and T024 (scan routing) → T025 (reader mode) → T026.

### Parallel opportunities

- Setup: T003 ∥ T004 (and both ∥ T001).
- Foundational: T006 ∥ T007 ∥ T008 ∥ T009 ∥ T010.
- Increment A: T013 ∥ T014.
- Increment B: T017 ∥ T018 ∥ T019 ∥ T020 ∥ T021.
- Increment C: T028 ∥ T029 ∥ T030, alongside T027.
- Polish: T031 ∥ T032 ∥ T033.

## Parallel Example: increment B tests

```text
Task: "T017 RegistrationServiceTest in src/test/java/com/rfidback/service/RegistrationServiceTest.java"
Task: "T018 mode cases in src/test/java/com/rfidback/controller/ReaderApiTest.java"
Task: "T019 RegistrationApiTest in src/test/java/com/rfidback/controller/RegistrationApiTest.java"
Task: "T020 registration scan case in src/test/java/com/rfidback/security/ReaderScanSecurityTest.java"
Task: "T021 matrix rows in src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java"
```

## Implementation Strategy

### MVP first (increment A)

1. Setup and Foundational (T001–T012).
2. Increment A (T013–T016): the API is add-only and protects tags in other buckets. Existing API callers already benefit.
3. **Stop and validate**: `mvn clean test -Dtest='TagServiceTest,TagRegistrationApiTest'`.

### Incremental delivery

1. Increment B: the registration flow works end to end through the API with a real reader token (testable with curl, [quickstart.md](quickstart.md) steps 2–8).
2. Increment C: the pages make it usable by an Administrateur.
3. Polish: docs, cross-spec notes, full build, manual quickstart.

Each increment leaves the suite green, and production readers behave the same throughout.

---

## Phase 7: Convergence

- [X] T036 Make the last-resort start conflict in `src/main/java/com/rfidback/service/RegistrationService.java` (`insertSession`, the retry after a concurrent insert also fails and no winning session is found) answer `409` with a body the page can read instead of Spring's default error body: either throw `ReaderBusyException` with a message and no owner (then drop `startedBy`/`startedAt` from `ReaderBusy.required` in `src/main/resources/openapi/api.yaml`), or keep the `409` and make `front/tags.html` show "Lecteur occupé, réessayez" when `startedBy` is missing; add a `RegistrationServiceTest` case for the double race, per contract §2 and FR-010 (contradicts)

---

## Phase 8: Convergence

- [X] T037 Write the SC-002 tests first and check that they fail: in `src/test/java/com/rfidback/controller/TagRegistrationApiTest.java`, `POST /api/tags/buckets/{n}` with exactly 100 distinct UIDs → `200` with `registeredCount 100`, and with 101 → `400` with no bucket created and no tag stored; in `src/test/java/com/rfidback/service/RegistrationServiceTest.java`, `save` on a session with 101 reads → `ResponseStatusException` `BAD_REQUEST`, `tagService.registerTagsForBucket` never called and the session not deleted, and with 100 reads → registered; in `src/test/java/com/rfidback/controller/RegistrationApiTest.java`, a session holding 101 reads (insert them with `RegistrationReadRepository`) → save `400`, then `GET` on the session still `200`, per SC-002 (missing)
- [X] T038 Add `maxItems: 100` to `RegisterTagsRequest.uids` in `src/main/resources/openapi/api.yaml`, with a description saying more than 100 UIDs are refused with `400`; run `mvn generate-sources` and check that the generated `RegisterTagsRequest.getUids()` carries `@Size(min = 1, max = 100)` and that the request gets `400`, not `500`; mirror the change in `.specify/specs/003-enregistrement-tags-seau/contracts/openapi-tags-registration.md` (§1 schema and a new row in the §3 status table), per FR-011 (missing)
- [X] T039 In `src/main/java/com/rfidback/service/RegistrationService.java`, make `save` refuse a session with more than 100 reads with `ResponseStatusException(BAD_REQUEST, "A registration covers at most 100 tags")` before calling `TagService`, so nothing is written and the session stays open; keep the limit in one constant that `TagService.registerTagsForBucket` also enforces for callers that bypass the API validation, per FR-011 (missing)
- [X] T040 In `front/tags.html`, when more than 100 tags are listed, show a warning next to the tag count ("100 tags au maximum par enregistrement") and have Enregistrer show that message instead of sending the request; on a `400` from save, keep the existing message, per FR-011 (missing)
