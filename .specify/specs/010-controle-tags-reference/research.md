# Research: Contrôle des tags par rapport à la liste de référence

Phase 0 decisions for [plan.md](plan.md). Each entry gives the decision, the reason, and the alternatives set aside.

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
  strips a UTF-8 BOM, accepts `\n` or `\r\n`, skips blank lines, stores UIDs upper-cased, ignores duplicates, and logs
  the number of UIDs loaded.
- **Rationale**: spec edge case "liste absente, vide ou mal formée": a broken list would flag every tag and push
  Administrateurs to confirm everything, which defeats the check. Failing the boot is caught by the deployment health
  check (`deploy/vps/deploy.sh` waits for `/actuator/health`, then fails and names the previous tag to redeploy),
  and before that by CI, since `ReferenceTagListTest` loads the shipped file. The 24-hex rule matches every line of the delivered file.
- **Alternatives**: start anyway and disable the check. Rejected: a silent failure mode.

## R3. Normalising a UID before the check

- **Decision**: `contains(uid)` compares `uid.trim().toUpperCase(Locale.ROOT)` against the set. The stored `Tag.uid`
  is not changed: it stays trimmed only, as today (`TagService.sanitizeUid`, `RegistrationService.recordRead`).
- **Rationale**: FR-002. Changing how UIDs are stored (upper-casing them) would split or merge existing tags and is
  out of scope; readers send upper-case EPCs anyway.
- **Alternatives**: normalise stored UIDs too. Rejected for this feature; noted as a possible follow-up.

## R4. "Off-list" is derived, never stored

- **Decision**: no new column. Whether a tag, a registration read or a Record is off-list is computed when the answer
  is built, from `ReferenceTagList.contains(tag.uid)`.
- **Rationale**: spec assumption "l'indication 'hors liste' d'une lecture reflète la liste de référence en vigueur".
  After a new version adds a UID, its past reads stop being flagged, which is what an Administrateur expects. No
  schema change, nothing to migrate, and the scan path (FR-006) is unchanged: the Record is created exactly as today.
- **Alternatives**: a `off_list` boolean on `record` set at scan time. Rejected: it would freeze a verdict the list may
  later correct, and add a write concern to the hot path.

## R5. Registration: one 409 for both confirmations

- **Decision**: `RegisterTagsRequest` and `SaveRegistrationSession` get `offListConfirmed` (boolean, default
  `false`) next to `moveConfirmed`. `TagService.registerTagsForBucket` computes both lists first — tags in another
  bucket not covered by `moveConfirmed`, off-list tags not covered by `offListConfirmed` — and when either is non-empty
  throws one `409` that carries both. The existing body `TagsInOtherBuckets` gains `offListTags` (array of UIDs,
  required, possibly empty); `tags` stays required and may now be empty. The exception becomes
  `RegistrationNotConfirmedException` (rename of `TagsInOtherBucketsException`).
- **Rationale**: FR-004 and clarification Q2 (warn, then save after confirmation). One 409 lets `tags.html` show a
  single dialog listing everything to confirm; each flag only covers its own kind, as the spec requires ("confirmer
  l'une ne vaut pas confirmation de l'autre"). Nothing is written before the check, as today, and on the session save
  the session stays open (`RegistrationService.save` already relies on that).
- **Compatibility**: a client that never sends `offListConfirmed` gets a 409 for off-list tags — intended. A client
  reading only `tags` still works; the front is updated to read both. The schema keeps its name to avoid churn in
  generated code and tests; its description changes.
- **Alternatives**:
  - Two separate 409s in sequence (move first, then off-list). Rejected: two dialogs for one save.
  - A single `confirmed` flag for both. Rejected by the spec.
  - Rename the schema to `RegistrationConflict`. Cleaner name, but breaks generated class names for no user gain.

## R6. Where off-list reads are shown (FR-007, FR-008, FR-009)

- **Decision**:
  - `RegistrationRead` gets `offList` (boolean, required); `tags.html` shows a red "hors liste" badge next to the
    existing bucket badge (FR-003).
  - `RecordSummary` gets `tagOffList` (boolean, required); `reader.html` (line kiosk, the only screen listing records)
    shows a "hors liste" badge on the box (FR-007, FR-008). The kiosk chain already allows this route; no security
    change.
  - A new route `GET /api/tags/off-list` (Administrateur, matched by the existing `/api/tags/**` rule) returns every
    known tag that is off-list, with its bucket number, its record count and its last record date. `tags.html` gets a
    "Tags hors liste" section that loads it on demand (FR-009, User Story 3).
- **Rationale**: the app has no paged record search, only the last 10 records of a reader (`GET
  /records/readers/{uid}`). A "only off-list" filter on 10 rows is meaningless, so the spec's FR-008 was revised: the
  off-list tag list, with counts and last read, is the way to find those reads. `ScanTagResponse` is unchanged so
  reader firmware has nothing to change (FR-006).
- **Query cost**: the off-list list loads all tags with their bucket (`findAllWithBucket`, one query; a season is a
  few thousand tags), filters them in memory, then gets counts and last dates for the off-list ones only in one
  grouped query on `record` (`tag_id IN (...)`, served by the existing index on `record`). It is an on-demand admin
  page, not a hot path.
- **Alternatives**: a general `GET /records?offList=true` search. Rejected: a new paged listing for one use; out of
  proportion.

## R7. Tests and the arbitrary UIDs they use

- **Decision**: integration tests keep the shipped list (no override in `application-test.yml`), so every Spring
  test also proves the real file loads. A test helper `src/test/java/com/rfidback/support/ReferenceTagUids.java`
  reads `tags/rfid_tag_list.csv` from the classpath and hands out UIDs one by one through a static counter
  (`nextInList()`), plus `offList()` returning `"OFF-LIST-" + UUID`. Existing tests that register made-up UIDs
  (`TagRegistrationApiTest`, `RegistrationApiTest`: their `uniqueUid()` helpers) switch to `nextInList()`, or to
  `offList()` with `offListConfirmed: true` where a case is about the list. `AccessMatrixSecurityTest` only checks
  that a role is let through, so its `MATRIX-TAG` answering `409` is fine; it gains the new `GET /api/tags/off-list`
  route. Unit tests (`TagServiceTest`, `RegistrationServiceTest`, `RecordServiceTest`) mock `ReferenceTagList`.
  `ReferenceTagListTest` covers the shipped file (5,008 UIDs, SC-002) and malformed fixtures under
  `src/test/resources/tags/` (R2).
- **Rationale**: the tests share one in-memory database per Spring context and each registration needs a UID no other
  test used; a static counter over 5,008 UIDs gives that for the whole run (one JVM, as Surefire runs by default).
  A tiny test list would run out or collide.
- **Alternatives**:
  - A small test-only list. Rejected: too few unique UIDs for tests sharing a database.
  - A property to turn the check off in tests. Rejected: the check would never run in integration tests.
