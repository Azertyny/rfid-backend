# Research: Gestion des cueilleurs (001)

The spec's Technical Context had no open `NEEDS CLARIFICATION` items after the 2026-09-24 clarification session. This file records the design decisions the plan depends on, and one defect found while planning (R5).

## R1. Where the `sort` value is parsed and checked

- **Decision**: `PickerController.getPickers` passes the raw `Optional<String> sort` to `PickerService.listPickers(page, size, sort)`. The service turns it into a Spring `Sort` through a fixed allow-list (`lastname`, `firstname`, `creationDate` × `asc`, `desc`). Anything else throws `ResponseStatusException(HttpStatus.BAD_REQUEST, ...)`, the same way `UserService` already rejects a bad request (`UserService.java:61`).
- **Rationale**: the allow-list is business logic (spec FR-003, Clarifications 2026-09-24), so it sits in the service, where it can be unit-tested without Spring. The field name that reaches the JPA query always comes from our constant table, never from the caller (spec Edge Cases, "Paramètre `sort` invalide").
- **Alternatives considered**:
  - A `pattern` on the `sort` parameter in `api.yaml`. Rejected: the generator turns it into `@Pattern` on a `@Validated` interface, which throws `ConstraintViolationException`. Today nothing maps that to a status, so a real server answers `500` (see R5). It would also put the allow-list in two places.
  - Passing the value to Spring Data as a `Pageable` via `@PageableDefault`. Rejected: the delegate signature is generated as `Optional<String>`, and Spring Data would accept any entity property without the `400`.

## R2. Accepted format and ordering details

- **Decision**: exactly one `field,direction` pair. The field name is matched case-sensitively as documented (`creationDate`, not `creationdate`). The direction is matched case-insensitively (`ASC` is accepted), and surrounding spaces are trimmed. When `sort` is missing or blank, the order stays `lastname ASC, firstname ASC` as today. Every requested order gets `lastname, firstname, id` appended as tie-breakers, so that pages stay stable when two rows share the sort value (for example, two pickers created in the same second).
- **Rationale**: the front already sends `sort=lastname,asc` (`front/pickers.html:145`), so this must keep working unchanged. Stable tie-breakers keep a picker from appearing on two pages or on none.
- **Alternatives considered**: several pairs (`sort=lastname,asc&sort=firstname,desc`). Rejected: the parameter is a single string in `api.yaml`, and nobody needs it for fewer than 200 pickers (SC-004).

## R3. Blocking deletion of a picker who still has buckets

- **Decision**: add `boolean existsByPicker(PickerEntity picker)` to `BucketRepository`. `PickerService.deletePicker` calls it after `loadPicker` and throws a new `PickerHasBucketsException` (`@ResponseStatus(HttpStatus.CONFLICT)`, same style as `PickerAlreadyExistsException`) when it returns `true`.
- **Rationale**: `existsBy` also works when a picker has several buckets (spec `006` target), unlike `findByPicker`, which returns `Optional` and fails on two rows. The 404 check (unknown id) still comes first, as FR-007 requires.
- **Race (accepted)**: a bucket could be assigned between the check and the delete. The FK `bucket.picker_id` has no `ON DELETE`, so the database refuses the delete (`DataIntegrityViolationException` → `500`) instead of losing data. This is accepted at this scale (a handful of admins, fewer than 200 pickers). No lock is added.
- **Alternatives considered**: automatically unassigning the buckets, or `ON DELETE SET NULL`. Both were rejected by the clarification (the target is an explicit `409`).

## R4. Contract changes in `api.yaml`

- **Decision**: only descriptions change, so the generated signatures stay the same:
  - The `PageSort` description lists the allowed fields and directions and the `400`.
  - `DELETE /pickers/{pickerId}` → `409` points to a new `Conflict` response ("the resource is still in use") instead of `AlreadyExist`, whose wording ("ressource existe déjà") is wrong for a delete.
  - `GET /pickers` keeps `4XX: InvalidRequest`, which already covers `400`.
- **Rationale**: this follows the API-first rule from `CLAUDE.md` without forcing any change to the controller signature. See [contracts/openapi-pickers.md](contracts/openapi-pickers.md).

## R5. Defect found: page-size and page-number validation answers `500`

- **Finding** (checked on 2026-09-24 with a throwaway MockMvc test on the `test` profile): `GET /api/pickers?size=500` throws `jakarta.validation.ConstraintViolationException: getPickers.size: must be less than or equal to 100`. The generated `PickerApi` is `@Validated` with `@Max(100)` on `size`, and no handler maps that exception, so a real server answers `500`. The dashboard makes exactly this call (`front/index.html:214`), so it is broken today, which contradicts SC-004. `page=-1` fails the same way.
- **Decision**:
  1. Add a small `@RestControllerAdvice` (`controller/ApiExceptionHandler.java`) that maps `ConstraintViolationException` to `400` for every generated API. This matches `4XX: InvalidRequest` in the contract.
  2. Change `front/index.html` to page through `/pickers?size=100`. With fewer than 200 pickers that means at most 2 calls. It stops when `metadata.hasNext` is false.
- **Alternatives considered**: raising `@Max` to 500 in `api.yaml`. Rejected: it only moves the limit, and the list would still answer `500` for other bad values.

## R6. Tests (SC-003)

- **Decision**:
  - `src/test/java/com/rfidback/service/PickerServiceTest.java`: Mockito unit tests in the same style as `TagServiceTest` and `UserServiceTest`. They cover create/update uniqueness (FR-002/005), 404 (FR-007), `sort` parsing (each allowed field and direction, the default, and the `400` cases), and delete blocked vs. allowed.
  - `src/test/java/com/rfidback/controller/PickerApiTest.java`: `@SpringBootTest` + MockMvc on the `test` profile with a `user(...).roles("ADMINISTRATEUR")` post-processor and `csrf()`. It checks status codes end to end: `201`/`409` create, `400` bad sort, `400` for `size=500`, `409` delete with a bucket, and `204` after unassigning it.
  - Roles are already covered by `AccessMatrixSecurityTest` (spec `008`), so they are not repeated here.
- **Rationale**: the spec requires automated protection against regressions (SC-003). The HTTP-level test is the only one that can see the status-code mapping in R3 and R5.

## R7. Unassigning a bucket, pulled in from spec 006

- **Finding**: FR-006 tells the caller to unassign each bucket with `DELETE /api/buckets/{bucketId}/picker`, but that route does not exist (`api.yaml` only has `PUT /buckets/{bucketId}/picker`). Spec `006` FR-007 already decided to add it (`204`, or `404` when the bucket does not exist). Without it, the new `409` would be a dead end: a picker who has had a bucket could never be deleted, except by first reassigning the bucket to someone else.
- **Decision**: build that route in this feature, exactly as spec `006` FR-007 defines it: `api.yaml` → `BucketController.unassignBucketFromPicker` → `BucketService`, which sets `picker` to `null`. Unassigning a bucket that has no picker is idempotent (`204`). Access is already covered by the `/api/buckets/**` Administrateur rule (`SecurityConfig.java:125`), so there is no security change, but the route must be added to `AccessMatrixSecurityTest`.
- **Alternatives considered**: waiting for a 006 plan. Rejected: it would ship FR-006 in an unusable state.

## R8. Scope boundaries

- **FR-004 (`bucketNumbers` list)**: owned by spec `006-gestion-seaux-affectation`, which has no plan yet. It is not implemented here. The delete check (R3) already works when a picker has several buckets.
- **FR-008 (authentication)**: already delivered by spec `008` (`SecurityConfig.java:119-121`, `AccessMatrixSecurityTest`). Nothing to build. The spec text that still says "mécanisme non encore choisi" is out of date and should be updated.

---

## Amendment 2026-10-11: logical deletion of a picker (FR-010, User Story 4 scenarios 3–6)

What exists (checked on 2026-10-11, branch `006-suppression-seau`):

- Three foreign keys point at `picker`: `record.picker_id`, `bucket.picker_id`, `picker_work_day.picker_id` (non-null).
- `PickerService.deletePicker` refuses a picker with a bucket (`409`), deletes their hours (`PickerWorkDayRepository.deleteByPicker`), then deletes the row. With any record, the `record.picker_id` key makes the delete fail: `500` (spec Edge Cases, constat 2026-10-11).
- The dashboard (`RecordStatsService`, `GET /api/records/stats`) reads picker names through `left join r.picker` on records and on work days, not through `/pickers`; so a picker row that stays keeps its name on the dashboard with no change there.
- Pickers are offered for choice in two places only: `front/buckets.html` (walks `GET /api/pickers`) and `front/work-hours.html` (reads `GET /api/work-hours?day=`, built by `WorkHoursService.describe` from `pickerRepository.findAll()`).
- Readers already have the same pattern (spec `002`, FR-008): `reader.deleted_at`, `ReaderEntity.isDeleted()`.

## R9. A nullable `picker.deleted_at` column

- **Decision**: `PickerEntity.deletedAt` (`OffsetDateTime`, column `deleted_at`, nullable) and `isDeleted()`, as on `ReaderEntity`. `ddl-auto: update` adds the column; existing rows read `null` (not deleted). No data upgrade.
- **Rationale**: same shape as readers, so one convention in the code base. A timestamp also tells when the picker was removed, which a boolean would not.
- **Alternatives considered**: a boolean `active` flag (rejected: there is no reactivation, and the reader pattern uses a date); moving deleted pickers to another table (rejected: every foreign key would have to follow).

## R10. `DELETE /api/pickers/{id}` sets the date, nothing else

- **Decision**: `deletePicker` loads the picker **row-locked** (`PickerRepository.findWithLockById`, `PESSIMISTIC_WRITE`, 5 s), answers `404` when it is missing or already deleted, keeps the `409` when a bucket still points at them (`existsByPicker`), then sets `deletedAt = now(clock)`. It no longer deletes the hours (`deleteByPicker` loses its last caller and is removed). Records, hours and conformity changes are untouched.
- **Rationale**: FR-010. The lock orders a delete against a concurrent bucket assignment (R12): without it, an assignment could commit between the bucket check and the date, leaving a bucket assigned to a deleted picker.
- **Alternatives considered**: keep `deleteByPicker` for pickers with no record (two behaviours for one button; rejected by FR-010, "qu'il ait ou non des lectures ou des heures").

## R11. Where a deleted picker is hidden or refused

- **Decision**: one repository read for "live" pickers, used everywhere a picker is listed or targeted:
  - `PickerRepository.findAllByDeletedAtIsNull(Pageable)` for `GET /api/pickers` (pagination and `sort` unchanged, the allow-list stays).
  - `PickerService.loadPicker` (`GET`, `PUT`, `DELETE /api/pickers/{id}`): `404` when `isDeleted()`.
  - `BucketService.assignBucketToPicker`: `404` (`PickerNotFoundException`) for a deleted picker, after locking it (R12).
  - `WorkHoursService`: `describe` lists `findAllByDeletedAtIsNull()` instead of `findAll()`; `saveWorkDay` answers `404` (`PickerNotFoundException`) for an entry naming a deleted picker.
  - The name rule (FR-002) is **not** filtered: `existsByLastnameIgnoreCaseAndFirstnameIgnoreCase…` keeps seeing deleted rows, so their name stays taken (`409`), as readers' uids do.
- **Rationale**: FR-010 lists exactly these surfaces. Everything that shows history (dashboard, records, kiosk) reads through records and needs no change (what exists, above).
- **Alternatives considered**: a Hibernate `@SQLRestriction("deleted_at is null")` on `PickerEntity`: rejected, it would also hide the picker from `record.picker` joins and lose their name on the dashboard, and make the duplicate-name rule miss deleted rows.

## R12. Assignment against a concurrent delete

- **Decision**: `BucketService.assignBucketToPicker` loads the picker with `findWithLockById` (after the bucket lock it already takes, spec `006` R12) and refuses a deleted one with `404`. `deletePicker` takes the picker lock first, then checks the buckets.
- **Rationale**: whichever comes second waits and sees the first one's result: an assignment after the delete gets `404`; a delete after the assignment gets `409`. Lock order (bucket then picker in assign; picker only in delete, which takes no bucket lock) cannot deadlock.
- **Accepted limit**: `WorkHoursService.saveWorkDay` reads its pickers without a lock, so a `PUT /api/work-hours` running at the same moment as the delete can still save hours for the picker just deleted. Harmless: those hours are kept and counted like the ones entered before the delete (FR-010), and the picker is no longer offered afterwards. No lock is added for it.

## R13. Front

- **Decision**: one text change. `pickers.html` already calls `DELETE` with a confirmation and reloads the page; the deleted picker disappears because `GET /api/pickers` no longer returns them, and `buckets.html` and `work-hours.html` read the filtered lists. But the confirmation says "Cette action est irréversible et supprimera l'historique associé." (`front/pickers.html:130`), which becomes false: it becomes "Cette action est irréversible. Ses lectures et ses heures restent comptées sur le tableau de bord." The button "Supprimer définitivement" stays (the deletion is final).
- **Rationale**: FR-010 changes what the API returns and what a deletion keeps; the page must not promise the opposite.

## R14. Tests

- **Decision**:
  - `PickerServiceTest`: replace `deletePicker_withoutBucket_deletes` and `deletePicker_withoutBucket_deletesTheHoursFirst` with `deletePicker_setsDeletedAtAndKeepsHours` (no `delete`, no `deleteByPicker`); `deletePicker_alreadyDeleted_throwsNotFound`; `getPicker`/`updatePicker` on a deleted picker → `PickerNotFoundException`; `createPicker` with the name of a deleted picker → `409`; the delete tests stub `findWithLockById`.
  - `PickerApiTest`: a picker with a record and hours → `DELETE` `204`; absent from `GET /api/pickers`; `GET`/`PUT`/`DELETE /api/pickers/{id}` `404`; `GET /api/records/stats` body identical before and after, with the picker's name and hours in it.
  - `BucketApiTest`: assigning a bucket to a deleted picker → `404`; spec `006` T045 (delete the bucket of a picker who has a record, then delete the picker → `204`).
  - `WorkHoursApiTest` (or the class that tests `/api/work-hours`): a deleted picker is not in `GET /api/work-hours?day=`; `PUT` with their id → `404`; their hours still count in `GET /api/records/stats`.
- **Rationale**: one test per scenario 3–6 of User Story 4 and per surface of R11.

## R15. Branch and documentation

- **Decision**: branch `001-suppression-cueilleur` from `006-suppression-seau` (spec `006` T045 and its `BucketApiTest` test live there; spec `014`'s hours, which this changes, are in `dev` since `0cd0501`). Open its PR after 006's. Docs: `CLAUDE.md` Persistence ("Deleting a picker deletes their hours" → pickers are soft-deleted, `picker.deleted_at`, their records and hours stay); `doc/20251116-use_cases.md` "Supprimer un cueilleur" (the final condition "supprimé ainsi que l'ensemble des données associées" becomes "n'apparaît plus ; ses lectures et heures sont conservées"); spec `008` access table unchanged (`DELETE /api/pickers/{id}` already Administrateur-only).
