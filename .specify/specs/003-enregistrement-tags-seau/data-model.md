# Data model: Enregistrement des TAGs sur un seau

Schema changes go through the entities with `ddl-auto: update`; there is no migration tool. Decisions referenced as R*n* are in [research.md](research.md).

## Reader (`reader`), changed

| Field | Type | Rule |
|---|---|---|
| `mode` *(new)* | `ReaderMode` enum, `varchar(20) not null`, `@Enumerated(STRING)`, `@ColumnDefault("'PRODUCTION'")`, `@Builder.Default PRODUCTION` | `PRODUCTION` or `ENREGISTREMENT`. Set by an Administrateur through `PATCH /api/readers/{id}` (R1). |

Unchanged: `id`, `name` (API `uid`), `apitoken`, `active`, `creationDate`, `updateDate`.

**Transitions**: `PRODUCTION ⇄ ENREGISTREMENT`, independent of `active`. Leaving `ENREGISTREMENT`, or disabling the reader, deletes its open registration session (R9).

**Scan routing** (R2): `active` and `PRODUCTION` → a `Record` is created (spec `004`, unchanged). `active` and `ENREGISTREMENT` → a registration read goes into the reader's open session, or is ignored when there is no session. No `Record` and no `Tag` are created either way. Inactive → `401` from the filter (spec `002`, unchanged).

## RegistrationSession (`registration_session`), new

| Field | Type | Rule |
|---|---|---|
| `id` | UUID | generated |
| `reader` | `@ManyToOne` → `reader`, `reader_id not null`, **unique** | one open session per reader (FR-010, R3) |
| `startedBy` | `@ManyToOne` → `app_user`, `not null` | the Administrateur who clicked "Start". Only that user can read, save or cancel it (R6). |
| `startedAt` | `OffsetDateTime not null` | set by the service from its `Clock` |
| `lastActivityAt` | `OffsetDateTime not null` | updated on each read received and each page request, throttled to once per 30 s for `GET` (R5) |

**Reads**: no `@OneToMany` collection on the session (changed during implementation). Reads are listed with `RegistrationReadRepository.findAllBySessionOrderByFirstReadAtAsc` and deleted with `deleteBySession` before the session itself. A collection cached earlier in the same persistence context could otherwise hide new reads or leave rows behind that block the delete on the foreign key.

**Validation at creation**: the reader exists (`404`), is `active` and in `ENREGISTREMENT` mode (`400`), and has no live session (`409`, with the owner's username and start time). A session found expired is deleted first, so the new one can take its place.

**Lifecycle**:

```text
          Start (POST)                     Save (POST …/save) → tags registered
 (none) ──────────────▶ OPEN ──────────────────────────────────────────────▶ deleted
                          │  Cancel (DELETE)
                          ├──────────────────────────────────────────────▶ deleted
                          │  last_activity_at + timeout < now (checked lazily)
                          ├──────────────────────────────────────────────▶ deleted
                          │  reader switched to PRODUCTION or disabled
                          └──────────────────────────────────────────────▶ deleted
```

**Expiry**: `lastActivityAt + app.registration.session-timeout < clock.now()`. Default timeout `5m` (R5). Deleting an expired session is committed even when the request then answers `404` (R3a).

## RegistrationRead (`registration_read`), new

The "Lecture temporaire" of the spec.

| Field | Type | Rule |
|---|---|---|
| `id` | UUID | generated |
| `session` | `@ManyToOne` → `registration_session`, `not null` | |
| `uid` | `varchar(50) not null` | trimmed; blank reads are ignored |
| `firstReadAt` | `OffsetDateTime not null` | time of the first read of this tag in this session |

**Uniqueness**: `unique (session_id, uid)`. A repeated read of the same tag does not add a row (FR-008): the service checks first, and a concurrent duplicate insert is caught and ignored. That insert runs in its own transaction (R3a).

**Not persisted**: the tag's current bucket. The session `GET` computes it on each call from `tag.bucket` (FR-009), so the warning stays correct if someone else moves the tag meanwhile.

## Tag (`tag`) and Bucket (`bucket`), unchanged schema

Behaviour changes in `TagService.registerTagsForBucket` (R7, R8):

1. Keep only the unique, non-blank, trimmed UIDs. If none are left → `400`.
2. Load the existing tags among them. Those whose `bucket` is set and differs from the target bucket are "tags in other buckets". If there are any and `moveConfirmed` is not `true` → `409` with `[{uid, bucketNumber}]`, and nothing is written.
3. Find or create the bucket by number (FR-002, unchanged).
4. Create the missing tags and set `bucket` on every tag of the request. The bucket's other tags are **not** detached (FR-001).
5. `registeredCount` = count from step 1; `totalCount` = `tagRepository.countByBucket(bucket)` after the save.

New repository methods: `TagRepository.findAllByUidIn(Collection<String>)`, `TagRepository.countByBucket(BucketEntity)`, `RegistrationSessionRepository.findByReader(ReaderEntity)`, `RegistrationReadRepository.existsBySessionAndUid(...)`.
