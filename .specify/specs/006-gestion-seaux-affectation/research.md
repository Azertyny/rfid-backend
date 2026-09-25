# Research: Gestion des seaux et affectation (006)

The spec had no open `NEEDS CLARIFICATION` items after the 2026-09-24 and 2026-09-25 sessions. This file records the design decisions the plan depends on.

What already exists (checked on 2026-09-25):

- `GET /api/buckets`, `GET /api/buckets/{id}`, `PUT /api/buckets/{id}/picker` and `DELETE /api/buckets/{id}/picker` are implemented (`BucketService`, `BucketController`). The `DELETE` route was delivered by feature `001`.
- All four routes are Administrateur-only (`SecurityConfig.java:125`), and `AccessMatrixSecurityTest.java:74-78` already checks them for anonymous callers, Opérateurs and Administrateurs.
- `PUT` already overwrites an existing assignment, which matches FR-004a.

So the remaining work is: `bucketNumbers` on the picker API (FR-006), the front page (FR-008, FR-009, US 1-3), the bucket tests (SC-002) and fixing the out-of-date diagram.

## R1. Shape of `bucketNumbers` in the picker API

- **Decision**: in `api.yaml`, the `Picker` schema drops `bucketNumber` (integer) and gains `bucketNumbers`: `type: array`, `items: integer`, listed under `required`. It is always present, sorted by increasing number, and `[]` when the picker has no bucket.
- **Rationale**: the spec asks for an empty list rather than a missing field. Marking it `required` makes the generator initialise the list, so the service never returns `null`. Sorting the numbers gives the tests and the front a stable order. Nothing in `front/` reads `bucketNumber` (checked with `grep`), so removing the field breaks no client.
- **Alternatives considered**: keeping `bucketNumber` next to the list for compatibility. Rejected: nothing uses it, and it would stay wrong (which of the buckets?) for a picker with several buckets.

## R2. Building the lists in `PickerService`

- **Decision**:
  - `listPickers`: replace `Collectors.toMap` (which throws on a duplicate key) with `Collectors.groupingBy(picker id, mapping(BucketEntity::getNumber, toList()))`, then sort each list. The single `findAllByPickerIn` query per page stays.
  - `getPicker`: replace `findByPicker` (returns `Optional`, fails on two rows) with a new `List<BucketEntity> findAllByPickerOrderByNumberAsc(PickerEntity picker)`.
  - Remove `BucketRepository.findByPicker`: after this change nothing calls it, and keeping it would invite the same bug again.
- **Rationale**: this fixes the `500` on both routes with no extra query and no schema change.
- **Alternatives considered**: a `@OneToMany` `buckets` collection on `PickerEntity`. Rejected: it changes the entity model and brings lazy-loading and N+1 risks just to read one number per bucket.

## R3. The bucket API contract stays as is

- **Decision**: no change to `/buckets*` routes or to `BucketWithTagsAndPicker`. The page gets the picker's first name, last name and comment from the bucket, which is enough for the confirmation text ("actuellement affecté à <Prénom Nom>").
- **Rationale**: US 1 says the bucket shows the picker without their id. The page does not need the current picker's id: when choosing a new picker, the id comes from the pickers list (R4).
- **Alternatives considered**: adding `picker.id` so the dropdown can preselect the current picker. Rejected: not needed for any scenario, and it would change a spec'd response.

## R4. Choosing the picker on the page

- **Decision**: the "Affecter" window contains a text field and, under it, a `<select size="8">` listing every picker as "Nom Prénom", sorted by last name. Typing hides the options whose text does not contain the typed letters (case and accent-insensitive, using `normalize('NFD')`). The option values are picker ids. The list is loaded once when the page opens by walking `GET /api/pickers?size=100&page=N&sort=lastname,asc` until `metadata.hasNext` is false, the same loop as `front/index.html:219-226`.
- **Rationale**: FR-009 requires all pickers, sorted, filterable by typing, and no API change. `size` is capped at 100 (`api.yaml:755-763`), so paging is required once there are more than 100 pickers. A few hundred pickers means at most 3-4 calls. Plain HTML and JS follows the no-build rule for `front/`.
- **Alternatives considered**:
  - `<input list>` with a `<datalist>`: rejected because it returns the displayed text, not the id, and filtering behaves differently between browsers.
  - A new search parameter on `/pickers`: rejected by the 2026-09-25 clarification.
  - A JS combo-box library: rejected because it adds a dependency for one field.

## R5. Page layout and flows (`front/buckets.html`)

- **Decision**: a new page built like `pickers.html` (Bootstrap 5.3 + Font Awesome from the CDN, `auth.js`, `config.js`, `requireRole('ADMINISTRATEUR')` at load).
  - Table sorted by number (order from the API): N° du seau | Tags (count, with the UIDs in a `title`) | Cueilleur (or a grey "Non affecté") | Actions.
  - Actions: "Affecter" on every bucket (label "Réaffecter" when it already has a picker). "Désaffecter" appears only on buckets that have a picker (US 3 scenario 3).
  - Assigning is two steps in one modal. Step 1: choose the picker (R4). Step 2: confirm. The confirmation text is "Affecter le seau n°X à <Prénom Nom> ?", or, when the bucket already has a picker, "Seau n°X est actuellement affecté à <Prénom Nom>, le réaffecter à <Prénom Nom> ?" (FR-004a). "Valider" sends `PUT`.
  - Unassigning uses a confirmation modal: "Retirer <Prénom Nom> du seau n°X ?" → `DELETE`.
  - After each successful write, the list is reloaded from `GET /api/buckets`.
  - A "Seaux" link (`data-admin-only`) is added to the navigation bar of the five pages that have one (`index`, `pickers`, `readers`, `tags`, `users`).
- **Rationale**: follows the use case in `doc/20251116-use_cases.md:82-100` (select a bucket → list of pickers → confirmation → assign) and the look of the existing admin pages.
- **Alternatives considered**: using the browser's `confirm()`. Rejected because the other pages use Bootstrap modals.

## R6. Error handling on the page

- **Decision**:
  - `401` and `403` are handled by `apiFetch` as on other pages (redirect to login, or the access-denied message).
  - `404` on `PUT` or `DELETE` (the bucket, or the picker on `PUT`, was deleted in the meantime) shows "Ce seau ou ce cueilleur n'existe plus" in the modal and reloads both lists.
  - Other errors show a generic message in `#pageError` and leave the modal open.
  - An empty bucket list shows "Aucun seau. Les seaux sont créés à l'enregistrement des tags." with a link to `tags.html`.
  - An empty picker list disables "Suivant" and links to `pickers.html`.
- **Rationale**: covers the error and empty states. Buckets are only created by tag registration (spec `003`), so the empty state points there.

## R7. Two admins editing at the same time

- **Decision**: last write wins. `PUT` and `DELETE` are each a single transaction with no version check. The list reloads after each action, so the admin sees the real state.
- **Rationale**: a handful of administrators. The worst case is a reassignment that can be seen immediately and undone. Optimistic locking (`@Version` plus `409`) is not worth it here.

## R8. Tests (SC-002)

- **Decision**:
  - New `service/BucketServiceTest` (JUnit 5 + Mockito): sorted list with tags grouped by bucket, detail, `404` for an unknown bucket, assign, reassign (the old picker is replaced), unassign (including a bucket with no picker), `404` for an unknown bucket or picker on assign.
  - New `controller/BucketApiTest` (`@SpringBootTest` + MockMvc, `test` profile, Administrateur): the same routes over HTTP, with real data. A picker assigned to 2 buckets → `GET /api/pickers` and `GET /api/pickers/{id}` return `200` with 2 `bucketNumbers`, sorted.
  - `PickerServiceTest`: update the bucket mocks to `findAllByPickerIn` returning 2 buckets for one picker, and `findAllByPickerOrderByNumberAsc`.
  - `401` and `403`: already covered by `AccessMatrixSecurityTest.java:74-78`, no new test needed.
  - The front page is checked by hand ([quickstart.md](quickstart.md)).
- **Rationale**: follows the test layout already used for pickers (feature `001`).

## R9. Documentation in `doc/`

- **Decision**:
  - `doc/20251013-database_diagram.puml:66` becomes `picker ||--o{ bucket` (one picker has 0..n buckets).
  - `doc/20251116-use_cases.md` gets a short "Désaffecter un seau" use case next to "Affecter des seaux", and a note on the reassignment warning.
- **Rationale**: the spec's drift table asks for the diagram to be fixed, and `CLAUDE.md` tells readers to check `doc/` first, so it must not contradict the code.
