# Data Model: Consultation des lectures et bascule de conformité

**Feature**: `005-consultation-lectures-conformite` | **Date**: 2026-09-25 | **Plan**: [plan.md](plan.md)

## New entity: `RecordConformityChangeEntity` (table `record_conformity_change`)

One row per manual compliance change that actually changed the value (spec FR-003, key entity "Modification de
conformité").

| Field | Column | Type | Constraints | Notes |
|---|---|---|---|---|
| `id` | `id` | `UUID` | PK, generated (`GenerationType.UUID`) | Same convention as every other entity |
| `record` | `record_id` | `@ManyToOne(LAZY, optional = false)` → `RecordEntity` | not null, FK → `record.id` | Unidirectional: `RecordEntity` has no collection |
| `previousCompliant` | `previous_conformity` | `boolean` | not null | Value before the change; for the first row, the reader's original verdict |
| `newCompliant` | `new_conformity` | `boolean` | not null | Always `!previousCompliant` (no-op requests write nothing, R3) |
| `author` | `author_id` | `@ManyToOne(LAZY, optional = false)` → `UserEntity` | not null, FK → `app_user.id` | The logged-in user; users are never deleted (spec `008`) |
| `changedAt` | `changed_at` | `OffsetDateTime` | not null, not updatable, `@CreationTimestamp` | |

**Index**: `idx_record_conformity_change_record_date` on `(record_id, changed_at)`. It serves both the history read
(FR-007) and the existence check of a duplicate scan (FR-006).

Column names follow `RecordEntity.compliant` → `conformity`.

**Rows are never updated or deleted.** Records themselves are never deleted today (no delete path in
`RecordRepository` users), so the foreign key needs no cascade rule.

## Changed entity: `RecordEntity`

No column change. **New index** `idx_record_reader_date` on `(reader_id, creation_date)`, next to spec `004`'s
`idx_record_reader_tag_date` (research R11). It serves the last-10 list (spec SC-003).

Behavior changes:

- `compliant` stays the current value, which is authoritative (spec `004`).
- **Locked for duplicate scans** (FR-006): once at least one `RecordConformityChangeEntity` exists for a record,
  a duplicate scan (spec `004`, FR-008) no longer changes `compliant`.

### Compliance state transitions

```text
                 scan (spec 004)
                       │
                       ▼
          ┌──── compliant = reader verdict ────┐
          │    (no change row, not locked)     │
          │                                    │
 duplicate non-compliant scan      PATCH with a different value
 (true → false, no change row)     (1 change row, now locked)
          │                                    │
          ▼                                    ▼
   compliant = false                 compliant = Opérateur value
   (still not locked)          ┌──►  (locked: duplicate scans ignored)
                               │                │
                               └── PATCH with a different value (+1 change row)

 PATCH with the current value, in any state → no-op (204, no row, no lock change)
```

## Repositories

| Repository | Method | Use |
|---|---|---|
| `RecordRepository` | `findTop10ByReader_NameOrderByCreationDateDesc` + `@EntityGraph("tag")` (existing method) | Last-10 list: tags loaded in the same query (R12) |
| `RecordRepository` | `findWithLockById(UUID)` — `@Lock(PESSIMISTIC_WRITE)`, `@Query` by id | PATCH: read the current value under a row lock (R3) |
| `RecordRepository` | `findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc` + `@Lock(PESSIMISTIC_WRITE)` | Duplicate scan: load the record under the same lock (R4) |
| `RecordConformityChangeRepository` (new) | `existsByRecord(RecordEntity)` | Duplicate scan: is the record locked? (FR-006) |
| `RecordConformityChangeRepository` (new) | `findAllByRecordOrderByChangedAtAsc(RecordEntity)` + `@EntityGraph("author")` | History read (FR-007) |
| `UserRepository` | `findByUsername` (existing) | Resolve the author from the session |

## Validation rules

- PATCH body: `isCompliant` required (already in `api.yaml`, `400` otherwise).
- Unknown `recordId` → `404` on PATCH and on history read.
- The author must exist and be the logged-in user. The chain guarantees a logged-in user, so a missing user is an
  internal error, not a client error.

## API models (see [contracts/openapi-records.md](contracts/openapi-records.md))

- `ConformityChange`: `previousIsCompliant`, `newIsCompliant`, `authorUsername`, `changedAt`.
- `ConformityChangesList`: `changes: ConformityChange[]`, oldest first.
