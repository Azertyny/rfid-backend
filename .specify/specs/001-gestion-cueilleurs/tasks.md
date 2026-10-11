---

description: "Task list for 001 — Gestion des cueilleurs"
---

# Tasks: Gestion des cueilleurs (Picker management)

**Input**: Design documents from `.specify/specs/001-gestion-cueilleurs/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included. The spec requires them (SC-003: no automated test protects this module today), and research R6 defines the approach. For stories that change behaviour (US2, US4), write the tests first and make sure they fail before implementing. For stories whose behaviour stays the same (US1, US3), the tests lock in current behaviour and should pass right away.

**Organization**: Tasks are grouped by user story (spec.md US1–US4) so each story can be implemented and tested as its own increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: User story from spec.md (US1, US2, US3, US4)
- Paths are relative to the repository root. Java sources: `src/main/java/com/rfidback/`; tests: `src/test/java/com/rfidback/`; front: `front/`.

## Conventions used by every task

- API-first: contract changes go into `src/main/resources/openapi/api.yaml` first, then `mvn generate-sources`; controllers implement the generated `*ApiDelegate`.
- Follow the existing style: Lombok (`@RequiredArgsConstructor`, `@Builder`), exceptions annotated with `@ResponseStatus` (see `exception/PickerAlreadyExistsException.java`), `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)` for request-shape errors (see `service/UserService.java:61`).
- Service unit tests: plain Mockito, no Spring context, in the style of `service/TagServiceTest.java` (mocks created in `@BeforeEach`, service built with its constructor).
- HTTP tests: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`, with `user("admin").roles("ADMINISTRATEUR")` and `csrf()` from `spring-security-test` (see `security/AccessMatrixSecurityTest.java`). Each test creates its own data with a unique name (e.g. suffixed with `UUID.randomUUID()`), because the in-memory H2 context is shared between test classes.
- If a run fails with `NoClassDefFoundError` or "Unresolved compilation problem", it is the VS Code Java extension racing Maven (`CLAUDE.md`): rerun `mvn clean test`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: apply the contract changes so generated interfaces are ready for every story.

- [X] T001 Apply [contracts/openapi-pickers.md](contracts/openapi-pickers.md) sections 1–4 to `src/main/resources/openapi/api.yaml`: rewrite the `components.parameters.PageSort` description (allowed fields `lastname`/`firstname`/`creationDate`, directions `asc`/`desc` case-insensitive, default, `400` otherwise; no `pattern`); add response `components.responses.Conflict` ("Retourné lorsque l'action est impossible car la ressource est encore utilisée"); on `DELETE /pickers/{pickerId}` change `"409"` to `$ref: "#/components/responses/Conflict"`; under `/buckets/{bucketId}/picker` add a `delete` operation `operationId: unassignBucketFromPicker`, `tags: [Bucket]`, summary "Unassign the picker from a bucket", responses `204` (description "Seau désaffecté (aussi quand il n'avait pas de cueilleur)"), `404` → `NotFound`, `500` → `InternalError`
- [X] T002 Run `mvn generate-sources` then `mvn -q compile`. Expected: compile succeeds (generated delegate methods have default bodies, so `controller/BucketController.java` needs no change yet), and `target/generated-sources/openapi/src/main/java/com/rfidback/generated/api/BucketApiDelegate.java` now declares `unassignBucketFromPicker(UUID bucketId)`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the two new test classes every story adds cases to.

**⚠️ CRITICAL**: complete before the story phases, because every story appends tests to these files.

- [X] T003 [P] Create `src/test/java/com/rfidback/service/PickerServiceTest.java`: mock `PickerRepository` and `BucketRepository` in `@BeforeEach`, build `new PickerService(pickerRepository, bucketRepository)`, add a private helper `PickerEntity picker(String lastname, String firstname)` that builds an entity with a random `id` and `OffsetDateTime.now()` as `creationDate`; no test methods yet besides one smoke test `getPicker_unknownId_throwsNotFound` (`findById` returns empty → `PickerNotFoundException`, FR-007)
- [X] T004 [P] Create `src/test/java/com/rfidback/controller/PickerApiTest.java` (`@SpringBootTest`, `@AutoConfigureMockMvc`, `@ActiveProfiles("test")`, `@Autowired MockMvc`, `@Autowired ObjectMapper`) with helpers: `RequestPostProcessor admin()` returning `user("admin").roles("ADMINISTRATEUR")`; `UUID createPicker(String lastname, String firstname)` that POSTs `/api/pickers` with `admin()` and `csrf()`, asserts `201` and returns the `id` from the JSON body; and `@Autowired BucketRepository` so tests can save a `BucketEntity` directly (unique `number` per test, e.g. from a static `AtomicInteger` starting at 90000). Add one smoke test: `GET /api/pickers` as admin → `200`

**Checkpoint**: `mvn clean test -Dtest='PickerServiceTest,PickerApiTest'` is green.

---

## Phase 3: User Story 1 - Créer un cueilleur (Priority: P1) 🎯 MVP

**Goal**: lock in the current create behaviour (FR-001, FR-002). No production change.

**Independent Test**: `mvn clean test -Dtest='PickerServiceTest,PickerApiTest'`. The create tests pass and cover `201`, the case-insensitive `409`, and trimming.

- [X] T005 [P] [US1] In `src/test/java/com/rfidback/service/PickerServiceTest.java` add: `createPicker_trimsNamesAndBlankCommentBecomesNull` (input `"  Dupont "`, `" Jean "`, comment `"   "` → saved entity has `"Dupont"`, `"Jean"`, `null`, checked with an `ArgumentCaptor<PickerEntity>`); `createPicker_duplicateIgnoringCase_throwsConflict` (`existsByLastnameIgnoreCaseAndFirstnameIgnoreCase` → `true` → `PickerAlreadyExistsException`, and `save` never called)
- [X] T006 [P] [US1] In `src/test/java/com/rfidback/controller/PickerApiTest.java` add: `createPicker_returns201WithBody` (`id` and `creationDate` present, names echoed); `createPicker_sameNameDifferentCase_returns409` (create `Martin`/`Paul` then `MARTIN`/`paul` → `409`)

**Checkpoint**: US1 protected by tests.

---

## Phase 4: User Story 2 - Consulter la liste des cueilleurs (Priority: P1)

**Goal**: `GET /api/pickers` honours `sort` through the allow-list and answers `400` to bad `sort`, `page` or `size` values. The dashboard stops requesting `size=500` (FR-003, SC-004, research R1, R2, R5).

**Independent Test**: `mvn clean test -Dtest='PickerServiceTest,PickerApiTest'`. Then run quickstart.md manual steps 1–2.

### Tests for User Story 2 (write first, must fail)

- [X] T007 [P] [US2] In `src/test/java/com/rfidback/service/PickerServiceTest.java` add tests calling `listPickers(0, 20, sort)` and capturing the `Pageable` passed to `pickerRepository.findAll(Pageable)` (stub it to return `Page.empty()`): `null` and `"  "` → `Sort.by(asc lastname, asc firstname, asc id)`; `"firstname,desc"` → `desc firstname, asc lastname, asc id`; `"creationDate,asc"` → `asc creationDate, asc lastname, asc firstname, asc id`; `"lastname,DESC"` and `" lastname , asc "` accepted; parameterized `ResponseStatusException` with status `400` for `"foo,asc"`, `"lastname,up"`, `"lastname"`, `"lastname,asc,extra"`, `"creationdate,asc"` (field names are case-sensitive), `",asc"`
- [X] T008 [P] [US2] In `src/test/java/com/rfidback/controller/PickerApiTest.java` add: `listPickers_sortFirstnameDesc_ordersDescending` (create two pickers with the same unique lastname and firstnames `Aaa`/`Zzz`, call `GET /api/pickers?size=100&sort=firstname,desc`, assert `Zzz` comes before `Aaa` in `content`); `listPickers_invalidSort_returns400` (`sort=foo,asc`); `listPickers_sizeAboveMax_returns400` (`size=500`); `listPickers_negativePage_returns400` (`page=-1`); `listPickers_defaultSortStillAccepted` (`sort=lastname,asc` → `200`)

### Implementation for User Story 2

- [X] T009 [US2] In `src/main/java/com/rfidback/service/PickerService.java` change `listPickers(int page, int size)` to `listPickers(int page, int size, String sort)`. Build the `Pageable` from a new private `Sort toSort(String sort)`. Blank or `null` returns `lastname ASC, firstname ASC, id ASC`. Otherwise split on `,` into exactly 2 trimmed parts; the field must be a key of a `private static final Map<String, List<String>> SORT_TIE_BREAKERS` (`lastname` → `firstname, id`; `firstname` → `lastname, id`; `creationDate` → `lastname, firstname, id`) and the direction must equal `asc` or `desc` ignoring case (`Sort.Direction.fromOptionalString`). Build `Sort.by(new Order(direction, field))` then `.and(...)` the tie-breakers ascending. Any violation throws `new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid sort '%s': expected field,asc|desc with field in lastname, firstname, creationDate".formatted(sort))`. Delete `DEFAULT_SORT`. The field name used in the query must always come from the map constant, never from the caller's string
- [X] T010 [US2] In `src/main/java/com/rfidback/controller/PickerController.java` pass `sort.orElse(null)` as the third argument to `pickerService.listPickers`
- [X] T011 [P] [US2] Create `src/main/java/com/rfidback/controller/ApiExceptionHandler.java`: `@RestControllerAdvice` with one `@ExceptionHandler(ConstraintViolationException.class)` method returning `ResponseEntity<ProblemDetail>` built with `ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage())` (research R5). Do not handle any other exception type, so the `@ResponseStatus` exceptions keep their current mapping
- [X] T012 [P] [US2] In `front/index.html` rewrite `fetchPickersInfo()` to loop `page = 0, 1, …` over `apiFetch(\`/pickers?size=100&page=${page}\`)`, filling `pickersMap` from each `content`, and stop when the response is not ok or `data.metadata.hasNext` is false (contracts/openapi-pickers.md, "Front contract")

**Checkpoint**: T007/T008 pass. `front/pickers.html` still lists pickers (it sends `sort=lastname,asc`), and the dashboard shows picker names.

---

## Phase 5: User Story 3 - Mettre à jour un cueilleur (Priority: P2)

**Goal**: lock in the current update behaviour (FR-005, FR-007). No production change.

**Independent Test**: `mvn clean test -Dtest='PickerServiceTest,PickerApiTest'`. The update tests pass.

- [X] T013 [P] [US3] In `src/test/java/com/rfidback/service/PickerServiceTest.java` add: `updatePicker_keepingOwnName_succeeds` (`existsBy...AndIdNot` → `false`, saved entity has the new comment); `updatePicker_nameTakenByAnother_throwsConflict` (`existsBy...AndIdNot` → `true` → `PickerAlreadyExistsException`); `updatePicker_unknownId_throwsNotFound`
- [X] T014 [P] [US3] In `src/test/java/com/rfidback/controller/PickerApiTest.java` add: `updatePicker_returns200` (create, PUT with the same names and a new comment → `200` and comment echoed); `updatePicker_toAnotherPickersName_returns409`; `updatePicker_unknownId_returns404` (PUT on a random UUID)

**Checkpoint**: US3 protected by tests.

---

## Phase 6: User Story 4 - Supprimer un cueilleur (Priority: P2)

**Goal**: deleting a picker who still has a bucket answers `409`. A bucket can be unassigned with `DELETE /api/buckets/{bucketId}/picker`, which unblocks the delete. The front explains the refusal (FR-006; spec `006` FR-007; research R3, R7).

**Independent Test**: `mvn clean test -Dtest='PickerServiceTest,PickerApiTest,AccessMatrixSecurityTest'`. Then run quickstart.md manual steps 3–4.

### Tests for User Story 4 (write first, must fail)

- [X] T015 [P] [US4] In `src/test/java/com/rfidback/service/PickerServiceTest.java` add: `deletePicker_withBucket_throwsConflictAndKeepsPicker` (`bucketRepository.existsByPicker` → `true` → `PickerHasBucketsException`, `pickerRepository.delete` never called); `deletePicker_withoutBucket_deletes`; `deletePicker_unknownId_throwsNotFoundBeforeBucketCheck` (`existsByPicker` never called)
- [X] T016 [P] [US4] In `src/test/java/com/rfidback/controller/PickerApiTest.java` add: `deletePicker_withAssignedBucket_returns409ThenUnassignThenDelete204`. Create a picker, save a `BucketEntity` with that picker through `BucketRepository`, then `DELETE /api/pickers/{id}` → `409` and `GET /api/pickers/{id}` → `200`. Then `DELETE /api/buckets/{bucketId}/picker` → `204`, then `DELETE /api/pickers/{id}` → `204` and `GET` → `404`. Also add `unassignBucket_unknownBucket_returns404` and `unassignBucket_alreadyUnassigned_returns204`. Use `csrf()` on every write
- [X] T017 [P] [US4] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java` add a `Route(HttpMethod.DELETE, "/api/buckets/" + ID + "/picker", null, ADMIN_ONLY)` next to the existing `PUT /api/buckets/{id}/picker` row

### Implementation for User Story 4

- [X] T018 [P] [US4] Create `src/main/java/com/rfidback/exception/PickerHasBucketsException.java`: `@ResponseStatus(HttpStatus.CONFLICT)`, `extends RuntimeException`, constructor taking a `String message`, same shape as `PickerAlreadyExistsException`
- [X] T019 [P] [US4] In `src/main/java/com/rfidback/repository/BucketRepository.java` add `boolean existsByPicker(PickerEntity picker);`
- [X] T020 [US4] In `src/main/java/com/rfidback/service/PickerService.java` `deletePicker`: after `loadPicker(pickerId)`, if `bucketRepository.existsByPicker(entity)` throw `new PickerHasBucketsException("Picker %s still has at least one bucket assigned; unassign it first".formatted(pickerId))`; otherwise delete as today (depends on T018, T019)
- [X] T021 [P] [US4] In `src/main/java/com/rfidback/service/BucketService.java` add `@Transactional public void unassignBucketFromPicker(UUID bucketId)`: load the bucket or throw `BucketNotFoundException("Bucket %s not found")` exactly like `assignBucketToPicker`, then `bucket.setPicker(null)` and `bucketRepository.save(bucket)`. It is idempotent when the bucket has no picker
- [X] T022 [US4] In `src/main/java/com/rfidback/controller/BucketController.java` override `ResponseEntity<Void> unassignBucketFromPicker(UUID bucketId)`: call `bucketService.unassignBucketFromPicker(bucketId)` and return `ResponseEntity.noContent().build()` (depends on T002, T021)
- [X] T023 [P] [US4] In `front/pickers.html` `confirmDelete()`: if `response.status === 409`, keep the modal open and show "Ce cueilleur a encore un seau affecté : désaffectez-le avant de le supprimer." (an alert is acceptable, matching the page's existing `alert` usage). If `404`, hide the modal and call `fetchPickers(currentPage)`. Keep the generic message for other errors

**Checkpoint**: T015–T017 pass. Deleting a picker who has a bucket is refused, and becomes possible after unassigning the bucket.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T024 [P] Update `.specify/specs/001-gestion-cueilleurs/spec.md`: FR-008, the "Authentification absente" edge case and the first Drift row now state that spec `008` delivered authentication (`SecurityConfig.java:119-121`, `AccessMatrixSecurityTest`); SC-003 no longer says "[NEEDS CLARIFICATION]" and instead points to `PickerServiceTest` and `PickerApiTest`
- [X] T025 [P] In `.specify/specs/006-gestion-seaux-affectation/spec.md` FR-007 and the "Aucune désaffectation possible" edge case, note that the route was delivered by feature `001` (tasks T001, T021, T022)
- [X] T026 Run `mvn clean install` (full build and all tests) and fix any failure
- [ ] T027 Run the manual steps of `.specify/specs/001-gestion-cueilleurs/quickstart.md` against the local app and record anything that differs

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none. T002 depends on T001.
- **Foundational (Phase 2)**: depends on Phase 1 (the test classes must compile against regenerated sources).
- **US1 (Phase 3)** and **US3 (Phase 5)**: depend only on Phase 2. Tests only, so they can run any time after it.
- **US2 (Phase 4)**: depends on Phase 2. It is independent of US4.
- **US4 (Phase 6)**: depends on Phase 2 and on T001/T002 for the generated `unassignBucketFromPicker`. It is independent of US2.
- **Polish (Phase 7)**: after all the stories you are shipping.

### Within each story

- Tests first (T007/T008, T015–T017) and confirm they fail.
- US2: T009 → T010. T011 and T012 are independent of both.
- US4: T018 + T019 → T020; T021 → T022; T023 is independent.

### Shared-file warning

`PickerServiceTest.java` and `PickerApiTest.java` receive tasks from every story, and `PickerService.java` is edited by US2 (T009) and US4 (T020). Tasks marked [P] in *different* stories that touch the same file must not run at the same time. Parallelism is safe within a story across different files.

## Parallel Example: User Story 4

```text
# Tests together (three different files):
T015 PickerServiceTest.java   T016 PickerApiTest.java   T017 AccessMatrixSecurityTest.java

# Then independent implementation pieces together:
T018 PickerHasBucketsException.java   T019 BucketRepository.java   T021 BucketService.java   T023 front/pickers.html

# Then the dependent ones:
T020 PickerService.java (after T018, T019)   T022 BucketController.java (after T021)
```

## Parallel Example: User Story 2

```text
T007 PickerServiceTest.java   T008 PickerApiTest.java           # tests
T011 ApiExceptionHandler.java  T012 front/index.html            # independent implementation
T009 PickerService.java → T010 PickerController.java            # sequential
```

## Implementation Strategy

### MVP first

1. Phase 1 + Phase 2 → tests run.
2. Phase 3 (US1) + Phase 4 (US2) → this fixes the dashboard `500` and delivers the sort. This is the smallest release that fixes a user-visible bug.
3. Stop and validate: `mvn clean test` and quickstart steps 1–2.

### Incremental delivery

1. MVP above.
2. US4: the delete rule and the unassign route (the only other behaviour change).
3. US3: regression tests only, which can go in any time.
4. Polish: sync the specs, full build, manual quickstart.

---

## Phase 8: Convergence

- [X] T028 Reject a blank `lastname` or `firstname` after trimming with `400` (`ResponseStatusException(HttpStatus.BAD_REQUEST, ...)`, same pattern as the sort check) in `createPicker` and `updatePicker` of `src/main/java/com/rfidback/service/PickerService.java`, before `ensureUniqueName`; add `PickerServiceTest` cases (`"   "` and `""` for each field, on create and update, `save` never called) and one `PickerApiTest` case (`POST /api/pickers` with `lastname` `"   "` → `400`) per FR-001 (partial)

---

## Phase 9: Terminologie de l'interface (FR-009, SC-005)

**Goal**: the front calls pickers "Cueilleurs" / "cueilleur" everywhere. "Opérateur" is left only where it names the user role (spec `008`), and "Picker" no longer appears in displayed text (Clarifications 2026-09-25).

**Scope**: displayed text only. Do not rename files (`pickers.html`), element ids (`pickerModal`, `pickerForm`, …), JS identifiers, API routes or backend code. Keep the `fa-users` icon. French capitalisation: a capital only at the start of a label or title ("Cueilleurs", "Liste des cueilleurs", "Nouveau cueilleur").

**Independent Test**: T034 returns only `front/users.html:49`. Then log in as Administrateur, open each page, and check that the nav reads "Cueilleurs" and that the pickers page, its add/edit/delete modals and its empty state never say "Opérateur" or "Picker".

- [X] T029 [P] In `front/pickers.html` change the displayed text only: `<title>` line 6 `Opérateurs - Admin` → `Cueilleurs - Admin`; nav link line 24 `Opérateurs` → `Cueilleurs`; heading line 47 `Liste des Opérateurs` → `Liste des cueilleurs`; button line 49 `Nouveau Picker` → `Nouveau cueilleur`; modal title line 84 and `openModal()` line 240 `Ajouter un Picker` → `Ajouter un cueilleur`; `editPicker()` line 249 `Modifier le Picker` → `Modifier le cueilleur`; delete confirmation line 123 `supprimer cet opérateur ?` → `supprimer ce cueilleur ?`; empty state line 164 `Aucun opérateur` → `Aucun cueilleur`
- [X] T030 [P] In `front/buckets.html` change the nav link text line 24 `Opérateurs` → `Cueilleurs`, and the link text line 90 `<a href="pickers.html">Opérateurs</a>` → `<a href="pickers.html">Cueilleurs</a>`
- [X] T031 [P] Change the nav link text `Opérateurs` → `Cueilleurs` inside the `<a href="pickers.html" …>` element of `front/index.html` (line 38), `front/tags.html` (line 30), `front/readers.html` (line 44) and `front/users.html` (line 24). Do **not** touch `front/users.html:49` ("Comptes Administrateur et Opérateur"), which names the user role
- [X] T032 [P] In `front/reader.html` line 229 change the subtitle prefix `` `Picker: ...${…}` `` → `` `Cueilleur : ...${…}` `` (French spacing before the colon); leave the comment on line 228 and `record.pickerId` unchanged
- [X] T033 In `.specify/specs/001-gestion-cueilleurs/spec.md` FR-009, replace "État actuel — …" with "Livré (tâches T029–T032)" and keep the decision text (depends on T029–T032)
- [X] T034 Verify SC-005: run `grep -rnE "Op[ée]rateur|Picker" front/*.html front/*.js | grep -vE "OPERATEUR|role"`, then check each remaining hit by hand. The only displayed text left MUST name the user role (`front/users.html:49`, plus the role option and labels at lines 103 and 122, which the `OPERATEUR` filter hides); any other hit that shows up on screen is a leftover to fix. JS identifiers such as `editPicker`, `fetchPickers` and `pickerModal` do not count (depends on T029–T032)

### Phase 9 dependencies

- T029–T032 touch different files and can run in parallel. None of them depends on Phases 1–8.
- T033 and T034 run after T029–T032.
- No automated test covers `front/` (no build, no JS tests), so T034 plus the manual check are this phase's acceptance.

---

## Amendment 2026-10-11: logical deletion of a picker

**Input**: spec Clarifications 2026-10-11 (FR-010, User Story 4 scenarios 3–6); plan "Amendment 2026-10-11"; research
R9–R15; data-model "Amendment 2026-10-11"; contract §5; quickstart "Amendment 2026-10-11". Code references are to
branch `006-suppression-seau` (spec `006` amendment, uncommitted at the time of writing).

**Branch**: create `001-suppression-cueilleur` from `006-suppression-seau` (research R15); open its PR (base `dev`)
after 006's.

**Story**: the amendment extends User Story 4 (`[US4]`). It is the only story, so it is also the MVP. Tests first: the
new ones fail until T041–T045 are done; the updated existing ones must keep passing.

**Key rules** (used by every task below):
- A picker is **deleted** when `picker.deleted_at` is not null (`PickerEntity.isDeleted()`). Set once, never cleared.
- `DELETE /api/pickers/{id}`: lock the picker (`findWithLockById`), `404` when missing **or deleted**, `409` while a
  bucket points at them (unchanged), else `deletedAt = OffsetDateTime.now(clock)`. Nothing is deleted: not the row, not
  the hours, not the records.
- A deleted picker is **unknown (`404`)** to `GET`/`PUT`/`DELETE /api/pickers/{id}`, bucket assignment and hour entry,
  and **not listed** by `GET /api/pickers` and `GET /api/work-hours`; but it is **still seen** by the duplicate-name
  rule (FR-002, its name stays taken) and by everything that reads history through records or work days (dashboard).
- `PickerService` gets the application `Clock` (bean in `configuration/ClockConfig.java`) and loses
  `PickerWorkDayRepository` (its only use was the hour deletion): constructor `(PickerRepository, BucketRepository,
  Clock)`.

## Phase 10: Setup (amendment)

- [X] T035 Update descriptions only in `src/main/resources/openapi/api.yaml`, as listed in [contracts/openapi-pickers.md](contracts/openapi-pickers.md) §5: `DELETE /pickers/{pickerId}` (logical delete wording), `GET /pickers` ("Deleted pickers are not listed."), the `404` wording of `GET`/`PUT /pickers/{pickerId}` ("unknown or deleted"), `PUT /buckets/{bucketId}/picker` (`404` also for a deleted picker), `GET /work-hours` ("Deleted pickers are not listed.") and `PUT /work-hours` (`404` also for a deleted picker). Where a response is a shared `$ref`, put the precision in the operation `description` instead. Run `mvn generate-sources`; no generated signature may change

## Phase 11: Foundational (amendment)

- [X] T036 [P] Add to `src/main/java/com/rfidback/entity/PickerEntity.java`: `@Column(name = "deleted_at") private OffsetDateTime deletedAt;` with a comment "Set by the logical delete; never cleared (spec 001, FR-010)", and `public boolean isDeleted() { return deletedAt != null; }`, copying `ReaderEntity.deletedAt`/`isDeleted()`
- [X] T037 [P] Add to `src/main/java/com/rfidback/repository/PickerRepository.java`: `Page<PickerEntity> findAllByDeletedAtIsNull(Pageable pageable)`, `List<PickerEntity> findAllByDeletedAtIsNull()`, and `Optional<PickerEntity> findWithLockById(@Param("id") UUID id)` with `@Lock(LockModeType.PESSIMISTIC_WRITE)`, `@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))`, `@Query("select p from PickerEntity p where p.id = :id")` and a Javadoc "orders a delete against a concurrent bucket assignment (spec 001, research R12)". Leave the two `existsByLastname…` methods unfiltered and add a comment saying deleted pickers are counted on purpose (FR-002/FR-010)

## Phase 12: User Story 4 - Supprimer un cueilleur, suppression logique (Priority: P2)

**Goal**: deleting a picker who has records or hours succeeds (`204`), hides them everywhere they could be chosen, and
leaves their history on the dashboard.

**Independent Test**: quickstart "Amendment 2026-10-11" — automated tests, then manual steps 1–6.

### Tests for User Story 4 (write first)

- [X] T038 [P] [US4] Update `src/test/java/com/rfidback/service/PickerServiceTest.java`: build the service with `new PickerService(pickerRepository, bucketRepository, Clock.fixed(Instant.parse("2026-10-11T08:00:00Z"), ZoneOffset.UTC))` and drop the `PickerWorkDayRepository` mock; in `listPickers` tests, stub `findAllByDeletedAtIsNull(any(Pageable.class))` instead of `findAll(any(Pageable.class))` (including the `sortUsedFor` helper); in the delete tests, stub `findWithLockById` instead of `findById`. Replace `deletePicker_withoutBucket_deletes` and `deletePicker_withoutBucket_deletesTheHoursFirst` by `deletePicker_withoutBucket_setsDeletedAtAndDeletesNothing` (`deletedAt` equals the fixed clock's instant; `verify(pickerRepository, never()).delete(any())`). Add `deletePicker_alreadyDeleted_throwsNotFoundBeforeBucketCheck`, `getPicker_deleted_throwsNotFound`, `updatePicker_deleted_throwsNotFound`, and `createPicker_nameOfADeletedPicker_throwsConflict` (the `existsBy…` stub returns `true`)
- [X] T039 [P] [US4] Add to `src/test/java/com/rfidback/controller/PickerApiTest.java` `deletePicker_withRecordAndHours_softDeletesAndKeepsHistory`: a saved picker with one `RecordEntity` (saved reader, `compliant(true)`) and one `PickerWorkDayEntity` for today (with a saved `UserEntity` as `updatedBy`, as `WorkHoursApiTest` does); capture `GET /api/records/stats` (as Administrateur, no parameter); `DELETE /api/pickers/{id}` → `204`; then `GET /api/pickers/{id}`, `PUT /api/pickers/{id}` (valid body) and `DELETE /api/pickers/{id}` → `404`; the picker is absent from every page of `GET /api/pickers?size=100`; `GET /api/records/stats` body is identical to the captured one and contains the picker's last name; `POST /api/pickers` with the same names in another case → `409`; the record and the work-day row still exist (repositories)
- [X] T040 [P] [US4] Update `src/test/java/com/rfidback/service/BucketServiceTest.java`: the assign tests stub `pickerRepository.findWithLockById` instead of `findById` (the `verify(pickerRepository, never()).findById(any())` check becomes `findWithLockById`); add `assignBucketToPicker_deletedPicker_throwsPickerNotFoundAndDoesNotSave`. Add to `src/test/java/com/rfidback/controller/BucketApiTest.java`: `assign_deletedPicker_returns404` and `deleteBucketThenPickerWithRecord_returns204` (a picker with a record and one bucket: `DELETE /api/buckets/{id}` → `204`, then `DELETE /api/pickers/{id}` → `204`; spec 006 T045)
- [X] T041 [P] [US4] Update `src/test/java/com/rfidback/service/WorkHoursServiceTest.java`: stub `pickerRepository.findAllByDeletedAtIsNull()` instead of `findAll()` (lines ~96 and ~288); add `saveDay_deletedPicker_throwsPickerNotFound` (the `findAllById` stub returns a picker with `deletedAt` set). Add to `src/test/java/com/rfidback/controller/WorkHoursApiTest.java` `aDeletedPickerIsNotListedAndCannotGetHours`: save hours for a picker, soft-delete them (`DELETE /api/pickers/{id}` as admin), then `GET /api/work-hours?day=` does not list them, and `PUT /api/work-hours` with an entry for them → `404`

### Implementation for User Story 4

- [X] T042 [US4] In `src/main/java/com/rfidback/service/PickerService.java`: replace the `PickerWorkDayRepository` field by `private final Clock clock;`; `listPickers` uses `pickerRepository.findAllByDeletedAtIsNull(pageable)`; `loadPicker` throws `PickerNotFoundException` also when the picker `isDeleted()` (same message); `deletePicker` loads with `findWithLockById(pickerId).filter(p -> !p.isDeleted())` (`404` otherwise), keeps the `existsByPicker` → `PickerHasBucketsException` check, then `entity.setDeletedAt(OffsetDateTime.now(clock))` and `pickerRepository.save(entity)`, with a comment citing spec 001 FR-010 (records and hours stay). Makes T038 and T039 pass
- [X] T043 [US4] Remove `deleteByPicker` (and its now-unused imports) from `src/main/java/com/rfidback/repository/PickerWorkDayRepository.java`; check nothing else calls it (`grep -rn deleteByPicker src`)
- [X] T044 [US4] In `src/main/java/com/rfidback/service/BucketService.java` `assignBucketToPicker`: load the picker with `pickerRepository.findWithLockById(pickerId).filter(p -> !p.isDeleted())` (after the bucket lock), same `PickerNotFoundException`. Makes T040 pass
- [X] T045 [US4] In `src/main/java/com/rfidback/service/WorkHoursService.java`: `describe` iterates `pickerRepository.findAllByDeletedAtIsNull()` instead of `findAll()`; in `saveWorkDay`, build the `pickers` map from `findAllById(...)` filtered on `!isDeleted()`, so an entry naming a deleted picker hits the existing `PickerNotFoundException`. Makes T041 pass
- [X] T046 [US4] In `front/pickers.html` (line ~130), replace "Cette action est irréversible et supprimera l'historique associé." by "Cette action est irréversible. Ses lectures et ses heures restent comptées sur le tableau de bord." (research R13)

**Checkpoint**: `mvn clean test -Dtest='PickerServiceTest,PickerApiTest,BucketServiceTest,BucketApiTest,WorkHoursServiceTest,WorkHoursApiTest,AccessMatrixSecurityTest'` is green.

## Phase 13: Polish (amendment)

- [X] T047 [P] In `CLAUDE.md` (Persistence), replace "Deleting a picker deletes their hours." by "Pickers are never physically deleted: `DELETE /api/pickers/{id}` sets `picker.deleted_at`, which hides them from the lists, bucket assignment and hour entry but keeps their name taken and their records and hours on the dashboard (spec 001, FR-010)."
- [X] T048 [P] In `doc/20251116-use_cases.md` "Supprimer un cueilleur": the final condition "Le cueilleur est supprimé du système ainsi que l'ensemble des données associées" and step 5 become "Le cueilleur n'apparaît plus dans les listes ; ses lectures et ses heures sont conservées et restent comptées"
- [X] T049 [P] In `.specify/specs/001-gestion-cueilleurs/spec.md`, append "**Livré** : `picker.deleted_at`, `PickerService.deletePicker` (suppression logique), filtres de `PickerService`, `BucketService` et `WorkHoursService`." to FR-010; in `.specify/specs/006-gestion-seaux-affectation/tasks.md`, tick T045 (done by T040's `deleteBucketThenPickerWithRecord_returns204`); in the same `spec.md`, set the header **Status** to "Implémenté — la spec décrit l'état initial (as-is) puis la cible ; amendement du 2026-10-11 (suppression logique, FR-010) implémenté sur la branche `001-suppression-cueilleur`" and append to User Story 4 scenario 2 "**Livré** : rejet `409` (`PickerHasBucketsException`) tant qu'un seau est affecté." Then mark spec `014`'s design as amended, with a dated line so its history stays readable: in `.specify/specs/014-heures-travail-cueilleurs/research.md` R7 and in `data-model.md` (`deleteByPicker` entry), add "**Amendé (2026-10-11, spec `001` FR-010)** : la suppression d'un cueilleur est logique ; ses heures sont conservées et `deleteByPicker` est supprimé."; in `plan.md` (lines ~105 and ~110 of the source tree), add the same note after `deleteByPicker` and after "deletePicker removes the picker's hours first"
- [X] T050 Run `mvn clean install`; the full suite must be green
- [X] T051 Run the manual check of [quickstart.md](quickstart.md) "Amendment 2026-10-11", steps 1–6, and record any deviation in [research.md](research.md)

## Dependencies (amendment)

- T035 first (descriptions only, nothing depends on it at compile time, but API-first).
- T036, T037 → every test and implementation task (they compile against `deletedAt`, `findAllByDeletedAtIsNull`, `findWithLockById`).
- T038–T041 written before T042–T045; each fails until its implementation task is done.
- T042 → T043 (removing `deleteByPicker` once `PickerService` no longer calls it). T044, T045, T046 are independent of T042 and of each other.
- T050 after all code tasks; T051 after T050.

### Parallel Opportunities (amendment)

- T036 and T037.
- T038, T039, T040, T041 (different files; T040 touches two files that no other test task touches).
- T044, T045, T046 alongside T042 → T043.
- T047, T048, T049.

```text
# After T035–T037:
Task: "T038 PickerServiceTest"   Task: "T039 PickerApiTest"   Task: "T040 Bucket tests"   Task: "T041 WorkHours tests"
# Then:
Task: "T042 → T043 PickerService, PickerWorkDayRepository"   Task: "T044 BucketService"
Task: "T045 WorkHoursService"   Task: "T046 pickers.html"
```

## Implementation Strategy (amendment)

1. T035–T037, then the tests T038–T041.
2. T042–T043: deleting any picker works and keeps history — **MVP** (removes the `500`).
3. T044–T045: a deleted picker can no longer be chosen for a bucket or given hours.
4. T046 and Polish T047–T051.
