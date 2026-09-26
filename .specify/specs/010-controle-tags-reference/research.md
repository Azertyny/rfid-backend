# Research: Contrôle des tags par rapport à la liste de référence

Phase 0 decisions for [plan.md](plan.md). Each entry gives the decision, the reason, and the alternatives set aside.

R1–R3 describe the reference list as delivered (2026-09-26) and stay valid. R4–R6 of the first delivery (off-list
derived and shown, 409 confirmation, off-list tags page) are **replaced** by R4–R9 below, following the revision of
2026-09-26: tags not in the list are dropped without being shown, and those already stored are deleted once. R10
replaces R7 (tests).

## R1. Where the reference list lives

- **Decision**: ship the list inside the application as a classpath resource,
  `src/main/resources/tags/rfid_tag_list.csv`, moved from `doc/rfid_tag_list.csv` (still untracked, so one copy only).
  Its location is the property `app.tags.reference-list` (default `classpath:tags/rfid_tag_list.csv`), read as a Spring
  `Resource`. A new component `ReferenceTagList` loads it once at startup into an immutable `Set<String>` and answers
  `contains(uid)`.
- **Rationale**: clarification Q1 (fixed list, changed by a new version). The list goes into the jar, so the
  `rfid-backend` image carries it without any change to `deploy/`. 5,008 UIDs of 24 characters take a few hundred KB,
  and a set lookup is constant time, so a scan pays nothing measurable (SC-004).
- **Alternatives**:
  - A `reference_tag` table filled at boot from the file. Rejected: a second copy to keep in sync with the file, a
    query per check, and no gain while the list cannot be edited in the app. It becomes the right choice if an import
    screen is ever wanted (Q1 option B).
  - Read the file from disk at a configurable path (volume on the VPS). Rejected: a deployment step and a way for
    prod to run a list that differs from the reviewed one. The property still accepts `file:` for a one-off test.
  - Keep the file in `doc/` and read it from there. Rejected: `doc/` is not packaged.

## R2. Startup checks on the list

- **Decision**: `ReferenceTagList` fails startup (exception in its constructor, so the context doesn't start) when the
  resource is missing, holds no UID, or has a non-blank line that is not 24 hexadecimal characters after trim. It
  strips a UTF-8 BOM, accepts `\n` or `\r\n`, skips blank lines, stores UIDs upper-cased, and logs the number of UIDs
  loaded.
- **Rationale**: spec edge case "liste absente, vide ou mal formée". Since the revision a broken list would drop every
  scan and every registration read, and the one-off purge (R7) would delete every tag: failing the boot is even more
  necessary than before. It is caught by the deployment health check (`deploy/vps/deploy.sh` waits for
  `/actuator/health`, then fails and names the previous tag to redeploy), and before that by CI, since
  `ReferenceTagListTest` loads the shipped file. The 24-hex rule matches every line of the delivered file.
- **Alternatives**: start anyway and disable the check. Rejected: a silent failure mode.

## R3. Comparing a UID with the list: last 12 characters

- **Decision** (revised 2026-09-26 after production feedback, spec clarifications): the list is held as the last 12
  characters of each line, upper-cased, and `contains(uid)` compares the last 12 characters of
  `uid.trim().toUpperCase(Locale.ROOT)`. A UID shorter than 12 characters is never in the list. Loading refuses a list
  where two lines end with the same 12 characters. The stored `Tag.uid` is not changed: it stays trimmed only, as
  before (`TagService.sanitizeUid`, `RegistrationService.recordRead`).
- **Rationale**: FR-002. The production readers send `E28069150000…` where the file has `E28069152000…` (e.g.
  `E2806915000040287477C993` for line 1469), so a whole-UID comparison rejected every production scan. Every line of the
  list starts with the same 12 characters (`E28069152000`) and the last 12 are unique across the 5,008 lines, so they
  identify a bought tag whatever form the reader uses.
- **Accepted side effect**: any UID ending with the 12 characters of a bought tag counts as in the list, whatever comes
  before them (spec edge case).
- **Alternatives**: accept both the `2000` and `0000` forms of each line (stricter, but tied to one reader behaviour);
  rewrite the file in the readers' form (the file would no longer match the supplier's); normalise stored UIDs too
  (would split or merge existing tags; out of scope).

## R4. Production scan of an off-list tag: checked first, answered like an ignored read

- **Decision**: `TagService.registerScan` checks `referenceTagList.isOffList(uid)` right after the blank-UID check and
  before any database access. An off-list UID returns `200` with `ScanTagResponse` `{uid: <trimmed uid>, isCompliant:
  true, processedAt: now, message: "Tag not in reference list, ignored"}`; no Tag, no Record, no duplicate lookup. The
  constant sits next to `DUPLICATE_READ_IGNORED`. The event is logged at `DEBUG` with the reader name and the UID.
- **Rationale**: FR-006 and clarification (révision) Q1. The reader firmware already gets `200` + `isCompliant: true`
  for a read the server ignores (`ENREGISTREMENT` reader without a session: `RegistrationService.scanResponse`, "the
  reader must not raise an alert"), so the firmware has nothing new to handle. Checking before the database keeps the
  off-list path cheaper than a normal scan (SC-004). `DEBUG` rather than `INFO`: parasitic reads can be frequent on a
  line and nobody acts on them (spec: users don't care); the level can be raised in the running container
  (`logging.level.com.rfidback.service.TagService`) to investigate.
- **Alternatives**:
  - Echo the reader's `isCompliant`. Rejected: a `false` could light the line's alarm for a tag the system ignores.
  - `422` naming the rejection. Rejected by the clarification (option B): the firmware would see an error.
  - Log at `INFO` / count in a metric. Rejected for now: no consumer (deferred in the spec's coverage).

## R5. Registration reads: dropped before they reach the session

- **Decision**:
  - Tag-by-tag (`RegistrationService.recordRead`, reader in `ENREGISTREMENT` mode on `POST /tags/scan`): after the
    blank check, an off-list UID returns the usual `ScanTagResponse` (`isCompliant: true`) with the message
    "Registration read ignored: tag not in reference list"; the session is neither read nor touched.
  - Batch (`recordReads`, spec `011`): validation is unchanged and runs on every UID (1–100 distinct, 50 characters
    max) so the `400` rules don't depend on the list; off-list UIDs are then removed before `storeReads`.
    `receivedCount` still counts every distinct non-blank UID of the request; `addedCount` never counts an off-list
    one. A batch made only of off-list UIDs is still a `200` (the session's activity is refreshed as for any batch).
  - `RegistrationRead.offList` is removed from the API: a stored read is always in the list.
- **Rationale**: FR-003, FR-005, clarification (révision) Q2. Filtering on the way in means the page, the session
  save and the stored reads never see an off-list tag, with no confirmation left to design. Keeping validation before
  filtering means a reader sending an over-long UID still learns about it (spec `011`).
- **Alternatives**: store the read and hide it on the page. Rejected: it would still be "registered in the database",
  which the user excludes, and the save would need a second filter.

## R6. Bucket association: off-list UIDs refuse the whole request

- **Decision**: `TagService.registerTagsForBucket` checks the list after the existing input checks (blank, count) and
  before the "in another bucket" check: when any UID is off-list it throws
  `ResponseStatusException(BAD_REQUEST, "Tags not in the reference list: <uid>, <uid>")` and writes nothing; the
  existing `ApiExceptionHandler.handleResponseStatus` renders it as a `ProblemDetail` like other `400`s. The same
  method serves `POST /tags/buckets/{bucketNumber}` and the session save (`RegistrationService.save`), so both answer
  `400`; on the session save the session stays open, as for a `409` today. `offListConfirmed` disappears from
  `RegisterTagsRequest` and `SaveRegistrationSession`; `TagsInOtherBuckets.offListTags` disappears; the `409` goes back
  to "tags in another bucket" only. `RegistrationNotConfirmedException` keeps its name (no churn) but loses
  `offListTags`.
- **Rationale**: FR-004, clarification (révision) Q2 option A: the rule is enforced where tags enter a bucket, not only
  on the page, and no flag can override it. Checking before the move check means a request with both problems gets
  the `400` (not overridable) rather than a `409` whose confirmation would only lead to the `400`.
- **Compatibility**: a client still sending `offListConfirmed` is not refused: Spring Boot's Jackson ignores unknown
  properties. The only client is `front/tags.html`, updated in the same change.
- **Alternatives**: skip off-list UIDs and register the rest (option B, rejected by the clarification: a silent partial
  save on an Administrateur action); a dedicated exception class (no gain over `ResponseStatusException`, already used
  by `RegistrationService` for its `400`s).

## R7. The one-off purge of off-list data already stored

- **Decision**: a new `configuration/OffListTagPurge` (`ApplicationRunner`, next to `ConformityAuthorSchemaUpgrade`),
  whose `run` is `@Transactional`. Unless its marker exists (R8), it:
  1. loads all tags (`tagRepository.findAll()`, a few thousand) and keeps those with `referenceTagList.isOffList(uid)`;
  2. deletes, by tag ids in chunks of 1,000, in foreign-key order: `record_conformity_change` rows of their records,
     their `record` rows, then the `tag` rows (the bucket link is the tag's `bucket_id`, so it goes with the tag; the
     bucket stays);
  3. deletes `registration_read` rows whose UID is off-list (`findDistinctUids()` then delete by UID; they have no
     foreign key to `tag`);
  4. inserts the marker, and logs at `INFO` the number of tags, records, conformity changes and registration reads
     deleted.
  Deletes are JPQL bulk deletes in the repositories (`RecordConformityChangeRepository.deleteByRecordTagIdIn`, via a
  subquery on `record`; `RecordRepository.deleteByTagIdIn`; `TagRepository.deleteByIdIn`;
  `RegistrationReadRepository.deleteByUidIn`). Any failure rolls the whole purge back and stops startup, as
  `ConformityAuthorSchemaUpgrade` does.
- **Rationale**: FR-007, User Story 3, clarification (révision) Q3. A startup runner is how this project already runs
  one-off data/schema changes (no migration tool yet, `CLAUDE.md`). One transaction means either nothing or everything
  is deleted; the deployment health check then either passes or restores the previous image. The pre-deployment backup
  (`deploy/vps/deploy.sh` runs `backup.sh` and stops on failure) is the recovery path (spec assumption), so no export is
  built. Java-side filtering reuses the one membership rule (R3) instead of re-coding "last 12 characters" in SQL.
- **Traffic during the purge**: the web server accepts requests before runners finish. That is harmless: with R4–R6 in
  place no request can create an off-list tag, and reads of in-list tags are untouched by the purge.
- **Alternatives**:
  - A SQL script run by hand during deployment. Rejected: a manual step on the VPS, easy to skip, untested by CI.
  - `CascadeType.REMOVE` on the entities and `tagRepository.deleteAll(tags)`. Rejected: loads every record into memory
    and changes entity mappings for a one-off.
  - Keep the rows and hide them (derived flag). Rejected by the clarification.

## R8. "Only once": a marker row in a new table

- **Decision**: a new entity `DataUpgradeEntity`, table `data_upgrade` (`name` varchar(100) primary key, `applied_at`
  timestamp with time zone, not null), created by `ddl-auto: update`, with `DataUpgradeRepository`. The purge's marker
  is the row `name = "010-off-list-tag-purge"`. `OffListTagPurge.run` only logs at `INFO` that the purge was already applied when it exists; otherwise it purges
  and inserts it in the same transaction. On a new database (fresh install, each test context) the first startup finds
  nothing to delete and writes the marker.
- **Rationale**: clarification (révision) Q4 — the deletion happens at this release's deployment only; a later list
  that no longer contains a stored tag deletes nothing (spec edge case "tag retiré plus tard de la liste"). A marker in
  the same transaction as the deletes cannot be written without them, or the reverse. The table is generic so the next
  one-off data change reuses it until a migration tool arrives.
- **Alternatives**:
  - A property (`app.tags.purge-off-list=true`) set for one deployment. Rejected: must be remembered, then removed, and
    a leftover `true` re-runs it on every restart.
  - Remove the runner in the next release. Rejected alone: any restart before that release would run it again (still
    harmless today, since nothing new can be off-list, but it breaks the rule as soon as the list changes). The class
    can still be deleted later; the marker stays.
  - Detect "already done" from the data (no off-list tag left). Rejected: that is exactly the every-startup behaviour
    the clarification excluded.

## R9. What is removed

- **Decision**: remove `GET /tags/off-list` (`listOffListTags`, `OffListTag`, `OffListTagsList`,
  `TagService.listOffListTags`, `TagRepository.findAllWithBucket`, and the record count/last-date query used only by
  it), `RecordSummary.tagOffList` (`RecordService` no longer needs `ReferenceTagList`), `RegistrationRead.offList`,
  the two `offListConfirmed` fields and `TagsInOtherBuckets.offListTags`. In the front: the "Tags hors liste" section,
  the "Hors liste" badge and the off-list part of the confirmation dialog in `tags.html`; the "HORS LISTE" badge and
  its CSS in `reader.html`. `SecurityConfig` is unchanged (the route was covered by the `/api/tags/**` rule).
- **Rationale**: FR-008, FR-009 (retiré); the user doesn't want to see off-list tags anywhere. After the purge there is
  nothing to show.
- **Alternatives**: keep the fields always `false`. Rejected: dead API surface that suggests a feature that no longer
  exists.

## R10. Tests and the UIDs they use

- **Decision**:
  - `src/test/java/com/rfidback/support/ReferenceTagUids` stays (`nextInList()`, `nextInListAsReaderSends()`,
    `offList()`). Every integration test that **scans** or **registers** a tag through the API must now use
    `nextInList()`: `TagScanApiTest`, `ReaderScanSecurityTest`, `RecordStatsApiTest` (its `POST /api/tags/scan`
    cases), `RecordApiTest`, in addition to the registration tests already switched. Tests that save a `TagEntity` straight through the repository may keep made-up UIDs (the purge ran at
    context startup, before they insert anything).
  - New cases: production scan of an off-list UID (`200`, message, no Tag, no Record) in `TagScanApiTest`;
    tag-by-tag and batch registration reads with an off-list UID (not in the session, counts) in
    `RegistrationApiTest` / `RegistrationReadsApiTest`; `400` naming the UIDs on both registration routes in
    `TagRegistrationApiTest` / `RegistrationApiTest`. Unit tests in `TagServiceTest` and `RegistrationServiceTest`
    (mocked `ReferenceTagList`).
  - `OffListTagPurgeTest` (`@SpringBootTest`, `test` profile) with **its own in-memory database**
    (`spring.datasource.url=jdbc:h2:mem:off-list-purge;DB_CLOSE_DELAY=-1`) so the deletions cannot touch the data of
    other test classes sharing the default context. It removes the marker, seeds in-list and off-list tags with a
    bucket, records, conformity changes and registration reads, calls `run`, checks what is gone and what is left, then
    seeds another off-list tag and checks a second `run` deletes nothing (marker present).
  - Removed: `TagOffListApiTest`, the off-list confirmation cases of `TagRegistrationApiTest`, `RegistrationApiTest`,
    `TagServiceTest`, `RegistrationServiceTest`, the `tagOffList` cases of `RecordApiTest` / `RecordServiceTest`, the
    `/api/tags/off-list` row of `AccessMatrixSecurityTest`.
- **Rationale**: in-list UIDs from the shipped file keep proving the real list loads in every Spring test, as before;
  the static counter over 5,008 UIDs covers the extra scans (a full run uses a few hundred). A separate database for
  the purge test is the only way to run a real delete-everything-off-list without order-dependent failures (CI runs
  classes alphabetically in one JVM).
- **Alternatives**: `@DirtiesContext` on the purge test alone. Rejected: it rebuilds the context after the test but the
  shared in-memory database (`DB_CLOSE_DELAY=-1`) would already have lost other classes' rows.
