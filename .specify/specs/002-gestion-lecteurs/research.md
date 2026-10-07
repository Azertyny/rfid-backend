# Research: Gestion des lecteurs (002)

The 2026-09-24 clarification sessions left no open `NEEDS CLARIFICATION` item that blocks design. The two markers still in the spec are about the as-is state: no business doc exists for this module, and SC-002 says there are no tests. This plan settles the second one (R8). This file records the design decisions the plan depends on.

## R0. What is already delivered

- **Finding**: spec `008` already delivered FR-003 and FR-005. `GET /api/readers` is open to Opérateur and Administrateur and every other `/api/readers/**` route is Administrateur-only (`SecurityConfig.java`, access matrix). `ReaderService.getReaders` leaves out `apitoken` for non-Administrateurs, which `ReaderTokenVisibilityTest` covers. The token hard-coded in `front/index.html:155` is gone.
- **Consequence**: this plan covers FR-001a, FR-006 and FR-007, adds the `active` flag to FR-003, and adds tests (SC-002). It changes no security rule: the new routes already fall under `/api/readers/**` → Administrateur.

## R1. How the new routes identify a reader

- **Decision**: expose the reader's `id` (UUID) in the `Reader` schema. The new routes use `/readers/{readerId}` with `format: uuid`, the same as `/users/{userId}` and `/pickers/{pickerId}`.
- **Rationale**: a `uid` is free text (`"Reader 1"` has a space) and duplicates are now checked ignoring case (FR-007). Putting it in a path means URL-encoding it and deciding how case applies. A UUID has neither problem, and `ReaderRepository` is already keyed by `UUID`.
- **Alternatives considered**: using `uid` in the path, like `GET /records/readers/{readerId}` does today. Rejected for the reasons above. That existing records route keeps taking a uid: changing it belongs to spec `005` and would break `front/reader.html`.

## R2. Shape of the deactivate/reactivate and rotation operations

- **Decision**:
  - `PATCH /readers/{readerId}` with body `UpdateReader { active?: boolean }` → `200` with the `Reader`. It follows `PATCH /users/{userId}` (`UpdateUser`, "at least one field must be present"). An empty body → `400`.
  - `POST /readers/{readerId}/token` with no body → `200` with the `Reader`, including the new `apitoken`.
- **Rationale**: `PATCH` with a partial body is the pattern the API already uses for enabling and disabling users. Spec `003` will add a `mode` field to the same `UpdateReader` without a new route. Rotation is not idempotent (each call makes a new secret), so it is a `POST` on a sub-resource and not a field in `PATCH`.
- **Alternatives considered**:
  - Separate `POST /readers/{id}/deactivate` and `/activate` routes. Rejected: two routes for one boolean, unlike `/users`.
  - `PUT /readers/{id}` replacing the whole reader. Rejected: `uid` can't be renamed (out of scope), so there is nothing else to replace.
  - Rotation as `PATCH { rotateToken: true }`. Rejected: it mixes a command with state in one body, and the response carries a secret while a plain state change does not.

## R3. Making a deactivated reader or an old token stop working

- **Decision**: `ReaderApiTokenAuthenticationFilter` refuses with `401` when `findByApitoken` finds nothing (old token after a rotation) or finds a reader with `active = false`. The two cases get different messages ("Invalid API token" / "Reader disabled"). The caller already holds the token, so the second message tells an installer nothing new, and it saves time in the field.
- **Rationale**: the filter already queries the database on every scan and there is no token cache. A rotation or deactivation therefore applies to the very next request, which is what FR-006 means by "dès la fin de l'opération". No session exists for readers (stateless chain), so there is nothing to expire.
- **Alternatives considered**: `403` for a disabled reader. Rejected: FR-006 says `401`, and the front and devices only handle `401` for token problems today.

## R4. Adding a NOT NULL `active` column with `ddl-auto: update`

- **Decision**: `@Column(nullable = false) @ColumnDefault("true") private boolean active`, plus `@Builder.Default` set to `true`.
- **Rationale**: there is no migration tool (`CLAUDE.md`). Hibernate's schema update issues `alter table reader add column active boolean not null`. Without a default, that fails on any existing row in H2 (dev file) and PostgreSQL (prod). With `@ColumnDefault("true")`, Hibernate writes `default true`, so existing readers become active, which matches today's behaviour. `@Builder.Default` keeps `ReaderEntity.builder()...build()` (used in `ReaderService` and in tests) from producing `false`.
- **Alternatives considered**: a nullable `Boolean` treated as active when null. Rejected: three states for a two-state flag, and every reader of the field has to remember the null case.

## R5. `uid` validation and the duplicate check (FR-001a, FR-007)

- **Decision**:
  - `api.yaml`: `CreateReader.uid` gets `maxLength: 50`, as `CreatePicker.lastname` has. The generated `@Size` rejects longer values with `400` before the service runs.
  - `ReaderService.createReader`: trim the value and reject blank with `400` (`ResponseStatusException`, the same as `PickerService.requiredName`). Then call `readerRepository.existsByNameIgnoreCase(trimmed)` and throw a new `ReaderAlreadyExistsException` (`@ResponseStatus(CONFLICT)`) if a reader matches. Only the trimmed value is stored.
- **Rationale**: this is the picker rule, as the spec's clarification asks, and it uses the same mechanisms, so the error bodies look alike.
- **Known limits (accepted)**:
  - `maxLength` is checked before trimming, so 50 characters plus surrounding spaces is refused. Pickers behave the same way, and spec FR-001a states it.
  - The database unique constraint on `name` is case-sensitive. Two concurrent creates of `"Reader 1"` and `"reader 1"` can both pass the check. There are only a few readers and only Administrateurs create them, which is the same trade-off pickers accept. A same-case race hits the database constraint instead. `ReaderService` saves with `saveAndFlush` and turns that `DataIntegrityViolationException` into `ReaderAlreadyExistsException`, so it answers `409` and not `500` (spec FR-007).
  - Readers created before this change may already hold blank or case-duplicate `uid`s. They are not rewritten. The check only applies to new creations.

## R6. Token format on rotation

- **Decision**: rotation uses the same generator as creation: a random UUID without dashes, 32 hex characters, 122 random bits. The generator moves from `@PrePersist` into `ReaderEntity.newApitoken()`, which both `@PrePersist` and the rotation call.
- **Rationale**: devices and the `length = 64` column already accept this format. `UUID.randomUUID()` uses `SecureRandom`. Keeping one generator means a rotated token can't differ in format from a new one.
- **Alternatives considered**: a longer token from `SecureRandom` bytes. Rejected: nothing requires it, and the format would then depend on the reader's age.

## R7. What the front does with `active` (FR-003: "the front decides")

- **Decision**:
  - `front/readers.html` (Administrateur): an "État" column shows an Actif/Désactivé badge. Each row gets actions: Désactiver/Réactiver (with a confirm) and Régénérer la clé (with a confirm warning that the device must be reconfigured). The new token is shown in the existing "new token" modal. Actions refer to readers by `id`, never by `uid`, so no free text goes into an `onclick`.
  - `front/index.html` (dashboard): leave out inactive readers when building production lines. They can't scan, so a line for them would stay empty.
  - `front/reader.html` (per-reader live view): keep inactive readers, show them greyed with a "désactivé" label. Their past records are still worth looking at.
- **Rationale**: the backend returns everything (clarification). Each page filters for its own purpose.

## R8. Tests (SC-002)

- **Decision**:
  - `ReaderServiceTest` (Mockito): trimming, blank → `400`, duplicate ignoring case → `409`, rotation changes the token, `PATCH` with an empty body → `400`, unknown id → `404`.
  - `ReaderApiTest` (MockMvc, `test` profile, logged in as Administrateur): the `201`/`400`/`409` codes on create, `200`/`404` on the new routes, and the `active` and `id` fields in `GET`.
  - `ReaderScanSecurityTest` gets two cases: the old token after a rotation → `401`, a deactivated reader → `401`, then `200` again after reactivation.
  - `AccessMatrixSecurityTest` gets rows for `PATCH /api/readers/{id}` and `POST /api/readers/{id}/token` (ADMIN_ONLY).
- **Rationale**: this covers every acceptance rule in FR-001a, FR-006 and FR-007 at the level where it is enforced.

---

# Amendment 2026-10-06: deleting a reader (FR-008)

The four clarifications of 2026-10-06 settle the behaviour. One point stayed open after them: what the other management routes answer for a deleted reader. R12 settles it (`404`), as the clarify report suggested.

## R9. How "deleted" is stored

- **Decision**: a nullable `deleted_at` timestamp (`OffsetDateTime deletedAt`) on `ReaderEntity`, plus a helper `isDeleted()`. `null` means not deleted.
- **Rationale**: `ddl-auto: update` adds a nullable column over existing rows with no default (unlike `active`, research R4). The timestamp also tells when the reader was deleted, which matters when someone later wonders where a line went. Readers are never physically removed, so the foreign keys from `record`, `record_conformity_change`, `line_activity_change` stay valid.
- **Alternatives considered**:
  - A `deleted boolean not null default false`. Rejected: it works, but loses the date for no gain.
  - Hibernate `@SQLRestriction("deleted_at is null")` on the entity, so deleted readers vanish from every query. Rejected: records still reference a deleted reader by `@ManyToOne`, and spec answers 2 and 3 need the row to stay visible to `existsByNameIgnoreCase`, `GET /records/readers/{uid}` and the stats filter. A global restriction would break all three.
  - A physical delete. Rejected by the spec (Clarifications 2026-10-06, Q1).

## R10. Route and refusal

- **Decision**: `DELETE /readers/{readerId}` (UUID, as R1) → `204` with no body, `404` (`ReaderNotFoundException`) for an unknown or already deleted reader, `409` (new `ReaderStillActiveException`, `@ResponseStatus(CONFLICT)`, response `Conflict`) for an active reader.
- **Rationale**: it mirrors `DELETE /pickers/{pickerId}` (`204`, `409` `Conflict` when the picker still has buckets, `PickerHasBucketsException`). Requiring deactivation first (Clarifications Q4) means the device is already shut off before anything is cleaned up, so the delete itself can't cut a working line.
- **Alternatives considered**: `PATCH { deleted: true }`. Rejected: deleting can't be undone (no `deleted: false`), and `DELETE` is what the API uses for pickers, buckets and activities.

## R11. What the delete cleans up, and in which order

- **Decision**: `ReaderService.deleteReader`, in one transaction:
  1. Lock the reader with `findWithLockById` (as `updateReader` does, spec 012 research R9). Missing or `isDeleted()` → `404`; `active` → `409`.
  2. Remove the reader from every activity returned by `activityRepository.findAllByLine(readerId)` (`activity.getLines().removeIf(...)`, as `setReaderActivities` does), then flush.
  3. `lineActivityService.startNewDay(reader)` first, as `setReaderActivities` does: a state from a previous day is brought to today (with the associations already removed, its default is none, logged by the system at the due midnight). Then, if the reader still has a current activity, `lineActivityService.clear(reader, currentUser())`: one `line_activity_change` row to "no activity", authored by the Administrateur. Either way the line's activity history ends with an explicit entry to "no activity"; it is the Administrateur's only when the line still had an activity today. Checking the current activity before `startNewDay` would read a stale value.
  4. `registrationService.closeForReader(reader)`: deletes an open registration session and its reads. Deactivation already does this (`updateReader`), so this call normally finds nothing and no API scenario can reach it (spec User Story 5, scenario 3). It stays as a guard for sessions left by data from before that rule, tested only in `ReaderServiceTest` with mocks.
  5. Set `deletedAt = OffsetDateTime.now(clock)` and save. `ReaderService` gets the `Clock` bean, as `LineActivityService` and `RecordStatsService` have.
- **Rationale**: these are the existing primitives of specs 011 and 012, so the delete adds no new way to change a line's activity. Only one reader is locked, and the activities are not locked, which is the same pattern as `setReaderActivities`, so it can't deadlock with `ActivityService.updateActivity`/`deleteActivity`, which lock readers in id order.
- **Alternatives considered**: delegating to `activityService.setReaderActivities(readerId, {activityIds: [], confirmed: true})`. Rejected: that method refuses readers not in PRODUCTION mode (a registration reader can be deleted too), and it would give the line a default activity rather than none.

## R12. A deleted reader on the other routes

- **Decision**:
  - `GET /api/readers`: `readerRepository.findAllByDeletedAtIsNull()` instead of `findAll()`, for every role.
  - Management routes that name a reader by `id`, `PATCH /readers/{id}`, `POST /readers/{id}/token`, `PUT /readers/{id}/activities`, `DELETE /readers/{id}`, `POST /tags/registration-sessions` (`RegistrationService.start`, which today answers `400` because the reader is deactivated): a deleted reader → `404`, as if unknown.
  - `GET`/`PUT /lines/{uid}/current-activity` (logged-in user): deleted → `404`. The line no longer exists for the front.
  - Midnight reset: `findIdsWithStaleState` adds `r.deletedAt is null`, so a deleted line never gets a "new day" change logged.
  - Token routes (scan, registration reads, kiosk): no change. A deleted reader is always deactivated, because the delete requires `active = false` and `PATCH` (the only way back to active) answers `404` once it is deleted. `ReaderApiTokenAuthenticationFilter` already refuses a deactivated reader with `401`. A test pins this invariant.
- **Rationale**: "disappears from every list" (Q1) plus "no longer offered as a line" (Q3). `404` keeps the routes simple, since nothing can be done to a deleted reader.
- **Alternatives considered**: `410 Gone` for deleted readers. Rejected: no client handles it, and it would tell apart "deleted" from "never existed" for no use.

## R13. What stays as it is

- `existsByNameIgnoreCase` keeps counting deleted readers, so a deleted reader's `uid` stays taken (Q2) with no code change. The database unique constraint on `name` stays as well.
- `RecordService` (`findByName`, `GET /records/readers/{uid}`) and `RecordStatsService` (`findById`, `?readerId=`) keep finding deleted readers, and the stats totals keep their records (Q3). Conformity changes authored by a deleted reader's kiosk keep their `author_reader_id`.
- `PATCH /records/{id}/conformity` on a deleted reader's record by a logged-in user keeps working: the record is history, and correcting its conformity is still allowed.

## R14. Front

- **Decision**: `front/readers.html` shows a "Supprimer" button only on deactivated rows. It asks for confirmation ("Le lecteur X disparaîtra de toutes les listes. Ses lectures sont conservées. Son nom ne pourra pas être réutilisé."), calls `DELETE /readers/{id}` with `apiFetch`, then reloads the list. A `409` (reader reactivated meanwhile) shows the API message.
- **Rationale**: the button matches the backend rule, so the common path never hits `409`. The confirm text states the two consequences the Administrateur might not expect: no undo, and the name stays taken.
- **No other page changes**: `index.html`, `reader.html`, `activities.html` and `tags.html` build their lists from `GET /api/readers`, which no longer returns deleted readers. The dashboard's "all lines" totals come from `GET /api/records/stats` and keep the deleted reader's records.
