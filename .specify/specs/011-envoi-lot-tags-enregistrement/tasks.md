---

description: "Task list for feature 011 — envoi groupé des tags par le lecteur d'enregistrement"
---

# Tasks: Envoi groupé des tags par le lecteur d'enregistrement

**Input**: Design documents from `.specify/specs/011-envoi-lot-tags-enregistrement/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-registration-reads.md](contracts/openapi-registration-reads.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan names the test classes to add or extend (research R9), and every earlier feature ships with its tests.

**Organization**: one phase per user story of the spec: US1 (P1) the enregistreur sends its tags in one call, US2 (P2) only an enregistreur may use the route, US3 (P3) the tag-by-tag path keeps working. US1 carries the route itself; US2 adds the refusals on top of it; US3 only proves nothing else moved.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to
- Paths are relative to the repository root

## Path Conventions

Single Spring Boot project: `src/main/java/com/rfidback/…`, `src/main/resources/…`, `src/test/java/com/rfidback/…`. Generated OpenAPI code lands in `target/generated-sources/openapi` and is never edited by hand: change `src/main/resources/openapi/api.yaml`, then run `mvn generate-sources`. Schemas with **required** properties get a required-args constructor from the generator.

---

## Phase 1: Setup

**Purpose**: green starting point.

- [X] T001 Run `mvn clean test` from the repository root on branch `011-envoi-lot-tags-enregistrement` and confirm all tests pass before any change. If it fails with `NoClassDefFoundError` on a bare class name or "Unresolved compilation problem", that's the VS Code Java extension racing Maven (see `CLAUDE.md`): rerun, don't fix code.

---

## Phase 2: Foundational

**Purpose**: contract, security route and repository queries. Blocks all stories.

- [X] T002 Edit `src/main/resources/openapi/api.yaml` as described in [contracts/openapi-registration-reads.md](contracts/openapi-registration-reads.md):
  - add the path `/tags/registration-reads` (operation `recordRegistrationReads`, tag `Registration`, `security: [ReaderApiToken: []]`) right after `/tags/registration-sessions/{sessionId}/save`, in the same comment/indent style as its neighbours;
  - add the schemas `RegistrationReadsRequest` and `RegistrationReadsResponse` next to the other registration schemas (`SaveRegistrationSession`, `RegistrationSession`);
  - in `components.securitySchemes.ReaderApiToken.description`, name `POST /tags/registration-reads` next to `POST /tags/scan`; in the comment on the top-level `security:` (line ~13), mention it as well.
  Run `mvn generate-sources` and check that `RegistrationApiDelegate` now has `recordRegistrationReads(RegistrationReadsRequest)` returning `ResponseEntity<RegistrationReadsResponse>`.
- [X] T003 [P] In `src/main/java/com/rfidback/configuration/SecurityConfig.java`, make the `@Order(1)` reader chain cover the new route (research R2): add a constant `READER_REGISTRATION_READS_PATH = "/api/tags/registration-reads"`; `securityMatcher` becomes `new OrRequestMatcher(path(null, READER_SCAN_PATH), path(null, READER_REGISTRATION_READS_PATH))` (import `org.springframework.security.web.util.matcher.OrRequestMatcher`); add `.requestMatchers(path(HttpMethod.OPTIONS, READER_REGISTRATION_READS_PATH)).permitAll()` next to the scan one. Update the comment above the bean ("reader devices on /api/tags/scan and /api/tags/registration-reads"). Also update the comment at the top of `src/main/java/com/rfidback/security/ReaderApiTokenAuthenticationFilter.java` ("reader scans" → "reader scans and registration batches").
- [X] T004 [P] In `src/main/java/com/rfidback/repository/RegistrationSessionRepository.java`, add
  ```java
  // Serializes the batches of one reader (spec 011, research R5); loads the owner like findByReader.
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
  @EntityGraph(attributePaths = { "reader", "startedBy" })
  @Query("select s from RegistrationSessionEntity s where s.reader = :reader")
  Optional<RegistrationSessionEntity> findLockedByReader(@Param("reader") ReaderEntity reader);
  ```
  (imports `org.springframework.data.jpa.repository.Lock`, `org.springframework.data.jpa.repository.QueryHints`, `jakarta.persistence.LockModeType`, `jakarta.persistence.QueryHint`). No `@Transactional` on it: callers must already be in a transaction. The hint bounds the wait on PostgreSQL; H2 ignores it (research R5, analysis K1).
- [X] T005 [P] In `src/main/java/com/rfidback/repository/RegistrationReadRepository.java`, add
  ```java
  @Query("select r.uid from RegistrationReadEntity r where r.session = :session and r.uid in :uids")
  List<String> findUidsBySessionAndUidIn(@Param("session") RegistrationSessionEntity session,
          @Param("uids") Collection<String> uids);
  ```
  (imports `Query`, `Param`, `java.util.Collection`).
- [X] T006 [P] In `src/test/resources/application-test.yml`, append `;LOCK_TIMEOUT=10000` to `spring.datasource.url` (becomes `jdbc:h2:mem:rfidback-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000`), with a comment: `# 10 s lock wait: the concurrent registration batches of spec 011 must not time out on a slow CI.` Run `mvn clean test` once to confirm nothing else changes.

**Checkpoint**: `mvn clean compile` fails only because `RegistrationController` does not yet implement `recordRegistrationReads` — if the generator made it a `default` method (delegate pattern), compile passes and the route answers `501`.

---

## Phase 3: User Story 1 - L'enregistreur envoie tous les tags lus en un seul appel (Priority: P1) 🎯 MVP

**Goal**: an ENREGISTREMENT reader posts `{ "uids": [...] }`; all distinct, non-blank UIDs join its open session in one transaction, or none do; without a session nothing is kept and the answer is still `200`.

**Independent Test**: with a session open on an enregistreur, one call with 5 distinct UIDs puts exactly those 5 reads in the session (and on `GET /api/tags/registration-sessions/{id}`); saving the session then puts them on the bucket.

### Tests for User Story 1

- [X] T007 [P] [US1] Create `src/test/java/com/rfidback/controller/RegistrationReadsApiTest.java` (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, class-level `@Transactional`, same setup style as `RegistrationApiTest`: save an Administrateur `UserEntity`, a `ReaderEntity` with `mode(ReaderMode.ENREGISTREMENT)`, and a `RegistrationSessionEntity` for it via the repositories; send requests with header `x-api-token` = the reader's `getApitoken()`, no CSRF). Take in-list UIDs from `com.rfidback.support.ReferenceTagUids`. Cases, each asserting the JSON body and the rows in `registration_read` (`RegistrationReadRepository.findAllBySessionOrderByFirstReadAtAsc`):
  - 5 distinct UIDs → `200`, `sessionOpen: true`, `receivedCount: 5`, `addedCount: 5`, message `Registration reads kept`; 5 reads, in the request order; session `lastActivityAt` updated to now; `tagRepository.count()`, `bucketRepository.count()` and `recordRepository.count()` unchanged — a batch creates no tag, bucket nor record and assigns nothing to a bucket (FR-004, FR-013, analysis C1).
  - session already holding A, request `["A", "B", "B"]` → `receivedCount: 2`, `addedCount: 1`; reads A and B once each.
  - `[" A ", "", "   ", "B"]` → `receivedCount: 2`; stored UIDs are trimmed.
  - same body sent twice → second answer `addedCount: 0`; reads unchanged (SC-004).
  - no session for the reader → `200`, `sessionOpen: false`, `addedCount: 0`, message `Registration reads ignored: no session started`; no read row.
  - expired session (`lastActivityAt` older than `app.registration.session-timeout`, 5 min) → `sessionOpen: false`; the session and its reads are deleted.
  - `{"uids": []}`, `{}`, `{"uids": ["", " "]}` → `400`, nothing written, `lastActivityAt` unchanged.
  - 100 distinct UIDs → `200` with `addedCount: 100`; 101 distinct → `400` and no read added (SC-006); 1,001 elements (all the same UID) → `400`; one 51-character UID → `400`.
  - after a batch, `POST /api/tags/registration-sessions/{id}/save` as the Administrateur (`with(user(...))` + `csrf()`, as in `RegistrationApiTest`) with a bucket number → `200` and the tags are on the bucket (FR-012).
  Run it and confirm it fails (route not implemented yet).
- [X] T008 [US1] In `src/test/java/com/rfidback/controller/RegistrationReadsApiTest.java`, add a concurrency test that runs **outside** the class transaction (`@Transactional(propagation = Propagation.NOT_SUPPORTED)` on the method; create the user, reader and session with the repositories so they are committed, and delete them — reads first, then session, reader, user — in a `finally`): two threads post the same 10 UIDs at the same time (start them with a `CountDownLatch`); both answer `200`, the two `addedCount` sum to 10, and the session holds exactly 10 reads (FR-010, research R5).

### Implementation for User Story 1

- [X] T009 [US1] In `src/main/java/com/rfidback/service/RegistrationService.java`, add a `TransactionTemplate` built from an injected `PlatformTransactionManager` (new constructor parameter, stored as `private final TransactionTemplate transactionTemplate`), and add constants `READS_KEPT = "Registration reads kept"`, `READS_IGNORED = "Registration reads ignored: no session started"`, `MAX_RAW_UIDS = 1000`, `MAX_UID_LENGTH = 50`. Update the constructor call in `src/test/java/com/rfidback/service/RegistrationServiceTest.java` (`setUp`) to pass a `PlatformTransactionManager` mock (a `Mockito.mock(PlatformTransactionManager.class)` whose `getTransaction` returns `new SimpleTransactionStatus()` is enough) so the existing tests still compile and pass.
- [X] T010 [US1] In `src/main/java/com/rfidback/service/RegistrationService.java`, add `public RegistrationReadsResponse recordReads(ReaderEntity reader, List<String> rawUids)`, not annotated `@Transactional` (class Javadoc already explains why), with a Javadoc line pointing to spec 011:
  1. (US2 adds the mode check here — leave a spot right at the top.)
  2. Validate (research R4) and throw `ResponseStatusException(HttpStatus.BAD_REQUEST, …)` before any repository call: `rawUids` null or larger than `MAX_RAW_UIDS`; then build `List<String> uids` = trimmed, non-blank, de-duplicated in order (`LinkedHashSet`); empty → 400 "No tag uid in the request"; `uids.size() > TagService.MAX_TAGS_PER_REGISTRATION` → throw `TagService.tooManyTags()`; any uid longer than `MAX_UID_LENGTH` → 400 naming it.
  3. Run `transactionTemplate.execute(status -> storeReads(reader, uids))`; if it throws `DataIntegrityViolationException` or `PessimisticLockingFailureException` (both `org.springframework.dao`; the latter covers `CannotAcquireLockException`, a lock wait that timed out), run it once more; if the second run throws either of them, throw `new ResponseStatusException(HttpStatus.CONFLICT, "Concurrent reads, send the batch again")`. Write it as a small loop or a private helper rather than nested try blocks, with a comment: the constraint race is a tag-by-tag read of the same tag, the lock timeout a stuck transaction; neither may end in a `500` (research R5, analysis K1).
  4. private `storeReads(reader, uids)`: `now = now()`; `sessionRepository.findLockedByReader(reader)`; empty → `readsResponse(false, uids.size(), 0, now, READS_IGNORED)`; expired (`isExpired`) → `deleteSession(session)` then the same "ignored" response; else `Set<String> known = new HashSet<>(readRepository.findUidsBySessionAndUidIn(session, uids))`, build one `RegistrationReadEntity` per uid not in `known` with `firstReadAt = now.plus(i, ChronoUnit.MICROS)` where `i` is the uid's index in `uids` (research R6), `readRepository.saveAllAndFlush(newReads)`, `session.setLastActivityAt(now)` (managed entity, flushed at commit), return `readsResponse(true, uids.size(), newReads.size(), now, READS_KEPT)`.
  5. private static `readsResponse(...)` building the generated `RegistrationReadsResponse` (use its required-args constructor if generated).
- [X] T011 [US1] In `src/main/java/com/rfidback/controller/RegistrationController.java`, implement `recordRegistrationReads(RegistrationReadsRequest request)`: take the reader from `SecurityContextHolder` as a `ReaderAuthentication` principal — move `TagController.resolveAuthenticatedReader()` into a small package-private helper `src/main/java/com/rfidback/controller/AuthenticatedReader.java` (`static Optional<ReaderEntity> current()`), used by both controllers, and keep `TagController.scanTag` behaviour identical — then `return ResponseEntity.ok(registrationService.recordReads(reader, request.getUids()))`. A missing reader throws `IllegalStateException` as in `TagController`.
- [X] T012 [US1] Run `mvn clean test -Dtest='RegistrationReadsApiTest,RegistrationServiceTest,RegistrationApiTest,TagScanApiTest'`; all pass.

**Checkpoint**: MVP — an enregistreur can send a whole bucket in one call.

---

## Phase 4: User Story 2 - Seul l'enregistreur peut utiliser ce point d'entrée (Priority: P2)

**Goal**: production readers get `403`; missing, unknown or disabled tokens and front-end sessions get `401`; nothing is written in any of these cases.

**Independent Test**: a PRODUCTION reader posting a batch gets `403` with the "reserved for readers in ENREGISTREMENT mode" detail, and no `record`, `tag` nor `registration_read` row is created.

### Tests for User Story 2

- [X] T013 [P] [US2] In `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java`, add tests on `POST /api/tags/registration-reads` with body `{"uids":["E2000017221101891400A23G"]}`: without token → `401`; unknown token → `401`; disabled reader's token → `401`; a logged-in Administrateur session (`with(user("admin").roles("ADMINISTRATEUR"))` + `csrf()`) without reader token → `401` (research R2); the setUp reader (mode PRODUCTION by default) with its token → `403`, body `detail` contains `ENREGISTREMENT`, and `recordRepository.count()` unchanged. Respect the `CLAUDE.md` note on `csrf()` and the shared CSRF repository (don't rely on the `XSRF-TOKEN` cookie afterwards).
- [X] T014 [P] [US2] In `src/test/java/com/rfidback/service/RegistrationServiceTest.java`, add: `recordReads` with a PRODUCTION reader throws `ResponseStatusException` with status `403`, and `verifyNoInteractions(sessionRepository, readRepository)`; `recordReads` with an ENREGISTREMENT reader and a `null` list → `400`, also without repository interaction; with an open session, `findLockedByReader` throwing `CannotAcquireLockException` once then returning the session → `200` with the reads kept; throwing it twice → `409` (analysis K1).

### Implementation for User Story 2

- [X] T015 [US2] In `RegistrationService.recordReads` (`src/main/java/com/rfidback/service/RegistrationService.java`), at the spot left in T010, before validation: `if (reader.getMode() != ReaderMode.ENREGISTREMENT) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This route is reserved for readers in ENREGISTREMENT mode");` (research R3).
- [X] T016 [US2] Run `mvn clean test -Dtest='ReaderScanSecurityTest,RegistrationServiceTest,KioskReaderTokenSecurityTest,AccessMatrixSecurityTest'`; all pass (the kiosk chain and the user access matrix are unchanged).

**Checkpoint**: the route cannot be misused by a production reader or a user.

---

## Phase 5: User Story 3 - L'envoi tag par tag reste possible pendant la migration (Priority: P3)

**Goal**: prove the existing reader path is untouched (FR-011, SC-005).

**Independent Test**: the existing tag-by-tag tests pass without modification.

- [X] T017 [US3] Run `git diff --stat dev -- src/test/java/com/rfidback/controller/TagScanApiTest.java src/test/java/com/rfidback/controller/RegistrationApiTest.java` and confirm both files are unchanged, then `mvn clean test -Dtest='TagScanApiTest,RegistrationApiTest,ReaderScanSecurityTest'`; all pass. If `TagController` was touched in T011, check that `scanTag` still routes ENREGISTREMENT reads to `recordRead` and others to `TagService.registerScan`.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T018 [P] Update `CLAUDE.md`, section "Security: two kinds of callers, three filter chains": the reader devices chain now covers `POST /api/tags/scan` and `POST /api/tags/registration-reads` (the latter for ENREGISTREMENT readers only, a batch of up to 100 tags into the open registration session, spec 011).
- [X] T019 [P] Update `deploy/INSTALL.md` near line 118: production readers keep `https://vegelink.apolog.fr/api/tags/scan`; the enregistreur can instead be set to `https://vegelink.apolog.fr/api/tags/registration-reads` with body `{"uids": [...]}` (same `x-api-token`), and `/api/tags/scan` keeps working for it until it is reconfigured.
- [X] T020 [P] In `.specify/specs/003-enregistrement-tags-seau/spec.md`, append to FR-007 one sentence: "**Étendu par la spec `011`** : un enregistreur peut aussi envoyer ses lectures en un seul appel (`POST /api/tags/registration-reads`)."
- [X] T021 Run `mvn clean install` (full build, test classes in CI order); all pass.
- [X] T022 Walk through the manual checks of [quickstart.md](quickstart.md) on the `dev` profile (steps 1–8) and note any deviation in the spec's Clarifications before closing the feature. Then set the spec's **Status** to `Delivered (<date>)`.

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2 → US1 → US2 → US3 → Polish.**
- T002 blocks everything that uses generated types (T007–T015). T003, T004, T005, T006 are independent of each other and of T002; T006 must be done before T008 runs.
- US1: T007 → T008 (tests, same file, written first); T009 → T010 → T011 → T012.
- US2 depends on US1 (it adds a check inside `recordReads` and tests the same route). T013 ∥ T014; then T015 → T016.
- US3 depends on T011 only (it verifies nothing else moved).
- Polish: T018, T019, T020 ∥ at any point after T011; T021 after all code tasks; T022 last.

## Parallel Example: Phase 2 and User Story 1

```text
After T002:  T003 SecurityConfig  ∥  T004 RegistrationSessionRepository  ∥  T005 RegistrationReadRepository  ∥  T006 application-test.yml
US1:         T007 → T008 share RegistrationReadsApiTest, so write them in turn; T009–T011 can start alongside
US2 tests:   T013 ReaderScanSecurityTest  ∥  T014 RegistrationServiceTest
Polish:      T018 CLAUDE.md  ∥  T019 deploy/INSTALL.md  ∥  T020 spec 003
```

## Implementation Strategy

1. **MVP (US1)**: T001–T012. The enregistreur can already switch to the batch route. A production reader calling it would get `200` with `sessionOpen: false` (no session can exist for it) instead of the `403` that makes the misconfiguration visible, so do not deploy before US2.
2. **Ship (US1 + US2)**: T013–T016 make the route safe; this is the smallest deployable increment.
3. **Confirm (US3) and polish**: T017–T022, then open the PR against `dev`.
