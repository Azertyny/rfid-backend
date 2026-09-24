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
