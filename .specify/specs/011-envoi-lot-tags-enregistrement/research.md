# Research: Envoi groupé des tags par le lecteur d'enregistrement

Decisions taken while planning spec `011`. Code references are to the `dev` branch at `679ba18`.

## R1 — Route and OpenAPI tag

- **Decision**: `POST /api/tags/registration-reads`, operation `recordRegistrationReads`, OpenAPI tag
  `Registration`, implemented in `RegistrationController` (the `RegistrationApiDelegate` implementation).
- **Rationale**: the route is registration-only (FR-003), so it sits with `/tags/registration-sessions` rather than
  under `/tags/scan`, whose name suggests a production scan. Staying under `/api/tags` keeps the reader targets on one
  prefix (`deploy/INSTALL.md` §118 already points readers at `/api/tags/scan`).
- **Alternatives considered**: `/tags/scan/batch` (reads as a production batch scan, and a production reader would
  expect it to work); `/tags/registration-sessions/current/reads` (the reader does not know the session id and has
  none to name).

## R2 — Security chain

- **Decision**: the reader chain (`SecurityConfig.readerSecurityFilterChain`, `@Order(1)`) matches both
  `/api/tags/scan` and `/api/tags/registration-reads` (an `OrRequestMatcher`), with `OPTIONS` permitted on both.
  Nothing else changes: `ReaderApiTokenAuthenticationFilter` still answers `401` for a missing, unknown or disabled
  reader's token.
- **Rationale**: FR-002. Because the chain matches on the path alone, a request carrying a user session but no token
  also lands here and gets `401` (User Story 2, scenario 3), exactly as `/api/tags/scan` does today. Without this
  change the request would fall into the kiosk chain (`@Order(2)`, `denyAll` → `403`) or the user chain
  (`/api/tags/**` → Administrateur), both wrong.
- **Alternatives considered**: a dedicated fourth chain (duplicates the reader chain for no gain).

## R3 — Production readers

- **Decision**: `RegistrationService.recordReads` answers `403` (`ResponseStatusException`, turned into a
  `ProblemDetail` by `ApiExceptionHandler`) with the detail "This route is reserved for readers in ENREGISTREMENT
  mode", before touching any data.
- **Rationale**: FR-003 / SC-003. The reader is authenticated but not allowed; `403` with a readable detail shows the
  misconfiguration during installation.
- **Alternatives considered**: `409` (not a state conflict the caller can resolve); silently feeding the production
  flow (the spec forbids creating any Record).

## R4 — Request validation

- **Decision**: body `{ "uids": [string] }`. The contract declares `minItems: 1` and `maxItems: 1000` on the raw
  array. The service then trims each element, drops blank ones (FR-009), de-duplicates in order, and answers `400`
  without writing anything when: no UID is left; more than 100 distinct UIDs remain (FR-008); or a trimmed UID is
  longer than 50 characters.
- **Rationale**: the 100 limit is on distinct UIDs (spec Assumptions), so it cannot be a schema `maxItems`. The raw
  cap of 1,000 bounds the payload (a reader may repeat a tag, but not 10× the bucket limit). The 50-character check
  matches the `registration_read.uid` and `tag.uid` columns: an over-long UID would otherwise fail the insert with a
  `500`. The spec's FR-008 was extended with these two bounds during planning.
- **Alternatives considered**: `maxItems: 100` on the raw array (would refuse a list with repeats that the spec
  accepts); an item `pattern` like `ScanTagRequest.uid` (would reject the whole call for a blank element, against
  FR-009).

## R5 — All or nothing, concurrency

- **Decision**: `recordReads` runs one transaction (through a `TransactionTemplate`, so the method itself stays
  non-transactional like `recordRead`, research R3a of spec `003`) that:
  1. loads the reader's session with a pessimistic write lock (`SELECT … FOR UPDATE`, new repository method
     `findLockedByReader`);
  2. if none: answers "no session" (FR-006). If expired: deletes it (existing `deleteSession`) and answers "no
     session";
  3. queries which of the UIDs the session already has (`findUidsBySessionAndUidIn`), inserts the others with
     `saveAll` + `flush`, and sets `lastActivityAt` once (FR-005).

  If the flush hits the `(session_id, uid)` unique constraint, the transaction is rolled back and run once more; the
  second run sees the committed row and skips it.

  **Lock wait (analysis K1)**: the wait for the session lock is bounded. `findLockedByReader` carries the hint
  `jakarta.persistence.lock.timeout = 5000` (ms), which Hibernate applies on PostgreSQL; H2 ignores it and uses its
  own lock timeout (1 s by default), raised to 10 s in `application-test.yml` (`;LOCK_TIMEOUT=10000` on the test
  datasource URL) so the concurrency test does not depend on CI speed. A timed-out wait surfaces as Spring's
  `PessimisticLockingFailureException` (whose subclass `CannotAcquireLockException` is what H2 and PostgreSQL
  produce) and is handled like the constraint violation: rolled back, run once more. A second failure of either
  kind answers `409` "Concurrent reads, send the batch again" with nothing kept — never a `500`.
- **Rationale**: FR-010 (the page never sees half a call) and the edge case on simultaneous calls. The lock
  serializes two batches from the same reader, which is the realistic concurrency. A tag-by-tag read
  (`recordRead`, not locked) racing a batch is the only way to hit the constraint, hence one retry. A batch holds
  the lock for one `IN` query and at most 100 inserts, far below 5 s, so a lock timeout means something abnormal
  (a stuck transaction), where `409` and a resend beat an unbounded wait or a `500`.
  `PESSIMISTIC_WRITE` works on H2 and PostgreSQL.
- **Interplay with save/cancel**: `save` and `cancel` delete the session row, which waits for the batch's lock. If the
  batch commits first, `save` has already read its reads (read committed), so the batch's tags are not put on the
  bucket and are deleted with the session: the edge case "Session annulée ou enregistrée pendant un appel" holds.
  If `save`/`cancel` commits first, the batch finds no session and answers "no session".
- **Alternatives considered**: calling `recordRead` in a loop (not atomic, one touch per tag); a table-level lock (too
  broad); catching the constraint violation per row inside one transaction (the transaction is already marked
  rollback-only, see the `RegistrationService` class comment).

## R6 — Read order

- **Decision**: reads of one call get `firstReadAt = now + i µs`, `i` being the UID's position in the de-duplicated
  list.
- **Rationale**: the page lists reads by `firstReadAt` ascending (`findAllBySessionOrderByFirstReadAtAsc`); equal
  timestamps would come back in an arbitrary order. A microsecond keeps the reader's order and fits the timestamp
  precision of both databases.
- **Alternatives considered**: a sequence column (schema change for a cosmetic need).

## R7 — Response

- **Decision**: `200` with `RegistrationReadsResponse { sessionOpen, receivedCount, addedCount, processedAt,
  message }`. `receivedCount` = distinct non-blank UIDs, `addedCount` = new reads in the session (0 when no session).
  `message` reuses the registration wording: "Registration reads kept" or "Registration reads ignored: no session
  started".
- **Rationale**: FR-006 / FR-007; a success status even without a session, as for `/tags/scan` in registration mode,
  so the reader does not retry in a loop.
- **Alternatives considered**: echoing each UID with a per-tag status (the reader does nothing with it; the page is
  where the Administrateur checks tags).

## R8 — Existing flows

- **Decision**: `POST /api/tags/scan`, `recordRead`, `save` and the page `front/tags.html` are unchanged. No schema
  change: the two repository methods only query existing tables.
- **Rationale**: FR-011, FR-012, SC-005; the page already polls the session's reads whatever their origin.

## R9 — Tests

- **Decision**:
  - `RegistrationReadsApiTest` (new, `@SpringBootTest` + MockMvc, `test` profile): batch into an open session,
    repeats and existing reads, no session, expired session, blank elements, 100 vs 101 distinct UIDs (SC-006),
    1,001 raw elements, UID of 51 characters, same batch twice (SC-004), saving the session afterwards (FR-012),
    concurrent batches from two threads (FR-010).
  - `RegistrationServiceTest`: production reader → `403` with no repository write; retry after a constraint
    violation.
  - `ReaderScanSecurityTest`: the new route answers `401` without token, with an unknown token, with a disabled
    reader's token, and with a logged-in Administrateur's session and CSRF token but no reader token.
  - Existing `TagScanApiTest`, `RegistrationApiTest` untouched (SC-005).
- **Rationale**: locking and rollback need a real database, so the atomicity cases are integration tests.
