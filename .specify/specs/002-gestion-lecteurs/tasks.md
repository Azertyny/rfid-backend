---
description: "Task list for 002 — Gestion des lecteurs RFID"
---

# Tasks: Gestion des lecteurs RFID (Reader management)

**Input**: Design documents from `.specify/specs/002-gestion-lecteurs/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included. The spec requires them (SC-002: no test covers `ReaderService` or `ReaderController`), and research R8 defines the approach. For US1, US3 and US4, write the tests first and make sure they fail before implementing. The US2 tests lock in behaviour that the Foundational phase (T004) already adds, so they pass right away.

**Organization**: Tasks are grouped by the spec's user stories (US1–US4) so each story can be implemented and tested as its own increment. US3 and US4 cover FR-006.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: User story (US1–US4)
- Paths are relative to the repository root. Java sources: `src/main/java/com/rfidback/`; tests: `src/test/java/com/rfidback/`; front: `front/`.

## Conventions used by every task

- API-first: contract changes go into `src/main/resources/openapi/api.yaml` first, then `mvn generate-sources`; controllers implement the generated `*ApiDelegate`.
- Follow the existing style: Lombok (`@RequiredArgsConstructor`, `@Builder`), exceptions annotated with `@ResponseStatus` (see `exception/PickerAlreadyExistsException.java`), and `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` for request-shape errors (see `service/UserService.java:60` and `PickerService.requiredName`).
- Service unit tests: plain Mockito, no Spring context, in the style of `service/PickerServiceTest.java` (mocks created in `@BeforeEach`, service built with its constructor).
- HTTP tests: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`, with `user("admin").roles("ADMINISTRATEUR")` and `csrf()` from `spring-security-test` (see `controller/PickerApiTest.java`). The in-memory H2 context is shared between test classes, so each test creates its readers with a unique `uid` (e.g. `"Reader test " + UUID.randomUUID()` cut to 50 characters).
- If a run fails with `NoClassDefFoundError` or "Unresolved compilation problem", it is the VS Code Java extension racing Maven (`CLAUDE.md`): rerun `mvn clean test`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: apply the contract changes so generated interfaces and models are ready for every story.

- [x] T001 Apply [contracts/openapi-readers.md](contracts/openapi-readers.md) §1–§2 to `src/main/resources/openapi/api.yaml`: `CreateReader.uid` gets `maxLength: 50` and the description "Reader uid. Trimmed; must not be blank; unique ignoring case."; `Reader`'s second `allOf` part gets `id` (`string`, `format: uuid`) and `active` (`boolean`, description "False when the reader is disabled; its token is then refused on /tags/scan.") plus `required: [id, active]`; add schema `UpdateReader` (object, description "At least one field must be present.", property `active: boolean`, nothing required); on `/readers` add `"401"`/`"403"` refs to `get` and `post`, and `"409": { $ref: "#/components/responses/AlreadyExist" }` to `post`; add path `/readers/{readerId}` (uuid path param) with `patch` `operationId: updateReader`, `tags: [Reader]`, body `UpdateReader`, responses `200` → `Reader`, `400`, `401`, `403`, `404`; add path `/readers/{readerId}/token` with `post` `operationId: rotateReaderToken`, `tags: [Reader]`, no body, description "The previous token is refused on /tags/scan from the next request on.", responses `200` → `Reader`, `401`, `403`, `404`; in `/tags/scan` leave the `401` ref as is and append " Also returned when the reader is disabled." to the operation `description`
- [x] T002 Run `mvn generate-sources` then `mvn -q compile`. Expected: compile succeeds (new delegate methods have default bodies), `ReaderApiDelegate` declares `updateReader(UUID readerId, UpdateReader updateReader)` and `rotateReaderToken(UUID readerId)`, and the generated `Reader` model has `setId(UUID)` and `setActive(Boolean)`. Use the exact generated signatures in later tasks

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the `active` column, the shared token generator, a single entity→DTO mapping, and the test classes every story adds cases to.

**⚠️ CRITICAL**: complete before the story phases.

- [x] T003 In `src/main/java/com/rfidback/entity/ReaderEntity.java` add `@Builder.Default @ColumnDefault("true") @Column(nullable = false) private boolean active = true;` (import `org.hibernate.annotations.ColumnDefault`; see research R4 for why both annotations are needed), and add `public static String newApitoken()` returning `UUID.randomUUID().toString().replace("-", "")`; make `prePersist()` call it instead of inlining the expression (research R6)
- [x] T004 In `src/main/java/com/rfidback/service/ReaderService.java`: switch to constructor injection (`@RequiredArgsConstructor`, `private final ReaderRepository readerRepository`, drop `@Autowired`) so it can be unit-tested; extract `private Reader toModel(ReaderEntity entity, boolean includeApiToken)` that sets `id`, `uid` (from `name`), `active`, `creationDate`, `updateDate`, and `apitoken` only when `includeApiToken`; use it in `getReaders()` (with `currentUserIsAdministrator()`) and in `createReader()` (with `true`, since only Administrateurs reach that route); delete the leftover "Here you would add logic…" comment
- [x] T005 [P] Create `src/test/java/com/rfidback/service/ReaderServiceTest.java`: mock `ReaderRepository` in `@BeforeEach`, build `new ReaderService(readerRepository)`, stub `save` and `saveAndFlush` to return their argument with a random `id` and `OffsetDateTime.now()` dates set (and `prePersist()` called, so `apitoken` is filled), add a helper `ReaderEntity reader(String name, boolean active)`. One smoke test: `getReaders_mapsIdAndActive` (`findAll` returns one inactive reader → the DTO has its `id` and `active == false`)
- [x] T006 [P] Create `src/test/java/com/rfidback/controller/ReaderApiTest.java` (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Autowired MockMvc`, `@Autowired ObjectMapper`, `@Autowired ReaderRepository`) with helpers `RequestPostProcessor admin()` / `operator()` and `JsonNode createReader(String uid)` that POSTs `/api/readers` with `admin()` and `csrf()`, asserts `201` and returns the body. One smoke test: `GET /api/readers` as admin → `200`
- [x] T007 Run `mvn clean test`. Expected: the whole suite is green, including the existing `ReaderScanSecurityTest` and `ReaderTokenVisibilityTest` (the refactor in T004 must not change what they observe)

**Checkpoint**: `mvn clean test -Dtest='ReaderServiceTest,ReaderApiTest'` is green.

---

## Phase 3: User Story 1 - Enregistrer un lecteur RFID (Priority: P1) 🎯 MVP

**Goal**: creation trims the `uid`, answers `400` to a blank or too-long one (FR-001a), and `409` to a duplicate ignoring case (FR-007), instead of `500`.

**Independent Test**: `mvn clean test -Dtest='ReaderServiceTest,ReaderApiTest'`. The create tests cover `201` with a trimmed `uid`, `400` for blank and 51 characters, and `409` for `"Poste X"` vs `"  poste x "`.

### Tests for User Story 1 (write first, must fail)

- [x] T008 [P] [US1] In `src/test/java/com/rfidback/service/ReaderServiceTest.java` add: `createReader_trimsUid` (input `"  Poste A "` → saved entity name `"Poste A"`, checked with an `ArgumentCaptor<ReaderEntity>`); `createReader_blankUid_throwsBadRequest` (`"   "` → `ResponseStatusException` with status `400`, `save` never called); `createReader_duplicateIgnoringCase_throwsConflict` (`existsByNameIgnoreCase("Poste A")` → `true` → `ReaderAlreadyExistsException`, `saveAndFlush` never called); `createReader_constraintViolationOnSave_throwsConflict` (`existsByNameIgnoreCase` → `false`, `saveAndFlush` throws `DataIntegrityViolationException` → `ReaderAlreadyExistsException`, spec FR-007 race case)
- [x] T009 [P] [US1] In `src/test/java/com/rfidback/controller/ReaderApiTest.java` add: `create_returns201WithTrimmedUidTokenIdAndActive`; `create_blankUid_returns400` (`"   "`); `create_uidOver50Chars_returns400` (51 × `"a"`); `create_duplicateIgnoringCase_returns409` (create `"Dup " + suffix`, then `"  dup " + suffix.toUpperCase() + " "`)

### Implementation for User Story 1

- [x] T010 [P] [US1] Create `src/main/java/com/rfidback/exception/ReaderAlreadyExistsException.java`, `@ResponseStatus(HttpStatus.CONFLICT)`, same shape as `PickerAlreadyExistsException.java`
- [x] T011 [P] [US1] In `src/main/java/com/rfidback/repository/ReaderRepository.java` add `boolean existsByNameIgnoreCase(String name);`
- [x] T012 [US1] In `src/main/java/com/rfidback/service/ReaderService.java` `createReader`: reject `null`/blank `uid` with `new ResponseStatusException(HttpStatus.BAD_REQUEST, "uid must not be blank")`, trim it, throw `new ReaderAlreadyExistsException("A reader with the same uid already exists")` when `existsByNameIgnoreCase(trimmed)`, then build the entity with the trimmed name and save it with `saveAndFlush`, catching `org.springframework.dao.DataIntegrityViolationException` and rethrowing it as the same `ReaderAlreadyExistsException` (research R5: a same-case race must answer `409`, not `500`) (depends on T010, T011)
- [x] T013 [US1] In `front/readers.html`: add `maxlength="50"` to the `#readerUid` input; in `createReader()` trim the value before the empty check and send the trimmed value; replace the single `alert("Erreur création : Ce nom existe peut-être déjà ?")` with messages per status: `409` → "Un lecteur porte déjà ce nom (sans tenir compte des majuscules).", `400` → "Nom invalide : 1 à 50 caractères.", otherwise "Erreur création (code X)."

**Checkpoint**: US1 tests green; creating `"Poste A"` then `"poste a"` in `readers.html` shows the `409` message.

---

## Phase 4: User Story 2 - Lister les lecteurs (Priority: P1)

**Goal**: every reader is listed with `id` and `active` for both roles, and `apitoken` still only reaches Administrateurs (FR-003). Each front page decides what to show (research R7).

**Independent Test**: `mvn clean test -Dtest='ReaderApiTest,ReaderTokenVisibilityTest'`. As Opérateur, an inactive reader is present with `active: false`, an `id`, and no `apitoken`.

### Tests for User Story 2 (lock in behaviour; expected to pass right away, because T004 already maps `id` and `active`)

- [x] T014 [P] [US2] In `src/test/java/com/rfidback/controller/ReaderApiTest.java` add `list_asOperator_includesInactiveReaderWithIdAndActiveButNoToken`: save a reader with `active(false)` through `ReaderRepository`, `GET /api/readers` with `operator()`, find it by `uid` with a JsonPath filter, assert `active == false`, `id` present, and `apitoken` absent; and `list_asAdmin_includesActiveFlagAndToken`

### Implementation for User Story 2

- [x] T015 [US2] Check that `ReaderService.getReaders()` (after T004) passes T014 with no further change; fix `toModel` if needed in `src/main/java/com/rfidback/service/ReaderService.java`
- [x] T016 [P] [US2] In `front/index.html` `fetchReaders()`: build `LINES` only from readers where `r.active !== false` (research R7: a disabled reader cannot scan, so its line would stay empty)
- [x] T017 [P] [US2] In `front/reader.html` `loadReaders()` loop: when `reader.active === false`, add a CSS class that greys the button (e.g. `.reader-btn.inactive { opacity: .5; }` in the page's `<style>`) and append `<small>désactivé</small>` under the `<h3>`; the button stays clickable so past records can still be viewed
- [x] T018 [US2] In `front/readers.html` `renderTable()`: add an "État" column (header in the `<thead>`, and set every `colspan="4"` in the file to the new column count) showing a Bootstrap badge `Actif` (`bg-success`) or `Désactivé` (`bg-secondary`) from `r.active`

**Checkpoint**: US1 and US2 green; `index.html` and `reader.html` handle an inactive reader saved from a test or the H2 console.

---

## Phase 5: User Story 3 - Désactiver / réactiver un lecteur (Priority: P2)

**Goal**: `PATCH /api/readers/{id}` sets `active`. A disabled reader's token gets `401` on `/api/tags/scan`, and after reactivation its current token works again.

**Independent Test**: `mvn clean test -Dtest='ReaderServiceTest,ReaderApiTest,ReaderScanSecurityTest,AccessMatrixSecurityTest'`.

### Tests for User Story 3 (write first, must fail)

- [x] T019 [P] [US3] In `src/test/java/com/rfidback/service/ReaderServiceTest.java` add: `updateReader_emptyBody_throwsBadRequest` (`new UpdateReader()` → `ResponseStatusException` `400`, repository never queried); `updateReader_unknownId_throwsNotFound` (→ `ReaderNotFoundException`); `updateReader_setsActive` (`active(false)` on an active reader → saved entity inactive, returned DTO `active == false`, `apitoken` present)
- [x] T020 [P] [US3] In `src/test/java/com/rfidback/controller/ReaderApiTest.java` add: `patch_disablesThenReenables` (two PATCH calls with `admin()` + `csrf()`, both `200`, `active` flips); `patch_emptyBody_returns400` (`{}`); `patch_unknownId_returns404`; `disabledReader_recordsStillReadable` (spec US3 scenario 3: disable a reader with PATCH, then `GET /api/records/readers/{uid}` with `operator()` → `200`)
- [x] T021 [P] [US3] In `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java` add `scan_withDisabledReader_returns401_thenWorksAfterReactivation`: set the reader from `setUp()` to inactive through `ReaderRepository` (`saveAndFlush`), scan → `401`; set it back to active, scan with the same token → `200`
- [x] T022 [P] [US3] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java` add `new Route(HttpMethod.PATCH, "/api/readers/" + ID, "{\"active\":true}", ADMIN_ONLY)` after the `POST /api/readers` row

### Implementation for User Story 3

- [x] T023 [US3] In `src/main/java/com/rfidback/service/ReaderService.java` add `@Transactional public Reader updateReader(UUID readerId, UpdateReader request)`: `400` when `request.getActive() == null` (message "Provide an active state"), load with `findById` or throw `new ReaderNotFoundException("Reader %s not found".formatted(readerId))`, set `active`, save, return `toModel(entity, true)`
- [x] T024 [US3] In `src/main/java/com/rfidback/controller/ReaderController.java` override `updateReader` with the signature generated in T002, returning `ResponseEntity.ok(readerService.updateReader(readerId, updateReader))`
- [x] T025 [US3] In `src/main/java/com/rfidback/security/ReaderApiTokenAuthenticationFilter.java`, after the `reader == null` check, add `if (!reader.isActive()) { response.sendError(HttpStatus.UNAUTHORIZED.value(), "Reader disabled"); return; }` (research R3)
- [x] T026 [US3] In `front/readers.html` add an "Actions" column (update the `colspan`s again) with a button `Désactiver` (active reader) or `Réactiver` (inactive reader) that calls `toggleReader(id, active)`: `confirm()` first (for deactivation: "Le lecteur ne pourra plus envoyer de lectures. Continuer ?"), then `apiFetch('/readers/' + id, {method: 'PATCH', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({active: !active})})`, then `fetchReaders()`; alert on a non-OK status. Pass only the UUID `id` into the `onclick`, never `uid` (research R7)

**Checkpoint**: US3 tests green; quickstart manual step 3 passes.

---

## Phase 6: User Story 4 - Régénérer le jeton d'un lecteur (Priority: P2)

**Goal**: `POST /api/readers/{id}/token` returns a new token, and the old one gets `401` from the next scan on, whatever the reader's state.

**Independent Test**: `mvn clean test -Dtest='ReaderServiceTest,ReaderApiTest,ReaderScanSecurityTest,AccessMatrixSecurityTest'`.

### Tests for User Story 4 (write first, must fail)

- [x] T027 [P] [US4] In `src/test/java/com/rfidback/service/ReaderServiceTest.java` add: `rotateToken_replacesToken` (returned `apitoken` is 32 hex characters and differs from the old one, saved entity has the new one, `active` unchanged); `rotateToken_unknownId_throwsNotFound`
- [x] T028 [P] [US4] In `src/test/java/com/rfidback/controller/ReaderApiTest.java` add: `rotate_returns200WithNewToken`; `rotate_unknownId_returns404`; `rotate_worksOnDisabledReader` (disable with PATCH, rotate → `200`, `active` still `false`)
- [x] T029 [P] [US4] In `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java` add `scan_withTokenReplacedByRotation_returns401_newTokenWorks`: `POST /api/readers/{id}/token` with `user("admin").roles("ADMINISTRATEUR")` + `csrf()`, read the new token from the JSON body, scan with the old token → `401`, with the new one → `200`
- [x] T030 [P] [US4] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java` add `new Route(HttpMethod.POST, "/api/readers/" + ID + "/token", null, ADMIN_ONLY)`

### Implementation for User Story 4

- [x] T031 [US4] In `src/main/java/com/rfidback/service/ReaderService.java` add `@Transactional public Reader rotateToken(UUID readerId)`: load or throw `ReaderNotFoundException`, `setApitoken(ReaderEntity.newApitoken())`, save, return `toModel(entity, true)`
- [x] T032 [US4] In `src/main/java/com/rfidback/controller/ReaderController.java` override `rotateReaderToken` with the signature generated in T002, returning `ResponseEntity.ok(readerService.rotateToken(readerId))`
- [x] T033 [US4] In `front/readers.html` add a `Régénérer la clé` button in the Actions column calling `rotateToken(id, uid)`: `confirm("L'ancienne clé cessera immédiatement de fonctionner. Il faudra reconfigurer le lecteur. Continuer ?")`, `apiFetch('/readers/' + id + '/token', {method: 'POST'})`, then fill `#newReaderName` / `#newTokenDisplay` with `textContent` and show the existing `successModal`, then `fetchReaders()`

**Checkpoint**: all four stories green; quickstart manual step 2 passes.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T034 Run `mvn clean install`. Expected: build and the full test suite pass
- [x] T035 Schema upgrade check ([quickstart.md](quickstart.md), "Schema upgrade on an existing database"): start the app on the existing `./data/rfidbackdb.mv.db` without deleting it and confirm that startup succeeds and existing readers come back `active: true`. Report the result, and do not commit the database file
- [x] T036 Run the manual steps of [quickstart.md](quickstart.md) (validation, rotation, deactivation, front display), or list any step that could not be run
- [x] T037 [P] Update `.specify/specs/002-gestion-lecteurs/spec.md`: mark FR-003's authentication part and FR-005 as delivered by spec `008`; mark FR-001a, FR-006 and FR-007 as delivered; replace SC-002's `[NEEDS CLARIFICATION]` with the test classes that now cover the module; update the third Drift row, the "Fuite de jetons" edge case (the hard-coded token is gone), and the header (`Feature Branch`, `Status`)
- [x] T038 [P] In `.specify/specs/008-authentification-roles/spec.md`, in the access-matrix row "`POST /api/readers` (et futures routes de rotation/suppression, changement de mode)", replace "suppression" with "désactivation" and name the delivered routes `PATCH /api/readers/{id}` and `POST /api/readers/{id}/token`

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (T001–T002)** → **Foundational (T003–T007)** → story phases → **Polish (T034–T038)**.
- All story phases depend only on Foundational. They touch the same files (`ReaderService.java`, `ReaderApiTest.java`, `ReaderServiceTest.java`, `readers.html`), so run them one after another: US1 → US2 → US3 → US4.
- US4 reuses the Actions column created in US3 (T026 → T033), and its test T028 uses the PATCH route. If you do US4 without US3, create the column in T033 and disable the reader through `ReaderRepository` in T028.

### Within each story

- Tests are written first and must fail. Then exception/repository, then service, then controller, then front.
- T012 depends on T010 and T011. Controller tasks depend on their service task.

### Parallel opportunities

- T005 ∥ T006 (different new test classes).
- US1: T008 ∥ T009, then T010 ∥ T011.
- US2: T016 ∥ T017 (different front files).
- US3: T019 ∥ T020 ∥ T021 ∥ T022 (four different test files).
- US4: T027 ∥ T028 ∥ T029 ∥ T030.
- Polish: T037 ∥ T038.

### Parallel example: User Story 3 tests

```text
T019 ReaderServiceTest   — updateReader cases
T020 ReaderApiTest       — PATCH cases
T021 ReaderScanSecurityTest — disabled reader → 401 → 200
T022 AccessMatrixSecurityTest — PATCH row
```

## Implementation Strategy

### MVP first

1. Phases 1–2 (contract, `active` column, mapping, test scaffolding).
2. Phase 3 (US1): creation stops answering `500` to a duplicate. **Stop and validate**: T007 plus the US1 tests.

### Incremental delivery

1. US1: validation and `409`.
2. US2: `id` and `active` in the list, front pages ready for inactive readers.
3. US3: deactivation. The first real security gain, because a lost device can be cut off.
4. US4: rotation. After deploying it, rotate the token formerly hard-coded in `front/index.html` ([quickstart.md](quickstart.md), "After deploying").

Each increment keeps `mvn clean test` green and leaves reader devices working with no reconfiguration, except after an explicit rotation.

---

## Amendment 2026-10-06: deleting a reader

**Input**: spec Clarifications 2026-10-06 (FR-008, User Story 5); plan "Amendment 2026-10-06"; research R9–R14;
data-model "Amendment 2026-10-06"; contract §5; quickstart "Amendment 2026-10-06". Code references are to `dev` at
`dd51ae3`.

**Story**: the amendment is User Story 5 (`[US5]`). It is the only story, so it is also the MVP. Tests come first, as in
the phases above (SC-002).

**Key rules** (used by every task below):
- A reader is **deleted** when `reader.deleted_at` is not null (`ReaderEntity.isDeleted()`). It is never physically
  removed, and never un-deleted.
- Only a **deactivated** reader (`active = false`) can be deleted; an active one → `409` (`ReaderStillActiveException`).
  Since `PATCH` answers `404` for a deleted reader, a deleted reader stays deactivated forever, so its token is already
  refused by `ReaderApiTokenAuthenticationFilter` with no filter change.
- A deleted reader is treated as **unknown (`404`)** by every route that targets it for management or as a line, but is
  **still found** by `existsByNameIgnoreCase` (uid stays taken), `GET /records/readers/{uid}` and
  `GET /records/stats?readerId=` (history).

## Phase 8: Setup (amendment)

- [X] T039 From an up-to-date `dev`, create the branch `002-suppression-lecteur` (PRs target `dev`), bring over the uncommitted spec 002 changes (spec, plan, research, data-model, contract, quickstart, tasks), then run `mvn clean test` to confirm a green baseline before any code change

## Phase 9: Foundational (amendment)

**Purpose**: the contract, the column and the repository changes every US5 task relies on. Blocks Phase 10.

- [X] T040 In `src/main/resources/openapi/api.yaml`, add `delete` (`operationId: deleteReader`, `tags: [Reader]`, responses `204` `Deleted`, `401`, `403`, `404`, `409` `Conflict`) to `/readers/{readerId}` with the description from `.specify/specs/002-gestion-lecteurs/contracts/openapi-readers.md` §5. Update the descriptions listed there: `GET /readers` (deleted readers are not listed), the `404` of `PATCH /readers/{readerId}`, `POST /readers/{readerId}/token`, `PUT /readers/{readerId}/activities` and `POST /tags/registration-sessions` (unknown or deleted reader), the `409` of `POST /readers` (also a deleted reader's uid). Run `mvn generate-sources` and check that `ReaderApiDelegate` now has `deleteReader(UUID readerId)`
- [X] T041 [P] In `src/main/java/com/rfidback/entity/ReaderEntity.java`, add `@Column(name = "deleted_at") private OffsetDateTime deletedAt;` (nullable, no default, with a one-line comment: set by a soft delete, never cleared, spec 002 FR-008) and `public boolean isDeleted() { return deletedAt != null; }`
- [X] T042 [P] Create `src/main/java/com/rfidback/exception/ReaderStillActiveException.java`, `@ResponseStatus(HttpStatus.CONFLICT)`, extending `RuntimeException` with a `String message` constructor, copied from `PickerHasBucketsException.java`
- [X] T043 In `src/main/java/com/rfidback/repository/ReaderRepository.java`, add `List<ReaderEntity> findAllByDeletedAtIsNull();` and add `r.deletedAt is null and (...)` to the `findIdsWithStaleState` query (keep `order by r.id`), so the midnight reset skips deleted readers (research R12). Depends on T041
- [X] T044 Add `deleteReader(UUID readerId)` to `src/main/java/com/rfidback/controller/ReaderController.java` returning `ResponseEntity.noContent().build()` after calling `readerService.deleteReader(readerId)`. In `src/main/java/com/rfidback/service/ReaderService.java`, add the fields `private final ActivityRepository activityRepository;` and `private final Clock clock;` (the constructor, generated by `@RequiredArgsConstructor`, becomes `(ReaderRepository, RegistrationService, LineActivityService, ActivityRepository, Clock)`), and a `public void deleteReader(UUID readerId)` throwing `UnsupportedOperationException`. Update the `new ReaderService(...)` call in `src/test/java/com/rfidback/service/ReaderServiceTest.java` with an `ActivityRepository` mock and a fixed `Clock`, so the build and the existing tests compile before the new tests are written. Then run `mvn clean test`: everything still green

**Checkpoint**: build green, the new route exists and answers `500`; nothing else changed.

## Phase 10: User Story 5 — Supprimer un lecteur (Priority: P2)

**Goal**: an Administrateur deletes a deactivated reader; it vanishes from the lists and lines, its token stays refused,
its uid stays taken, its history stays (spec User Story 5, scenarios 1–9).

**Independent test**: quickstart "Amendment 2026-10-06", automated command plus manual steps 1–7.

### Tests (write first, they must fail)

- [X] T045 [P] [US5] In `src/test/java/com/rfidback/service/ReaderServiceTest.java` (constructor already updated in T044), add: delete of an active reader → `ReaderStillActiveException` and nothing saved; delete of an unknown id and of an already deleted reader → `ReaderNotFoundException`; delete of a deactivated reader with two associated activities and a current activity → removed from both activities' `lines`, `lineActivityService.startNewDay(reader)` called before `lineActivityService.clear(reader, currentUser)`, `registrationService.closeForReader(reader)` called (the guard of research R11 step 4, only testable here), `deletedAt` set to the clock's instant; `updateReader` and `rotateToken` on a deleted reader → `ReaderNotFoundException`; `getReaders` uses `findAllByDeletedAtIsNull` and returns no deleted reader
- [X] T046 [P] [US5] In `src/test/java/com/rfidback/controller/ReaderApiTest.java` (Administrateur, CSRF), add: `DELETE` an active reader → `409` and still listed; deactivate then `DELETE` → `204`, absent from `GET /api/readers` for Administrateur and Opérateur; `DELETE` again → `404`; `DELETE` unknown UUID → `404`; after deletion `PATCH` `{"active":true}`, `POST .../token` and `PUT .../activities` `{"activityIds":[]}` → `404`; `POST /api/readers` with the deleted uid in another case → `409`; a deleted reader's records still returned by `GET /api/records/readers/{uid}` with its `uid`
- [X] T047 [P] [US5] In `src/test/java/com/rfidback/controller/LineActivityApiTest.java`, cover spec scenario 3: a deactivated PRODUCTION reader associated with an activity that is its current activity, chosen today, then `DELETE` → `GET /api/activities` no longer lists it in that activity's `lines`, the reader's `currentActivity` is null in the database, and the last `line_activity_change` for it is "no activity" authored by the Administrateur (true because the choice is from today; research R11 step 3); then `GET /api/lines/{uid}/current-activity` as Opérateur → `404`. Add a second case for the midnight reset (research R12): a deleted reader whose `current_activity_set_at` is set to a previous day in the database, then `LineActivityService.startNewDayForAllLines()` → no new `line_activity_change` for it
- [X] T048 [P] [US5] In `src/test/java/com/rfidback/controller/RecordStatsApiTest.java`, add: records scanned by a reader, then the reader is deactivated and deleted → `GET /api/records/stats` (no `readerId`) totals unchanged, and `GET /api/records/stats?readerId={id}` still `200` with the same counts (spec scenario 6)
- [X] T049 [P] [US5] In `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java`, add: a deleted reader's token → `401` on `POST /api/tags/scan`; and in `src/test/java/com/rfidback/security/KioskReaderTokenSecurityTest.java`, its token → `401` on `GET /api/records/readers/{uid}` (spec scenario 4)
- [X] T050 [P] [US5] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java`, add the row `new Route(HttpMethod.DELETE, "/api/readers/" + ID, null, ADMIN_ONLY)` (spec scenario 5)
- [X] T051 [P] [US5] In `src/test/java/com/rfidback/service/RegistrationServiceTest.java`, add: `start` on a deleted reader → `ReaderNotFoundException`; in `src/test/java/com/rfidback/service/LineActivityServiceTest.java`, add: `getLineActivity`/`setLineActivity` on a deleted reader → `ReaderNotFoundException`; in `src/test/java/com/rfidback/service/ActivityServiceTest.java`, add: `setReaderActivities` on a deleted reader → `ReaderNotFoundException`

Run `mvn clean test`: the new tests fail, the others still pass.

### Implementation

- [X] T052 [US5] In `src/main/java/com/rfidback/service/ReaderService.java`: use the `ActivityRepository` and `Clock` fields added in T044; `getReaders` iterates `readerRepository.findAllByDeletedAtIsNull()`; `loadReader` and `updateReader`'s `findWithLockById` throw `ReaderNotFoundException` when the reader `isDeleted()`; implement `@Transactional deleteReader(UUID readerId)` per research R11: lock with `findWithLockById` (missing or deleted → `ReaderNotFoundException`; `isActive()` → `ReaderStillActiveException("Deactivate the reader before deleting it")`), remove it from every `activityRepository.findAllByLine(readerId)` (`activity.getLines().removeIf(line -> line.getId().equals(readerId))`), `activityRepository.flush()`, `lineActivityService.startNewDay(reader)`, then if `getCurrentActivity() != null` call `lineActivityService.clear(reader, lineActivityService.currentUser())` (research R11 step 3: checking before `startNewDay` would read a stale state), call `registrationService.closeForReader(reader)`, then `setDeletedAt(OffsetDateTime.now(clock))` and `save`. Update the class Javadoc of `updateReader`'s neighbourhood with one line on the delete rule. Depends on T041–T044
- [X] T053 [P] [US5] In `src/main/java/com/rfidback/service/ActivityService.java`, `setReaderActivities`: after `findWithLockById`, throw `ReaderNotFoundException` when `reader.isDeleted()` (same message as the missing case)
- [X] T054 [P] [US5] In `src/main/java/com/rfidback/service/LineActivityService.java`, `getLineActivity` and `setLineActivity`: treat a reader that `isDeleted()` as not found (`readerNotFound(readerUid)`)
- [X] T055 [P] [US5] In `src/main/java/com/rfidback/service/RegistrationService.java`, `start`: treat a reader that `isDeleted()` as not found (`ReaderNotFoundException`), checked before the active/mode check
- [X] T056 [US5] Check that `src/main/java/com/rfidback/controller/ReaderController.java` `deleteReader` (written in T044) needs no change and that no `UnsupportedOperationException` from T044 is left in `ReaderService.java`. Run `mvn clean test`: all T045–T051 tests pass. Depends on T052–T055
- [X] T057 [US5] In `front/readers.html` `renderTable`, add a "Supprimer" button (`btn-outline-danger`) only on rows where `r.active === false`, calling `deleteReader('${r.id}')` (the id only: the page has no escaping helper, so no free text goes into `onclick`, research R7); keep the list from the last `fetchReaders()` in a variable; add `async function deleteReader(id)` that finds the reader by `id` in that list and asks `confirm(\`Supprimer le lecteur ${reader.uid} ? Il disparaîtra de toutes les listes. Ses lectures sont conservées. Son nom ne pourra pas être réutilisé.\`)` (`confirm` shows plain text, so the uid needs no escaping there), sends `DELETE /readers/${id}` through `apiFetch`, then calls `fetchReaders()`; on a non-OK response, show the API message with `showError`

**Checkpoint**: all tests green; quickstart "Amendment 2026-10-06" manual steps pass.

## Phase 11: Polish (amendment)

- [X] T058 Run `mvn clean install`. Expected: build and the full test suite pass
- [X] T059 Schema check: start the app on the existing `./data/rfidbackdb.mv.db` without deleting it; confirm startup succeeds, `reader.deleted_at` exists and is null, and `GET /api/readers` lists the same readers as before. Do not commit the database file
- [X] T060 Run the manual steps of `.specify/specs/002-gestion-lecteurs/quickstart.md` "Amendment 2026-10-06", or list any step that could not be run
- [X] T061 [P] In `.specify/specs/008-authentification-roles/spec.md`, access-matrix row for `POST /api/readers`, `PATCH /api/readers/{id}`…: add `DELETE /api/readers/{id}` (suppression logique, spec `002` FR-008)
- [X] T062 [P] In `CLAUDE.md`, Persistence: add one sentence that readers are soft-deleted (`reader.deleted_at`, spec 002 FR-008), never physically removed, and that a deleted reader keeps its uid and its records
- [X] T063 [P] In `.specify/specs/002-gestion-lecteurs/spec.md`, mark FR-008 and User Story 5 as delivered (as FR-006 says "**Livré** : …"), add the test classes to SC-002, and update the Status line

## Dependencies & Execution Order (amendment)

- T039 → T040 → T041 ∥ T042 → T043 → T044 → tests T045–T051 (all [P], different files) → T052 → T053 ∥ T054 ∥ T055 → T056 → T057 → T058–T060 → T061 ∥ T062 ∥ T063.
- T044 sets the new `ReaderService` constructor and updates `ReaderServiceTest`'s setup, so the tests T045–T051 compile before T052: only the new tests fail.
- T057 (front) only needs the route and T052; it can be done in parallel with T053–T056.

### Parallel example: User Story 5 tests

```text
T045 ReaderServiceTest            — delete rules, 404 on deleted, filtered list
T046 ReaderApiTest                — 204/404/409, hidden, uid taken, records kept
T048 RecordStatsApiTest           — totals unchanged after delete
T049 ReaderScanSecurityTest + KioskReaderTokenSecurityTest — token → 401
T050 AccessMatrixSecurityTest     — DELETE row
```

## Implementation Strategy (amendment)

One story, delivered at once: Phases 8–10 are the MVP. **Stop and validate** after T056 (all tests green) before the
front (T057). Readers in the field need no reconfiguration: only deactivated readers can be deleted, and they are
already cut off.
