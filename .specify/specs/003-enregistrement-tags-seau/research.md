# Research: Enregistrement des TAGs sur un seau

Phase 0 decisions for [plan.md](plan.md). Each entry gives the decision, the reason, and the alternatives set aside.

## R0. Authentication of `POST /api/tags/buckets/{n}` is already delivered

- **Decision**: no security work in this feature. Spec `008` already restricts `/api/tags/**` to Administrateurs (`SecurityConfig.java:124`), and `AccessMatrixSecurityTest` checks the route. FR-005 and the "Aucune authentification" edge case of the spec describe a state that no longer exists.
- **Rationale**: the new routes of this feature (R4) sit under `/api/tags/`, so they inherit the Administrateur rule with no `SecurityConfig` change. `/api/tags/scan` stays on the `@Order(1)` reader chain, whose `securityMatcher` only matches that exact path.
- **Alternatives**: a separate `/api/registration/**` prefix. Rejected: it would need a new matrix rule, and spec `008` already reserves the `/api/tags/**` prefix for these routes (matrix row "Futures routes des lectures temporaires d'enregistrement").

## R1. Reader mode is a column on `reader`

- **Decision**: new enum `ReaderMode { PRODUCTION, ENREGISTREMENT }` and column `reader.mode varchar(20) not null default 'PRODUCTION'`, set through the existing `PATCH /api/readers/{id}` (new optional `mode` field in `UpdateReader`, next to `active`). `GET /api/readers` returns `mode` for every reader.
- **Rationale**: the `PATCH` route was shaped for this in spec `002` (its research R2). `@ColumnDefault("'PRODUCTION'")` lets `ddl-auto: update` add the NOT NULL column over existing readers, as `active` did. The SQL literal needs its inner quotes, unlike the boolean default of `active`.
- **Alternatives**: a boolean `registration`. Rejected: the spec names two modes, and an enum reads better in the API and the UI.

## R2. How a scan is routed

- **Decision**: `TagController.scanTag` checks the authenticated reader's `mode`. In `PRODUCTION` mode it calls `TagService.registerScan` as today. In `ENREGISTREMENT` mode it calls `RegistrationService.recordRead(reader, uid)`, which creates no tag and no `Record`. The branch sits in the controller because `RegistrationService` already depends on `TagService` (Save reuses `registerTagsForBucket`, R4); routing inside `TagService` would make the two services depend on each other. The response keeps the `ScanTagResponse` shape: `uid`, `isCompliant: true`, `processedAt` = now, and `message` = `"Registration read"`, or `"Registration read ignored: no session started"` when no session is open on that reader. The request's `isCompliant` is ignored.
- **Rationale**: FR-007 and clarification Q3. The reader device changes nothing, and never gets a non-compliant alert. The `Tag` row is only created when the bucket is saved (FR-003), so stray reads never leave tags behind.
- **Alternatives**: a separate scan endpoint for registration readers. Rejected by clarification Q (same endpoint, mode on server).

## R3. Temporary reads and sessions are stored in the database

- **Decision**: two new tables, `registration_session` and `registration_read` ([data-model.md](data-model.md)). A unique constraint on `registration_session.reader_id` enforces one session per reader (FR-010). A unique constraint on `(session_id, uid)` stores each tag once per session (FR-008).
- **Rationale**: the unique constraints settle concurrent "Start" clicks and concurrent reads of the same tag atomically (transaction handling in R3a). Sessions survive a restart. H2 in tests exercises the same code as prod.
- **Alternatives**: an in-memory `ConcurrentHashMap` in the service. Simpler, but reads vanish on restart, it breaks if the backend ever runs more than one instance, and it adds a second concurrency model to a codebase that relies on the database everywhere else.

## R3a. Transactions around the unique constraints

- **Decision**: `RegistrationService.start` and `recordRead` are not `@Transactional`. Each repository call runs in its own transaction, so a unique-constraint violation only rolls back the insert that caused it:
  - `recordRead`: a failed insert means another request stored the same tag first. It is ignored, and the session's `lastActivityAt` is then updated by a separate update query.
  - `start`: a failed insert means another Administrateur started first. A fresh `findByReader` loads the winner for the `ReaderBusy` body. If the winner is already gone, the insert is retried once.

  `get`, `cancel` and `save` are `@Transactional(noRollbackFor = RegistrationSessionNotFoundException.class)`, so the deletion of an expired session is committed even though the call answers `404`.
- **Rationale**: a Spring Data repository call that fails inside an outer transaction marks that whole transaction rollback-only. Catching the exception and carrying on then fails at commit with `UnexpectedRollbackException` (`500` to the reader). `ReaderService.createReader` gets away with it because it rethrows. By default, a `RuntimeException` rolls back everything the method did, including a delete done just before throwing `404`.
- **Alternatives**: a `REQUIRES_NEW` insert in a separate bean. It works, but it needs an extra bean to get through the Spring proxy, and on H2 the inner transaction can wait on row locks held by the outer one.

## R4. REST shape: a session resource under `/api/tags/registration-sessions`

- **Decision**: new OpenAPI tag `Registration`, which generates `RegistrationApiDelegate`, implemented by a new `RegistrationController`:
  - `POST /tags/registration-sessions` `{readerId}` → `201` session (Start)
  - `GET /tags/registration-sessions/{sessionId}` → `200` session with its reads, each flagged with its current bucket (page polling)
  - `DELETE /tags/registration-sessions/{sessionId}` → `204` (Cancel)
  - `POST /tags/registration-sessions/{sessionId}/save` `{bucketNumber, moveConfirmed}` → `200` `RegisterTagsResponse` (Save)
- **Rationale**: Save registers the session's reads and closes the session in one transaction, so FR-008 ("saving clears the reads") cannot half-fail. Save reuses `TagService.registerTagsForBucket`, so the add-only and move rules (FR-001, FR-009) live in one place and also apply to `POST /tags/buckets/{n}`.
- **Alternatives**: the page calls `POST /tags/buckets/{n}` and then `DELETE` on the session. Rejected: two calls, and a failure between them leaves reads behind.

## R5. Inactivity timeout: lazy expiry, 5 minutes, polling counts as activity

- **Decision**: `registration_session.last_activity_at` is updated by every read received and every page request on the session (`GET`, save). A session whose `last_activity_at` is older than `app.registration.session-timeout` (default `5m`) counts as expired. It is deleted, along with its reads, the next time anything touches it: a "Start" on that reader, a scan from that reader, or a `GET`/save/`DELETE` on it (which then gets `404`). No scheduler. To limit writes, a `GET` only updates `last_activity_at` when it is more than 30 s old.
- **Rationale**: the open page polls every `CONFIG.POLLING_INTERVAL`, so a session stays alive exactly as long as its page is open. The timeout only has to detect an abandoned page (closed tab, crashed browser). Five minutes keeps a reader from staying locked for long, and it is long enough to ride out a short network drop. Lazy expiry is enough because only a new "Start", a scan, or the page itself can observe a session.
- **Alternatives**: a `@Scheduled` cleanup job. It adds moving parts for no visible gain. A 30-minute timeout, matching the HTTP session. Rejected: the reader would stay blocked for half an hour after an abandoned page.
- **Testability**: the service takes a `java.time.Clock`. No `Clock` bean exists yet, so add `Clock.systemDefaultZone()` in a configuration class. Tests pass a fixed clock.

## R6. Who can cancel a session

- **Decision**: only the Administrateur who started a session can `GET`, save or `DELETE` it. Anyone else gets `403`. A reader held by someone else is released by that person, or by the timeout.
- **Rationale**: FR-010 refuses a second "Start" to protect the first person's work. Letting others cancel it would undo that protection.
- **Alternatives**: any Administrateur can cancel. Rejected for the reason above. Revisit if stations are often left locked in practice.

## R7. Move confirmation and the `409` body

- **Decision**: `RegisterTagsRequest` and `SaveRegistrationSession` get `moveConfirmed` (boolean, default `false`). When at least one tag belongs to **another** bucket and `moveConfirmed` is not `true`, nothing is saved and the answer is `409` with a `TagsInOtherBuckets` body: `message` plus `tags: [{uid, bucketNumber}]`. `TagsInOtherBucketsException` carries the list, and `ApiExceptionHandler` renders it. The session `GET` already flags each read with its current bucket, so the page can warn before Save; the `409` is the server-side safety net.
- **Rationale**: FR-009. The codebase's `@ResponseStatus` exceptions carry no body, and the page needs the list, hence a dedicated handler.
- **Alternatives**: always move and return the moved tags in the response. Rejected: it contradicts the clarification (confirm first).

## R8. Add-only registration, counts, and empty input

- **Decision**: `registerTagsForBucket` no longer detaches the bucket's other tags (FR-001). `registeredCount` = unique, non-blank UIDs in the request; the new `totalCount` = tags linked to the bucket after the save (clarification Q5). A request with no usable UID (all blank) is now `400` instead of silently creating an empty bucket; saving a session with no read is `400` too.
- **Rationale**: the spec's clarifications. The `400` stops an empty save from creating a bucket for nothing. `minItems: 1` already shows that intent, but a list of blanks slipped past it.
- **Alternatives**: keep `200` with `registeredCount: 0`. Rejected: it hides an operator mistake.

## R9. Effects on other features

- **Decision**:
  - Switching a reader to `PRODUCTION`, or disabling it, closes its open session (reads deleted).
  - `front/index.html` (dashboard, spec `007`) skips `ENREGISTREMENT` readers when it builds production lines, as it already skips disabled readers.
  - `front/readers.html` shows the mode and switches it.
  - Removing a tag from a bucket (the "separate action" of FR-001) is **not** built here. It belongs to bucket management, spec `006` (see plan follow-ups).
- **Rationale**: a registration reader has no production records, so its dashboard line would always be empty. A session on a reader that left registration mode could never receive reads again.
