# Research: Scan d'un TAG par un lecteur (déclaration de conformité)

Phase 0 decisions for [plan.md](plan.md). Each entry gives the decision, the reason, and the alternatives set aside.

## R0. What is already delivered

- **Decision**: no work on reader authentication, reader mode routing, or the conformity override.
  - FR-001 (token check, `401`) is covered by `ReaderApiTokenAuthenticationFilter` and `ReaderScanSecurityTest` (missing, unknown, disabled, rotated token).
  - FR-003's `ENREGISTREMENT` branch was delivered by spec `003` (its research R2), and `ReaderScanSecurityTest.scan_fromEnregistrementReader_returns200WithoutRecord` covers it.
  - FR-007 (operator override and its history table) belongs to spec `005`. This feature does not touch `PATCH /records/{id}/conformity`.
- **Rationale**: the only new behavior left in the spec is FR-002's blank-UID rejection, FR-008's deduplication, and the SC-004 latency target.
- **Alternatives**: none.

## R1. Blank UID is rejected by the OpenAPI contract

- **Decision**: add `pattern: '.*\S.*'` to `ScanTagRequest.uid` in `api.yaml`. The generator already puts `@Valid` on the request body and `@NotNull` on required fields, so it adds `@Pattern` and Spring rejects the body with `MethodArgumentNotValidException` before `TagController.scanTag` runs. A missing `uid` or `isCompliant` already fails the same way.
- **Error body**: add an `ApiExceptionHandler` method for `MethodArgumentNotValidException` that answers `400` with a `ProblemDetail` whose `detail` lists `field: message` for each field error. Without it, Spring Boot's default error body leaves out the message (`server.error.include-message` is `never`), and FR-002 asks for one. It matches how `ConstraintViolationException` is already answered. Side effect: every `@Valid` request body in the API now gets this `ProblemDetail` for a `400` instead of Spring's default body. The status doesn't change, and existing tests only check the status.
- **Rationale**: the check applies to both reader modes and is written down in the contract the reader vendor reads. It needs no new code. `@Pattern` matches the whole value, so a UID made only of spaces fails, while one with a leading or trailing space passes and is trimmed by `TagService.sanitizeUid`, as today. Error dispatches are already allowed through the user chain (`SecurityConfig`, `DispatcherType.ERROR`), so the reader gets the `400` and not a `401`/`403` from the `/error` forward.
- **Behavior change for spec `003`**: an `ENREGISTREMENT` reader sending a blank UID used to get `200` with "read ignored" (`RegistrationService.recordRead`). It now gets `400`. A blank UID is a malformed request in either mode. The blank branch in `recordRead` and the `Assert.isTrue` in `registerScan` stay as defensive guards for direct service calls.
- **Alternatives**:
  - Turn on `server.error.include-message: always`. Rejected: it exposes exception messages on every error, including `500`s.
  - Check in `TagController` and throw `ResponseStatusException(BAD_REQUEST)`. Works, but it hides the rule from the contract.
  - Map `IllegalArgumentException` to `400` in `ApiExceptionHandler`. Rejected: it would turn any internal programming error thrown by `Assert` anywhere into a `400`.

## R2. Deduplication: look up the latest Record for (reader, tag)

- **Decision**: in `TagService.registerScan`, when the tag already exists, look up the most recent `Record` for this reader and this tag created after `now - window`:
  `RecordRepository.findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(reader, tag, cutoff)`.
  - Found: no new `Record`. The reader gets `200` with the existing Record's values: `uid`, `isCompliant` = its current `compliant`, `processedAt` = its `creationDate`, and `message` = `"Duplicate read ignored"`.
  - Found, and the scan says `isCompliant: false` while the Record is compliant: set the Record's `compliant` to `false` and save it, still with no new Record. The response then says `isCompliant: false`. A compliant repeat never changes a non-compliant Record (clarification 2026-09-24: the non-compliant verdict wins within the window).
  - Not found, or the tag was just created: a new `Record`, as today.
- **Rationale**: FR-008 and the clarification. The key includes the reader, so the same tag passing two different readers within the window gives two Records. Returning the first Record's `processedAt` makes the ignored read visible to tests and logs without changing the response shape (`message` is already an optional field, always `null` today for `PRODUCTION` readers). A new tag cannot have Records, so the lookup is skipped for it.
- **Non-compliant wins**: counts stay right (still one Record per pass), and a real non-compliant read is never lost. The reader only ever lowers the verdict, and only within 10 s of the Record it created. An operator's review (spec `005`) that lands inside those 10 s could be lowered by the reader. That window is too short for this to matter in practice. When spec `005` adds its conformity history table, this change must be logged there too, with the reader as its author.
- **Alternatives**:
  - First read wins (the verdict of a repeat is dropped). Rejected in clarification: a non-compliant read could be lost without anyone knowing.
  - A different verdict bypasses dedup and creates a Record. Rejected: a reader that flips its verdict would count the pass twice, which FR-008 exists to prevent.
  - Echo the request (`isCompliant` as sent, `processedAt` = now). Simpler, but the reader would be told a time for a Record that doesn't exist.
  - An in-memory cache of the last scan per (reader, tag). Faster, but lost on restart, wrong if the backend ever runs more than one instance, and a second source of truth next to the database (same reasoning as spec `003` research R3).
  - A unique constraint on a time bucket. Rejected: fixed buckets don't give a sliding window (two reads 1 s apart across a boundary would both count).

## R3. The window is a Duration property, and time comes from the `Clock` bean

- **Decision**: new property `app.scan.duplicate-window: 10s` in `application.yml`, injected into `TagService` as a `Duration` through `@Value`, like `app.registration.session-timeout` in `RegistrationService`. The cutoff is `OffsetDateTime.now(clock).minus(window)`, using the `Clock` bean from `ClockConfig`. `0s` turns deduplication off (a zero window never matches a past Record). `TagService` loses `@RequiredArgsConstructor` in favor of an explicit constructor, as `RegistrationService` does.
- **Rationale**: the spec says "10 s by default, configurable". Reusing the existing `Clock` lets `TagServiceTest` fix the time. `RecordEntity.creationDate` is set by `@CreationTimestamp` from the JVM clock, which `Clock.systemDefaultZone()` matches in production.
- **Alternatives**: a constant. Rejected by the spec.

## R4. Index on `record (reader_id, tag_id, creation_date)`

- **Decision**: declare `@Index(name = "idx_record_reader_tag_date", columnList = "reader_id, tag_id, creation_date")` on `RecordEntity`'s `@Table`.
- **Rationale**: SC-004 (p95 < 200 ms) now includes a lookup on a table that grows with every scan of the season. With this index the lookup reads a single index range, whatever the table size. With `ddl-auto: update`, Hibernate's schema migrator creates a missing named index on an existing table, on H2 and PostgreSQL. The name is explicit so it's recognised on the next boot and not created twice.
- **Alternatives**: rely on the foreign-key index on `reader_id` alone. Rejected: a busy reader would scan all of its own Records.

## R5. Concurrent identical scans are not locked

- **Decision**: no lock. Two requests for the same (reader, tag) that run at the same moment can both miss the lookup and both create a Record.
- **Rationale**: one reader sends its reads one after the other over a single connection, so identical requests do not overlap in practice. Locking the tag row (`PESSIMISTIC_WRITE`) on every scan would add a lock to the hot path for a case that doesn't occur. It doesn't cover the "new tag" case either, since there is no row to lock yet.
- **Alternatives**: a pessimistic lock on the tag, or a serializable transaction. Reconsider if readers ever send reads in parallel.

## R6. SC-004 is measured, not asserted in CI

- **Decision**: no timing assertion in the test suite. [quickstart.md](quickstart.md) gives a manual measurement: 3 `curl` loops run at the same time against the local app, one per reader token, each sending its scans back to back (SC-004's load: 1 to 3 readers). The index (R4) is the design-side guarantee.
- **Rationale**: timing assertions in JUnit fail at random on slow CI machines. The scan does a fixed number of indexed queries (tag by UID, latest Record, insert), and the tag lookup already uses the unique index on `tag.uid`.
- **Alternatives**: a Gatling/JMeter scenario. Out of proportion for a handful of readers.
