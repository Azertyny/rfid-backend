---

description: "Task list for 006 — Gestion des seaux et affectation à un cueilleur"
---

# Tasks: Gestion des seaux et affectation à un cueilleur

**Input**: Design documents from `.specify/specs/006-gestion-seaux-affectation/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included, because the spec requires them. SC-002 lists backend tests: `BucketService` unit tests, HTTP tests, and a picker with 2 buckets. The front is checked by hand. The picker-list fix changes behaviour (today it returns `500`), so write its tests first and check that they fail. The bucket routes already behave correctly, so their tests only lock in current behaviour and should pass right away.

**Organization**: Tasks are grouped by user story (spec.md US1–US3) so each story can be built and checked as its own increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: User story from spec.md (US1, US2, US3)
- Paths are relative to the repository root. Java sources: `src/main/java/com/rfidback/`; tests: `src/test/java/com/rfidback/`; front: `front/`.

## Conventions used by every task

- API-first: contract changes go into `src/main/resources/openapi/api.yaml` first, then `mvn generate-sources`.
- **No change** to `service/BucketService.java` or `controller/BucketController.java`: their behaviour already matches FR-001 to FR-004a and FR-007 (research, "What already exists").
- Service unit tests: plain Mockito, no Spring context. Mocks are created in `@BeforeEach` and the service is built with its constructor, as in `service/PickerServiceTest.java:45-50`.
- HTTP tests use `@SpringBootTest`, `@AutoConfigureMockMvc` and `@ActiveProfiles("test")`, with `user("admin").roles("ADMINISTRATEUR")` and `csrf()`, as in `controller/PickerApiTest.java`.
  - Each test creates its own data with unique names and bucket numbers, because the in-memory H2 database is shared between test classes. `PickerApiTest` uses an `AtomicInteger` starting at 90000; start at 91000 to avoid clashes.
  - Create buckets directly with `bucketRepository.save(BucketEntity.builder().number(n).build())`.
- Front pages: Bootstrap 5.3 and Font Awesome from the CDN, then `bootstrap.bundle.min.js`, `config.js` and `auth.js` (`front/pickers.html:131-133`).
  - Every call goes through `apiFetch(path, options)`, which adds CSRF and handles `401`/`403`.
  - Every value from the API is escaped before it goes into HTML (`escapeHtml`, as in `front/tags.html`).
  - The UI text is in French.
- If a run fails with `NoClassDefFoundError` or "Unresolved compilation problem", it is the VS Code Java extension racing Maven (`CLAUDE.md`). Rerun `mvn clean test`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: apply the contract change so the generated `Picker` model has `bucketNumbers`.

- [X] T001 Apply [contracts/openapi-buckets.md](contracts/openapi-buckets.md) section 1 to `src/main/resources/openapi/api.yaml`:
  - In `components.schemas.Picker`, replace the `bucketNumber` property with `bucketNumbers` (`type: array`, `items: {type: integer}`, description "Numbers of the buckets currently assigned to the picker, ascending; empty when none", `example: [12, 47]`) and add `bucketNumbers` to its `required` list.
  - Set the description of `PUT /buckets/{bucketId}/picker` to "Assign an existing bucket to an existing picker. If the bucket already has a picker, it is replaced (a picker may have several buckets)."
- [X] T002 Run `mvn generate-sources`. Expected: `target/generated-sources/openapi/src/main/java/com/rfidback/generated/model/Picker.java` has `getBucketNumbers()`/`setBucketNumbers(List<Integer>)` and no `setBucketNumber`. `mvn -q compile` now **fails** in `service/PickerService.java` (`setBucketNumber`); Phase 2 fixes that.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: get the build compiling again and make the picker API handle several buckets per picker (FR-006). Every story needs a green build. US2 then creates the several-buckets case.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

- [X] T003 [P] In `src/main/java/com/rfidback/repository/BucketRepository.java`, add `List<BucketEntity> findAllByPickerOrderByNumberAsc(PickerEntity picker);` and remove `Optional<BucketEntity> findByPicker(PickerEntity picker);`, along with the `Optional` import if nothing else uses it (research R2).
- [X] T004 [P] Write failing tests in `src/test/java/com/rfidback/service/PickerServiceTest.java`:
  - `listPickers_pickerWithTwoBuckets_returnsBothNumbersSorted`: mock `pickerRepository.findAll(any(Pageable.class))` → a `PageImpl` with one picker; mock `bucketRepository.findAllByPickerIn` → 2 buckets of that picker, numbers 47 and 12, in that order. Expect `bucketNumbers == [12, 47]`.
  - `listPickers_pickerWithoutBucket_returnsEmptyList`: expect `[]`, not `null`.
  - `getPicker_twoBuckets_returnsBothNumbers`: mock `findAllByPickerOrderByNumberAsc` → numbers 12 and 47. Expect `[12, 47]`.
  - `getPicker_noBucket_returnsEmptyList`.
  - Remove or rewrite any existing test that stubs `findByPicker` or reads `getBucketNumber`.
- [X] T005 Update `src/main/java/com/rfidback/service/PickerService.java` (research R2):
  - In `listPickers`, build a `Map<UUID, List<Integer>>` with `Collectors.groupingBy(b -> b.getPicker().getId(), Collectors.mapping(BucketEntity::getNumber, Collectors.toList()))`, keep the `picker != null` filter, and set `picker.setBucketNumbers(sorted copy or List.of())` on each picker.
  - Add a private `List<Integer> bucketNumbersOf(PickerEntity entity)` that returns the numbers from `bucketRepository.findAllByPickerOrderByNumberAsc(entity)`.
  - Use `bucketNumbersOf` in `getPicker` (replacing the `findByPicker` call) and in `updatePicker` on the saved result.
  - In `createPicker`, set `List.of()`, since a new picker has no bucket.
- [X] T006 Run `mvn clean test -Dtest='PickerServiceTest,PickerApiTest'`. Expected: all green, including the new T004 tests. Then run `grep -rn "setBucketNumber(\|getBucketNumber()\|findByPicker(" src/main src/test`. It must return nothing that concerns pickers. The tag-registration code (`/tags/buckets/{bucketNumber}` and its schemas) legitimately keeps its own `bucketNumber`, so ignore matches from there.

**Checkpoint**: the build compiles, and the picker API returns `bucketNumbers` for 0, 1 or several buckets.

---

## Phase 3: User Story 1 - Consulter les seaux et leurs tags (Priority: P1) 🎯 MVP

**Goal**: an Administrateur opens a "Seaux" page listing every bucket by number, with its tags and its picker (or "Non affecté").

**Independent Test**: `BucketServiceTest`/`BucketApiTest` list and detail tests pass. By hand, [quickstart.md](quickstart.md) §2 step 1: the page lists buckets sorted by number, and an Opérateur gets the access-denied message (step 9).

### Tests for User Story 1

These lock in current behaviour and should pass right away.

- [X] T007 [P] [US1] Create `src/test/java/com/rfidback/service/BucketServiceTest.java`. In `@BeforeEach`, mock `BucketRepository`, `TagRepository` and `PickerRepository`, then build `new BucketService(bucketRepository, tagRepository, pickerRepository)`. Add:
  - `listBuckets_sortsByNumberAndGroupsTags`: verify `findAll(Sort.by(Sort.Order.asc("number")))` is called. Two buckets, and `tagRepository.findAllByBucketIn` returns 2 tags for the first and none for the second. Expect `tags` to be `[uid1, uid2]` and `[]`, `picker` to be set with lastname/firstname/comment only on the assigned bucket, and `null` on the other.
  - `listBuckets_noBucket_doesNotQueryTags`: empty list, and `tagRepository.findAllByBucketIn` is never called.
  - `getBucket_returnsTagsAndPicker`.
  - `getBucket_unknown_throwsBucketNotFound`.
- [X] T008 [P] [US1] Create `src/test/java/com/rfidback/controller/BucketApiTest.java` (Administrateur, `test` profile, conventions above), with helpers `createPicker(lastname, firstname)` (via `pickerRepository.save`), `createBucket()` (unique number from an `AtomicInteger` starting at 91000) and `admin()`. Add:
  - `listBuckets_returnsCreatedBucketsInNumberOrder`: create 2 buckets and check that they appear in increasing number order in `$.buckets`.
  - `getBucket_returns200WithEmptyTagsAndNullPicker`.
  - `getBucket_unknownId_returns404`.

### Implementation for User Story 1

- [X] T009 [US1] Create `front/buckets.html`, copying the page skeleton and `<nav>` of `front/pickers.html`:
  - Title "Seaux - Admin". The new "Seaux" nav link (`<a href="buckets.html" data-admin-only class="btn btn-sm btn-primary"><i class="fas fa-fill me-1"></i>Seaux</a>`) is the active one; the "Opérateurs" link is not.
  - Header "Seaux" / "Affectation des seaux aux cueilleurs", and `<div class="alert alert-danger d-none" id="pageError">`.
  - A table with columns "N° du seau", "Tags", "Cueilleur", "Actions".
  - On load, `await requireRole('ADMINISTRATEUR')`, then `loadBuckets()`, which calls `GET /buckets` and renders one row per bucket in API order:
    - Tags: the count, with the UIDs joined in a `title` attribute.
    - Cueilleur: `Prénom Nom` (with the comment in `small.text-muted` if present), or `<span class="text-muted">Non affecté</span>`.
    - Actions: left empty for now; US2 and US3 fill them.
  - Empty list: one row "Aucun seau. Les seaux sont créés à l'enregistrement des tags." with a link to `tags.html` (research R6).
  - Non-OK response: message in `#pageError`.
  - Escape every value with an `escapeHtml` helper.
- [X] T010 [P] [US1] Add the "Seaux" nav link to the nav bar in `front/index.html`, `front/pickers.html`, `front/readers.html`, `front/tags.html` and `front/users.html`: `<a href="buckets.html" data-admin-only class="btn btn-sm btn-outline-light border-0"><i class="fas fa-fill me-1"></i>Seaux</a>`, placed right after the "Tags" link.

**Checkpoint**: an Administrateur can see all buckets; the page is reachable from every nav bar.

---

## Phase 4: User Story 2 - Affecter un seau à un cueilleur (Priority: P1)

**Goal**: from the page, assign or reassign a bucket.
- The picker is chosen in a list of **all** pickers, sorted by last name and filtered as you type (FR-009).
- A confirmation step names the current picker when there is one (FR-004a).
- A picker can hold several buckets, and the picker API still works in that case (FR-006).

**Independent Test**:
- The `BucketServiceTest` and `BucketApiTest` assignment tests pass, including the 2-bucket picker test.
- By hand, [quickstart.md](quickstart.md) §2 steps 2–5 and 10.

### Tests for User Story 2

- [X] T011 [P] [US2] Add to `src/test/java/com/rfidback/service/BucketServiceTest.java`:
  - `assignBucketToPicker_setsPickerAndSaves`.
  - `assignBucketToPicker_alreadyAssigned_replacesPicker`: the bucket has picker A, assign B, and expect `bucket.getPicker() == B` and a `save` call.
  - `assignBucketToPicker_unknownBucket_throwsBucketNotFound`: `pickerRepository` is never queried.
  - `assignBucketToPicker_unknownPicker_throwsPickerNotFoundAndDoesNotSave`.
- [X] T012 [P] [US2] Add to `src/test/java/com/rfidback/controller/BucketApiTest.java` (every `PUT` uses `.with(csrf())` and JSON `{"pickerId":"..."}`):
  - `assign_returns204AndDetailShowsPicker`.
  - `reassign_replacesPicker`: assign to A, then B. `GET /api/buckets/{id}` shows B's names, and this bucket's number is no longer in `bucketNumbers` of `GET /api/pickers/{A}`.
  - `assign_unknownBucket_returns404`.
  - `assign_unknownPicker_returns404`.
  - `assign_missingPickerId_returns400`.
  - `pickerWithTwoBuckets_listAndDetailReturn200WithBothNumbers`: assign 2 new buckets to one picker through the API. `GET /api/pickers/{id}` returns `200` with `$.bucketNumbers` equal to both numbers sorted. `GET /api/pickers?size=100&sort=lastname,asc`, walking the pages until the picker is found, returns `200` with the same list for it (SC-002 c).

### Implementation for User Story 2

- [X] T013 [US2] In `front/buckets.html`, add `loadPickers()`, called once after `requireRole` (research R4):
  - Loop `GET /pickers?size=100&page=${page}&sort=lastname,asc` from page 0 until `!data.metadata.hasNext`, as in `front/index.html:219-226`.
  - Keep an array `pickers` of `{id, label: lastname + ' ' + firstname, search: label normalized with normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase()}`.
  - On error, show a message in `#pageError`.
- [X] T014 [US2] In `front/buckets.html`, add the assign modal `#assignModal` (Bootstrap), with two steps in the same modal (research R5):
  - **Step 1**
    - Title "Affecter le seau n°X", with an `<input id="pickerFilter" placeholder="Rechercher un cueilleur">` and a `<select id="pickerSelect" size="8" class="form-select">`.
    - On each `input` event, rebuild the options from `pickers` whose `search` contains the normalized typed text, with value = id and text = label.
    - Leave out of the options the picker currently assigned to the bucket, matched on lastname + firstname: the bucket response has no picker id, and names are unique (`PickerService.ensureUniqueName`) (US2 scenario 6).
    - "Suivant" stays disabled until an option is selected. Double-click on an option also moves to step 2.
    - If `pickers` is empty, show "Aucun cueilleur. Créez-en un dans la page Opérateurs." with a link to `pickers.html`, and keep "Suivant" disabled.
  - **Step 2**
    - Text "Affecter le seau n°X à <Prénom Nom> ?". When the bucket already has a picker, use "Seau n°X est actuellement affecté à <Prénom Nom actuel>, le réaffecter à <Prénom Nom> ?" (FR-004a) with an `alert-warning` style.
    - Buttons "Retour" (back to step 1) and "Valider".
  - **"Valider"**
    - Sends `apiFetch('/buckets/' + id + '/picker', {method: 'PUT', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({pickerId})})`.
    - On `204`: hide the modal and call `loadBuckets()`.
    - On `404`: show "Ce seau ou ce cueilleur n'existe plus" in the modal, then call `loadBuckets()` and `loadPickers()`.
    - On any other failure: show a generic error in the modal and leave it open.
    - Disable "Valider" while the request is in flight.
  - Reset the filter, selection and step each time the modal opens.
- [X] T015 [US2] In the `front/buckets.html` row rendering (T009), add the action button: "Affecter" (`btn-outline-primary`, icon `fa-user-plus`) when the bucket has no picker, "Réaffecter" (`btn-outline-secondary`, icon `fa-exchange-alt`) when it has one. It opens `#assignModal` for that bucket, passing its id, number and current picker names.

**Checkpoint**: US1 + US2 cover the full `doc/20251116-use_cases.md:82-100` flow. A picker holding 2 buckets still shows correctly in `pickers.html`.

---

## Phase 5: User Story 3 - Désaffecter un seau depuis le front (Priority: P2)

**Goal**: remove the picker from a bucket after a confirmation that names them (FR-008). This lets an admin then delete that picker (spec `001`).

**Independent Test**: the `BucketServiceTest`/`BucketApiTest` unassignment tests pass. By hand, [quickstart.md](quickstart.md) §2 steps 6–8.

### Tests for User Story 3

- [X] T016 [P] [US3] Add to `src/test/java/com/rfidback/service/BucketServiceTest.java`:
  - `unassignBucketFromPicker_clearsPickerAndSaves`.
  - `unassignBucketFromPicker_noPicker_stillSucceeds`.
  - `unassignBucketFromPicker_unknownBucket_throwsBucketNotFound`.
- [X] T017 [P] [US3] Add to `src/test/java/com/rfidback/controller/BucketApiTest.java`:
  - `unassign_returns204AndDetailShowsNoPicker`: after assigning, `DELETE` with `csrf()`; then `$.picker` is null or absent.
  - `unassign_bucketWithoutPicker_returns204`.
  - `unassign_unknownBucket_returns404`.
  - `unassignThenDeletePicker_returns204`: this checks the spec `001` flow.

### Implementation for User Story 3

- [X] T018 [US3] In `front/buckets.html`, add the confirmation modal `#unassignModal` with the text "Retirer <Prénom Nom> du seau n°X ?" and the buttons "Annuler" (sends nothing) and "Confirmer".
  - "Confirmer" calls `apiFetch('/buckets/' + id + '/picker', {method: 'DELETE'})`.
  - On `204`: hide the modal and call `loadBuckets()`.
  - On `404`: same message and reload as in T014.
  - On any other failure: a generic error.
  - Disable "Confirmer" while the request is in flight.
- [X] T019 [US3] In the `front/buckets.html` row rendering, add a "Désaffecter" button (`btn-outline-danger`, icon `fa-user-minus`) **only** on buckets with a picker (US3 scenario 3). It opens `#unassignModal` for that bucket.

**Checkpoint**: all three stories work; a picker with buckets can be freed and then deleted from the UI.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T020 [P] In `doc/20251013-database_diagram.puml:66`, replace `picker ||--o| bucket` with `picker ||--o{ bucket` (research R9).
- [X] T021 [P] In `doc/20251116-use_cases.md`, under "Gestion des seaux":
  - Add to "Affecter des seaux" a variant: if the bucket is already assigned, the confirmation window shows the current picker and assigning replaces them.
  - Add a "Désaffecter un seau" use case in the same format, with actor Administrateur, starting condition "le seau est affecté à un cueilleur", end condition "le seau n'est plus affecté", and main steps: select the bucket → "Désaffecter" → confirmation window naming the picker → validate → the bucket is unassigned.
- [X] T022 [P] Update `.specify/specs/006-gestion-seaux-affectation/spec.md`:
  - Header: `**Feature Branch**` → `006-gestion-seaux-affectation`; `**Status**` → "Target — as-is spec updated by the 2026-09-24/25 clarifications, implemented on branch `006-gestion-seaux-affectation`".
  - FR-006: "Livré (006)".
  - Edge case "Aucune interface utilisateur" and the second Drift row: the page exists (`front/buckets.html`).
  - Edge case "Cardinalité" and the first Drift row: diagram fixed (T020).
- [X] T023 [P] In `.specify/specs/001-gestion-cueilleurs/spec.md`, point every mention of the single `bucketNumber` field to spec `006` FR-006 (`bucketNumbers`, delivered).
- [X] T024 Run `mvn clean install`, then `mvn clean test`. Both must pass, including `AccessMatrixSecurityTest` (the `/api/buckets/**` rows stay `401`/`403`/allowed for Administrateur).
- [X] T025 Run the manual checks in [quickstart.md](quickstart.md) §2, steps 1–10, and note any failure.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: T001 → T002.
- **Foundational (Phase 2)**: depends on Phase 1, and blocks every story because the build does not compile after T002 until T005 is done. T003 and T004 can run in parallel; T005 needs T003; T006 needs T004 and T005.
- **US1 (Phase 3)**: depends on Phase 2.
- **US2 (Phase 4)**: depends on Phase 2. Its front tasks (T013–T015) need `front/buckets.html` from T009.
- **US3 (Phase 5)**: depends on Phase 2. Its front tasks (T018–T019) need T009. They do not need US2.
- **Polish (Phase 6)**: T020–T023 can start any time. T024 and T025 come last.

### Within each story

- Backend tests (T007/T008, T011/T012, T016/T017) are in different files and can be written in parallel with the front tasks. They should pass as soon as they are written, because the bucket backend already exists. If one fails, it is a real defect: fix it in `BucketService` and record it in [research.md](research.md).
- Front tasks all edit `front/buckets.html`, so they are sequential: T009 → T013 → T014 → T015 → T018 → T019.

### Parallel Opportunities

- Phase 2: T003 and T004.
- US1: T007, T008 and T010 alongside T009.
- US2: T011 and T012 alongside T013–T015.
- US3: T016 and T017 alongside T018–T019.
- Polish: T020, T021, T022 and T023 together.

## Parallel Example: User Story 2

```text
# Backend tests (different files, run together):
Task: "T011 [US2] Assignment tests in src/test/java/com/rfidback/service/BucketServiceTest.java"
Task: "T012 [US2] Assignment + 2-bucket picker tests in src/test/java/com/rfidback/controller/BucketApiTest.java"

# Meanwhile, front (sequential, same file):
Task: "T013 → T014 → T015 in front/buckets.html"
```

## Implementation Strategy

### MVP first

1. Phase 1 + Phase 2: the picker API handles several buckets. This alone fixes the existing `500` bug.
2. Phase 3 (US1): a read-only "Seaux" page. **Stop and check** (quickstart steps 1 and 9).

### Incremental delivery

3. Phase 4 (US2): assign and reassign from the page. This is the flow from `doc/` and the main value of the feature.
4. Phase 5 (US3): unassign, which also unblocks deleting a picker (spec `001`).
5. Phase 6: fix the docs and run the full build plus the manual checks.

---

## Phase 7: Convergence

- [X] T026 Manually verify in `front/buckets.html` the two scenarios that T025's quickstart steps 1–10 do not exercise: with no bucket at all (fresh in-memory database), the table shows "Aucun seau. Les seaux sont créés à l'enregistrement des tags." with a link to `tags.html`; and on "Réaffecter" for a bucket assigned to picker A, A is absent from the picker list, including while filtering by A's name, per US1/AC4 and US2/AC6 (partial)
