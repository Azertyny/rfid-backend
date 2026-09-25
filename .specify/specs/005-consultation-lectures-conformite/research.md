# Research: Consultation des lectures et bascule de conformité

**Feature**: `005-consultation-lectures-conformite` | **Date**: 2026-09-25 | **Plan**: [plan.md](plan.md)

Each decision below resolves an item of the spec's 2026-09-24 and 2026-09-25 clarifications. There was no
`NEEDS CLARIFICATION` left in the Technical Context. R11 and R12 were added after the SC-003 clarification
(2026-09-25: p95 < 200 ms for the last-10 list, 1 to 3 screens polling every 500 ms, a season of records).

## R0 — What already exists

- **Decision**: the last-10 list (FR-001, FR-002) and the explicit-value PATCH (FR-003 minus history) stay as they
  are. FR-004 and FR-005 are already delivered by spec `008`: `/api/records/**` requires the `ADMINISTRATEUR` or
  `OPERATEUR` role (`SecurityConfig.java:126`) and `AccessMatrixSecurityTest` covers both routes.
- **Rationale**: this feature only adds the history (FR-003, FR-007), idempotency (FR-003) and the lock against
  duplicate scans (FR-006).
- **Gap found**: `RecordService`/`RecordController` still have no dedicated test (spec SC-002).

## R1 — Where the history lives

- **Decision**: a new entity `RecordConformityChangeEntity`, table `record_conformity_change`, with a
  unidirectional `@ManyToOne` to `RecordEntity` and one to `UserEntity` (the author). `RecordEntity` gets no
  collection back-reference. Hibernate creates the table at boot (`ddl-auto: update`).
- **Rationale**: the 2026-09-24 clarification chose a separate table that keeps every change. A unidirectional
  relation keeps the scan and list paths from ever loading history rows.
- **Alternatives considered**: columns on `record` (rejected in clarification: keeps only the latest change);
  reusing `Record.comment` (free text, not queryable).

## R2 — How the author is stored

- **Decision**: foreign key `author_id` → `app_user.id`, resolved from `Authentication.getName()` through
  `UserRepository.findByUsername` inside the PATCH transaction. The read route returns the author's `username`.
- **Rationale**: spec `008` guarantees users are never deleted (only disabled), and `UpdateUser` cannot change a
  username, so a foreign key is always resolvable and the displayed name never drifts.
- **Alternatives considered**: storing the username as a string (no referential integrity, and duplicated data).

## R3 — Idempotency and two Opérateurs clicking together

- **Decision**: `updateRecordConformity` loads the record with a pessimistic write lock
  (`RecordRepository.findWithLockById`, `@Lock(PESSIMISTIC_WRITE)`, i.e. `SELECT … FOR UPDATE`). If the stored
  value already equals the requested one, it returns without writing (`204`, no history row). Otherwise it sets
  `compliant` and inserts one `RecordConformityChangeEntity` in the same transaction.
- **Rationale**: both screens send the opposite of the value they last displayed. Without a lock, two concurrent
  requests would both read the old value and both write a history row. With the lock, the second request waits,
  then sees the new value and becomes a no-op, as the 2026-09-25 clarification requires. The lock is on one row
  and held for a few milliseconds.
- **Alternatives considered**: `@Version` optimistic locking (existing rows would get a `null` version under
  `ddl-auto: update`, and the second Opérateur would get an error instead of a harmless no-op); no lock (duplicate
  history rows under a race).

## R4 — Duplicate scans no longer change a record an Opérateur changed (FR-006)

- **Decision**: in `TagService.ignoreDuplicate`, before lowering a record to non-compliant, check
  `RecordConformityChangeRepository.existsByRecord(record)`. If a change exists, do not modify the record. The
  duplicate lookup `findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc` gets
  `@Lock(PESSIMISTIC_WRITE)` so the record is read under the same row lock the PATCH takes.
- **Rationale**: without a common lock, a scan could read "no change yet", the Opérateur's PATCH could commit, and
  the scan could then lower the record anyway. Locking at the lookup means the record is loaded fresh under the lock,
  so both the check and the value returned to the reader are current. The lock only affects a duplicate scan of
  that very record, so the SC-004 latency of spec `004` is not affected outside a real conflict.
- **Alternatives considered**: a `manually_changed` flag on `record` (duplicates what the history already says,
  and adding a non-null column to an existing table under `ddl-auto: update` needs a default); locking by id
  after the lookup (the entity would already be in the persistence context, so its state could be stale).
- **Risk to verify during implementation**: H2 must accept `ORDER BY … LIMIT 1 FOR UPDATE` on this single-table
  query (PostgreSQL does). If it does not, fall back to a `@Lock` lookup by id before the record is first loaded.
- **Supersedes** the follow-up in spec `004`'s plan that a duplicate-scan lowering "must be logged … with the
  reader as author": FR-006 removes that case instead.

## R5 — Reading the history (FR-007)

- **Decision**: new route `GET /api/records/{recordId}/conformity-history`, `operationId`
  `listRecordConformityChanges`, tag `Record`, response `ConformityChangesList { changes: ConformityChange[] }`,
  ordered by `changedAt` ascending. Unknown record → `404` (`RecordNotFoundException`). Repository method
  `findAllByRecordOrderByChangedAtAsc` with an `@EntityGraph` on `author` to avoid one query per row.
- **Rationale**: matches the `RecordsList` / `UsersList` wrapper convention in `api.yaml`. Oldest first reads
  as a story: the first entry's `previousIsCompliant` is the reader's original verdict.
- **Ordering ties**: the row lock of R3 serializes changes to one record, so `changedAt` values are strictly
  increasing in practice. No tie-breaker column is added.

## R6 — Access rule

- **Decision**: in `SecurityConfig`, add
  `requestMatchers(path(HttpMethod.GET, "/api/records/*/conformity-history")).hasRole(ADMINISTRATEUR)` just
  before the `/api/records/**` rule. Add the route as `ADMIN_ONLY` to `AccessMatrixSecurityTest` and a row to the
  spec `008` matrix.
- **Rationale**: the chain uses first-match rules, most specific first (comment at `SecurityConfig.java:117`).

## R7 — Timestamp of a change

- **Decision**: `changedAt` uses `@CreationTimestamp`, like `creationDate` on every other entity.
- **Alternatives considered**: injecting a `Clock` into `RecordService` as `TagService` does. Not needed: no rule
  here depends on elapsed time, and the tests check order and presence, not exact times.

## R8 — Tests

- **Decision**:
  - `RecordServiceTest` (new, Mockito): a change writes the record and one history row with the right author and
    values; the same value writes nothing; an unknown record → `RecordNotFoundException` for both PATCH and history;
    history is mapped in order.
  - `TagServiceTest` (extended): a non-compliant duplicate of a record that has a change leaves it compliant.
  - `RecordApiTest` (new, `@SpringBootTest` + MockMvc, `test` profile): full flow with real users (PATCH twice →
    one row, PATCH back → two rows, author username, empty list, `404`, Opérateur → `403`), plus last-10 list
    limit and order (spec SC-001).
  - `AccessMatrixSecurityTest` (extended): the new route.
- **Rationale**: covers every acceptance scenario of the spec, including the ones marked "no test" in SC-001/SC-002.
- **Not tested automatically**: the real two-transaction race of R3/R4. It would be timing-dependent; the lock
  itself is covered by the repository annotation, and the quickstart gives a manual check.

## R9 — Front end

- **Decision**: no change to `front/reader.html` or any other page.
- **Rationale**: the 2026-09-25 clarification (option B) keeps the history off the production screen. The PATCH
  contract is unchanged, so `toggleCompliance` keeps working.

## R10 — Contract cleanup on the two existing record routes

- **Decision**: add the `401` and `403` responses (already enforced since spec `008`) and a `400` on the PATCH to
  the two existing record routes in `api.yaml`. Documentation only: the generated delegate signatures do not change.

## R11 — Index for "the 10 newest records of a reader" (SC-003)

- **Decision**: add a second index on `record`, `idx_record_reader_date` on `(reader_id, creation_date)`, declared in
  `RecordEntity`'s `@Table(indexes = …)` next to spec `004`'s `idx_record_reader_tag_date`.
- **Rationale**: `findTop10ByReader_NameOrderByCreationDateDesc` filters on the reader and sorts by date. The existing
  index starts with `(reader_id, tag_id, …)`: it narrows to the reader, but the rows then come in tag order, so the
  database must read and sort every record of that reader to find the newest 10. Over a season that is every read of
  the line, twice a second per screen. With `(reader_id, creation_date)` the database walks the index backwards from
  the newest entry and stops after 10 rows, whatever the table size. H2 and PostgreSQL both scan a B-tree index
  backwards, so the index needs no `DESC`. `reader.name` is already unique (`ReaderEntity`), so the join to `reader`
  is one indexed lookup.
- **Alternatives considered**: reordering 004's index to `(reader_id, creation_date, tag_id)` (would slow down 004's
  duplicate lookup, which filters on the tag); keeping only 004's index and measuring first (the sort grows with the
  season, so a test on a small table would pass and production would not).
- **Cost**: one more index to maintain on each scan insert. Negligible at a few scans per second.

## R12 — Loading the tags of the 10 records in one query (SC-003)

- **Decision**: annotate `findTop10ByReader_NameOrderByCreationDateDesc` with `@EntityGraph(attributePaths = "tag")`.
- **Rationale**: `RecordService.toRecordSummary` reads `record.getTag().getUid()`. `tag` is `LAZY`, so today each call
  runs 1 query for the list plus up to 10 for the tags. The entity graph fetches them with a join in the same query.
  `picker` needs no fetch: only `getPicker().getId()` is read, which Hibernate answers from the proxy without a query.
- **Alternatives considered**: a DTO projection query (more code, and the API mapping already exists); `EAGER` on
  `RecordEntity.tag` (would also load tags on the scan path, which doesn't need them).

## R13 — Measuring SC-003

- **Decision**: a manual run in [quickstart.md](quickstart.md): fill `record` with a season's worth of rows for one
  reader, then 3 parallel loops calling the list every 500 ms, and read the 95th percentile. Same approach and
  threshold as spec `004`'s SC-004 run.
- **Season volume assumed**: 200,000 records for the measured reader (order of magnitude: a few thousand reads a
  day on one line over a season of a few months). Change it in the quickstart if the real figure is known.
- **Not automated**: a timing test would be flaky on CI machines. The automated tests check the result (limit, order),
  and a query-count assertion is not added either: the entity graph is visible in the SQL log during the manual run.
