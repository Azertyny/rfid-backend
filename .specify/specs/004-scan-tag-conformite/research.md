# Research: Scan d'un TAG par un lecteur (déclaration de conformité)

Phase 0 decisions for [plan.md](plan.md). Each entry gives the decision, the reason, and the alternatives set aside.

R0–R6 are the 2026-09-24 plan, delivered. R7–R13 are the 2026-10-11 amendment; where they replace an earlier entry, that entry says so.

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

> **Replaced by R7 (2026-10-11)**: the window now starts from the last read, and the Record is found through the tag (R8), not by this lookup. The response and the non-compliant-wins rule are unchanged.

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

> **Replaced by R10 (2026-10-11)**: the duplicate lookup no longer uses this index. Its declaration is removed; existing databases keep it (harmless).

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

## R7. Sliding window: the Record stores its last read

- **Decision**: new nullable column `record.last_read_at`. It is set when the Record is created, and set again to now on every read that the Record absorbs: a repeat of its own tag, or a read of a second tag of the same crate (R10, and that tag's repeats). A read by reader R of a tag whose counting Record L (R8) has `L.reader = R` and `L.lastReadAt > now − app.scan.duplicate-window` is the same pass: no Record, `L.lastReadAt = now`, the non-compliant-wins rule of R2 applies to L, and the response is unchanged (`"Duplicate read ignored"`).
- **Rationale**: FR-008 as clarified on 2026-10-11. Measured from the Record's creation, a crate left for more than 10 s in front of the reader got a second Record, which is the reported bug. Under FR-009 it would now get a KO Record instead, which is worse. Storing the last read on the Record that absorbed it needs no new table, and the Record is already loaded and locked (spec `005`, FR-006) for the non-compliant-wins rule.
- **Cost**: one update of the Record per repeated read. Repeated reads come from one reader one after the other, so the row lock never waits on another reader in practice.
- **Old rows**: `last_read_at` is `null` on Records from before the deployment; `null` never matches a window. They're all older than 10 s anyway.
- **Alternatives**:
  - The last read on the tag (`tag.last_read_at`, `tag.last_read_reader_id`). Works for duplicates, but the crate rule (R10) then needs the bucket on the Record and a join back to the tag, and the second tag of a crate would have no Record to lower. Rejected.
  - An in-memory map (reader, tag) → last read. Lost on restart, a second source of truth (same reasoning as R2).

## R8. The tag points at the Record that counted it

- **Decision**: new nullable column `tag.counted_record_id` (FK → `record`). A scan that creates a Record, or joins a crate's Record (R10), sets `tag.bucket = null` and `tag.countedRecord` = that Record. `TagService.registerTagsForBucket`, the single path through which a tag enters a bucket (direct registration and the end of a registration session, specs `003` / `011`), sets `countedRecord = null` on each tag it links.
- **Rationale**: one pointer answers the three questions of a scan: was this tag counted since it was last registered (`countedRecord != null`), by which reader and how long ago it was last read (R7), and on which day (R9). It's read through the tag already loaded by UID, so no new lookup and no index.
- **Picker**: the Record keeps the picker of the bucket at scan time (FR-003, unchanged). Unlinking the tag afterwards doesn't touch it.
- **Alternatives**:
  - A state column on the tag (`IN_BUCKET` / `COUNTED`) plus a date. It says less than the pointer and the clarifications of 2026-09-24 dropped a tag state machine. Rejected.
  - Find "the tag's latest Record" by query on `record (tag_id, creation_date)`. It can't tell a tag counted since its last registration from one counted before it, unless registration also leaves a mark. The pointer is that mark.

## R9. Rescan: same station day → KO, earlier day → ordinary

- **Decision**: a read of a tag whose counting Record L isn't the same pass (R7):
  - `L.creationDate >= LineActivityService.startOfToday()` → new Record, `compliant = false`, `picker = null`, `bucket = null`, the line's current activity as for any Record (spec `012`). The response says `isCompliant: false` and `message: "Tag already scanned today"`. L is unchanged.
  - Otherwise → an ordinary Record (FR-003/FR-004): the reader's verdict, no picker since the tag has no bucket.
  - Either way the new Record becomes `tag.countedRecord`, so the same-day rule restarts from it, and its own repeats are absorbed (R7) rather than giving one KO per hardware read.
- **Rationale**: clarifications 2026-10-11 (rescans the same day are KO with no picker; on a later day an ordinary Record; registering again resets). `startOfToday()` already turns `APP_STATION_TIME_ZONE` into the start of today for the activity reset (spec `012`), and `TagService` already depends on `LineActivityService`.
- **Any reader**: the rule doesn't look at L's reader. A tag counted on line 1 and read on line 2 the same day is KO, unless it's the second tag of a crate (R10 runs first and only for a tag still in a bucket, which a counted tag is not).
- **Alternatives**: compare calendar dates in the JVM zone. Rejected: the station day is defined by `APP_STATION_TIME_ZONE`.

## R10. Two tags, one crate: same reader, same bucket, 2 s from the crate's first read

- **Decision**: new nullable column `record.bucket_id` (FK → `bucket`): the bucket the counted tag left, set only on an ordinary Record whose tag was in a bucket (`null` on KO rescans and tags without a bucket). A read by reader R of a tag T still in bucket B looks for a Record C with `C.reader = R`, `C.bucket = B`, `C.tag ≠ T` and `C.creationDate > now − app.scan.crate-window` (default `2s`), newest first, row-locked. If one exists, T joins C: no Record, `T.bucket = null`, `T.countedRecord = C`, `C.lastReadAt = now`, the non-compliant-wins rule applies to C. The response carries C's values and `message: "Second tag of the crate ignored"`.
- **Index**: `idx_record_reader_bucket_date` on `record (reader_id, bucket_id, creation_date)` keeps the lookup to one index range. It runs only for tags still in a bucket, i.e. at most once per counted tag.
- **Rationale**: clarification 2026-10-11 (same bucket, same reader, less than 2 s apart). The two tags of one crate sit at the same place and are first read almost together; measuring from C's first read (its creation) compares those two first reads. Once T has joined C, T's own repeats are absorbed by R7 since `T.countedRecord = C` and `C.reader = R`.
- **Risk accepted**: two crates from the same bucket whose first reads are less than 2 s apart count as one; a second tag first read more than 2 s after the first one counts as another crate. The window is configurable.
- **Alternatives**:
  - Measure 2 s from C's last read (`lastReadAt`). Rejected: a picker brings several crates, and while crate N is still being read in front of the reader, crate N+1 of the same bucket would be merged into it, losing a crate.
  - Any bucket. Rejected in clarification: two pickers' crates side by side would merge.

## R11. Schema: nullable columns only

- **Decision**: `record.last_read_at` (timestamp with time zone), `record.bucket_id` and `tag.counted_record_id` are nullable. `ddl-auto: update` adds them to the existing tables, with `null` on existing rows, on H2 and PostgreSQL. The new named index is created the same way (as R4 did).
- **Rationale**: a `not null` column added to a table that has rows fails on PostgreSQL under `ddl-auto`, and no rule needs the value on old rows (R7, R12).
- **Foreign-key cycle**: `record.tag_id → tag` and now `tag.counted_record_id → record`. Inserts are fine (tag first, then Record, then the tag's update in the same transaction). Deleting a Record still pointed at by a tag would fail, and only `OffListTagPurge` deletes Records: it gets a first step that sets `counted_record_id = null` on tags pointing at the Records it's about to delete. It has already run on prod and finds nothing on a new database, but its test exercises it.

## R12. No data upgrade at deployment

- **Decision**: no `data_upgrade` entry. Existing tags keep their bucket and get `counted_record_id = null`.
- **Rationale**: nothing in the old data says which tags left their bucket physically: before this amendment a scan left the tag in its bucket. Guessing from Records ("scanned today, so take it out") would empty buckets that were just refilled. The effect is bounded: each tag already scanned before the deployment counts once more, then follows the new rules.
- **Alternatives**: unlink every tag with a Record from today. Left as a follow-up for the user (plan).

## R13. Rule order and response values

- **Decision**: after the off-list check (spec `010`) and the tag lookup or creation, `registerScan` applies, first match wins:
  1. **Same pass** (R7): `countedRecord` by this reader, last read within `duplicate-window`.
  2. **Second tag of a crate** (R10): the tag is in a bucket and a matching Record exists.
  3. **Rescan today** (R9): `countedRecord` created today → KO Record.
  4. **Count**: a new Record, `bucket` = the tag's bucket, the tag leaves it.
- **Responses**: the shape doesn't change. For 1 and 2: the absorbing Record's `compliant` (after the non-compliant-wins rule) and `creationDate`. For 3 and 4: the new Record's values. `message` is `"Duplicate read ignored"`, `"Second tag of the crate ignored"`, `"Tag already scanned today"` or absent.
- **Rationale**: 1 before 3, otherwise a crate still in front of the reader would turn into KO Records. 2 before 4, otherwise the second tag would be counted. 1 and 3 need `countedRecord`, which a tag in a bucket doesn't have, so 2 and 3 never compete.
- **Concurrency**: unchanged from R5. Each reader sends its reads one after the other; the absorbing Record is row-locked.
