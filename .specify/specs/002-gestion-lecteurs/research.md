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
