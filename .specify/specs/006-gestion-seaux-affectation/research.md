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

---

## Amendment 2026-10-11: deleting a bucket (FR-010, User Story 4)

What exists (checked on 2026-10-11):

- Two columns point at a bucket: `tag.bucket_id` (`TagEntity.bucket`) and `record.bucket_id` (`RecordEntity.bucket`, added by the spec `004` amendment). `RecordEntity.bucket` only serves the crate window (`TagService.crateOf`, `RecordRepository.findFirstByReaderAndBucketAnd…`); no API response, stat or page reads it. The picker of a record is copied at scan time (`TagService.count`).
- Nothing else references a bucket: registration sessions name a bucket **number**, `PickerService` derives `bucketNumbers` from the `bucket` table, `RecordStatsService` and work hours ignore buckets.
- `/api/buckets/**` is Administrateur-only for every method (`SecurityConfig.java:166`), so a new `DELETE /api/buckets/{bucketId}` needs no security change; spec `008`'s row `/api/buckets/**` already covers it.
- `BucketNotFoundException` already maps to `404`.

## R10. Physical delete, in one transaction

- **Decision**: `BucketService.deleteBucket(bucketId)`, `@Transactional`:
  1. lock the bucket row (`BucketRepository.findWithLockById`, R12), `404` when missing;
  2. `TagRepository.clearBucket(bucket)`: `update TagEntity t set t.bucket = null where t.bucket = :bucket`;
  3. `RecordRepository.clearBucket(bucket)`: `update RecordEntity r set r.bucket = null where r.bucket = :bucket`;
  4. `bucketRepository.delete(bucket)`.
- **Rationale**: the spec's assumption (no history of its own, number reusable). Two bulk updates make the cost independent of the number of tags or records. Clearing `record.bucket_id` only matters for the 2-second crate window: a crate whose bucket was just deleted can no longer gather a second tag, but that tag has been freed anyway (it no longer has the bucket).
- **Alternatives considered**:
  - Soft delete (`bucket.deleted_at`, like readers): rejected in the spec; it would also keep the number taken while `registerTagsForBucket` looks buckets up by number.
  - `ON DELETE SET NULL` on the foreign keys: not expressible through `ddl-auto: update` on existing columns, and invisible from the code.
  - Refusing a bucket with tags or a picker: rejected by Clarifications 2026-10-11.

## R11. Picker, tags and the scan rules after a delete

- **Decision**: nothing more to do.
  - The picker loses the bucket because the row is gone; `bucketNumbers` no longer lists it, and `existsByPicker` no longer blocks deleting the picker (US 4, scenario 7).
  - A freed tag has `bucket = null`. Its `counted_record` is untouched: a tag in a bucket always has `counted_record = null` (registration clears it, spec `004`), so freeing a tag gives exactly the state of a tag never registered. A production scan counts it without picker (`TagService.count`), and registering it into a bucket asks for no move confirmation (`registerTagsForBucket` only lists tags whose bucket number differs).
- **Rationale**: the existing rules already give the spec's scenarios 5 and 7.

## R12. Concurrent scan, registration or assignment

- **Decision**: one new locking read, `BucketRepository.findWithLockById(id)` (`@Lock(PESSIMISTIC_WRITE)`, 5 s lock timeout, `select b from BucketEntity b where b.id = :id`), and its by-number twin `findWithLockByNumber(number)`:
  - `deleteBucket`, `assignBucketToPicker` and `unassignBucketFromPicker` load the bucket through it (instead of `findById`).
  - `TagService.count` (rule 4 of a scan, the only rule that inserts a record pointing at a bucket): when the tag has a bucket, it locks it first; if the lock read finds no row, the bucket was deleted meanwhile, so the tag is treated as having no bucket (`tag.setBucket(null)`, record without picker nor bucket).
  - `TagService.registerTagsForBucket` looks the bucket up with `findWithLockByNumber`; a bucket deleted meanwhile is simply not found and is created again.
- **Rationale**: without a lock, a scan inserting `record.bucket_id = B` while B is being deleted fails on the foreign key (or makes the delete fail), and an assignment flushing an `UPDATE` on a deleted row fails with a stale-state error: a `500` either way, which the spec's edge case forbids. With the lock, whichever comes second waits (milliseconds) and then sees the committed state. The lock only makes scans of tags of the **same** bucket wait for each other. It is taken in rule 4, after the crate rule (`crateOf`) has run, so it does not change how crates are detected (spec `004`, FR-010). Only existence is read from the lock query, so the stale-instance caveat of `ReaderRepository` (an entity already in the persistence context comes back as it was) does not matter here.
- **Alternatives considered**:
  - A shared lock (`PESSIMISTIC_READ`) on the scan side: H2, used by dev and tests, turns it into an exclusive lock anyway, so the behaviour would differ between environments for no gain.
  - Retrying on `DataIntegrityViolationException`: hides the race instead of ordering it, and the reader device would have to resend.
  - Accepting the race: a scan line runs all day; a delete during the shift is plausible.

## R13. Front: "Supprimer" on every bucket

- **Decision**: in `front/buckets.html`, a "Supprimer" button (`btn-outline-danger`, `fa-trash`) on every row, after the existing actions. It opens a Bootstrap modal (like "Désaffecter") titled "Supprimer le seau" whose text is built from the loaded bucket:
  - always "Supprimer le seau n°X ?";
  - if it has tags: "Ses N tags seront libérés et devront être réenregistrés.", or "Son tag sera libéré et devra être réenregistré." for 1;
  - if it has a picker: "Il est affecté à <Prénom Nom>, qui ne l'aura plus.";
  - "Les lectures passées sont conservées."
  "Confirmer" (`btn-danger`) sends `DELETE /buckets/{id}` through `apiFetch`; `204` closes the modal and reloads `GET /buckets`; `404` shows "Ce seau n'existe plus" and reloads; other errors show the generic message and keep the modal open (same handling as R6).
- **Rationale**: FR-010 asks for the number, the freed tags and the picker in the confirmation; the page already has the data. The button is always shown since any bucket may be deleted.

## R14. Tests (SC-003)

- **Decision**:
  - `BucketServiceTest` (Mockito): delete → tags and records cleared, then bucket deleted; unknown bucket → `BucketNotFoundException`, nothing cleared; assign/unassign now go through `findWithLockById`.
  - `BucketApiTest` (MockMvc, real data): a bucket with 2 tags, a picker and a counted record → `DELETE` `204`; `GET /api/buckets/{id}` `404`; the bucket is gone from `GET /api/buckets` and the picker's `bucketNumbers`; second `DELETE` `404`; `GET /api/records/stats` identical before and after; the record keeps its picker; re-registering the same number creates a new empty-then-filled bucket without picker and with no `409`; the picker can then be deleted (`204`).
  - `TagScanApiTest`: a freed tag scanned in production → a record with no picker.
  - `TagServiceTest`: rule 4 with a bucket whose lock read is empty → record with no picker nor bucket, tag without bucket.
  - `AccessMatrixSecurityTest`: + `DELETE /api/buckets/{id}` (`ADMIN_ONLY`).
  - The concurrency of R12 is not tested automatically (would need two real transactions on PostgreSQL); the lock queries are covered by the tests above, which run through them.
- **Rationale**: SC-003's list, in the existing test classes.

## R15. Documentation

- **Decision**: `doc/20251116-use_cases.md` gets a "Supprimer un seau" use case after "Désaffecter un seau". `CLAUDE.md` (Persistence) gets one sentence: deleting a bucket frees its tags and unlinks its records (`record.bucket_id`), which keep their picker. The database diagram does not change (no column added or removed).

## Deviation found by T044 (2026-10-11): deleting a picker who has records answers `500`

Running quickstart §3 against the built app (in-memory H2, port 8099), every step behaved as specified except step 8: `DELETE /api/pickers/{id}` for picker A, who no longer had a bucket but had one record from step 3, answered `500` (`record.picker_id` foreign key, `DataIntegrityViolationException` in `PickerService.deletePicker`). This is **not caused by the bucket delete**: unassigning a bucket and then deleting a picker who has records gives the same `500` on `dev`. `deletePicker` only checks buckets (`409`) and work hours (deleted), not records. What should happen to a deleted picker's records (refuse with `409`, keep them without picker, or delete them) is a spec `001` decision, left out of this amendment. US 4 scenario 7 holds for a picker with no record (`BucketApiTest.deleteBucket_numberReusable_andPickerDeletable`).
