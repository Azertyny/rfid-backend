# Data Model: Gestion des seaux et affectation (006)

**No schema change.** The `bucket.picker_id` column already allows several buckets per picker. The spec confirms that this is the target (FR-004), so no unique constraint is added. Hibernate `ddl-auto: update` has nothing to do.

## Bucket (`bucket` table, `entity/BucketEntity.java`)

| Field | Type | Rules |
|---|---|---|
| `id` | UUID | generated |
| `number` | Integer | required, unique; the list is sorted by it |
| `picker` | `@ManyToOne` → `PickerEntity`, column `picker_id` | nullable; **not unique** |
| `creationDate` | OffsetDateTime | set on insert |

Relationships:

- Bucket 0..n ↔ 1 Picker: a bucket has **0 or 1** picker, and a picker has **0..n** buckets.
- Bucket 1 ↔ 1..n Tag: see spec `003`.

### Assignment lifecycle

```text
                PUT /buckets/{id}/picker {pickerId: P}
   ┌──────────┐ ─────────────────────────────────────▶ ┌──────────────┐
   │Non affecté│                                        │ Affecté à P  │ ──┐ PUT {pickerId: Q}
   └──────────┘ ◀───────────────────────────────────── └──────────────┘ ◀─┘ (réaffectation, P perd le seau)
                DELETE /buckets/{id}/picker
```

- `PUT` works from either state and always answers `204`. When the bucket already had a picker, that picker is replaced; the page warns about this before confirming (FR-004a).
- `DELETE` always answers `204`, including when the bucket had no picker (idempotent, FR-007).
- Either call answers `404` when the bucket does not exist. `PUT` also answers `404` when the picker does not exist, and in both cases nothing changes.
- Assigning or unassigning never changes the bucket's tags or its scan records.

## Picker (API view, spec `001`)

The only change is in the `Picker` response schema:

| Before | After |
|---|---|
| `bucketNumber: integer` (optional, a single value; `500` when there are 2 buckets) | `bucketNumbers: integer[]` (required, sorted ascending, `[]` when none) |

It is built from `BucketRepository`:

- `findAllByPickerIn(pickers)`: list view, one query per page, grouped by picker id.
- `findAllByPickerOrderByNumberAsc(picker)`: detail view (**new**).
- `findByPicker(picker)`: **removed**, because it cannot handle a picker with several buckets.
- `existsByPicker(picker)`: unchanged; still blocks deleting a picker who has buckets (spec `001`).

---

## Amendment 2026-10-11: deleting a bucket (FR-010)

**No schema change.** The bucket row is physically deleted; the two foreign keys pointing at it are nulled first, in the same transaction (research R10).

| Table | Column | On bucket delete |
|---|---|---|
| `tag` | `bucket_id` | set to `null`: the tag stays, without bucket (same state as a tag never registered) |
| `record` | `bucket_id` | set to `null`: the record keeps its tag, picker, activity, conformity and dates |
| `bucket` | — | row deleted; its `number` can be used again |

### Bucket lifecycle (amended)

```text
  POST /tags/buckets/{n} (number unknown)           DELETE /buckets/{id}
 ─────────────────────────────────────▶ [ exists: Non affecté ⇄ Affecté à P ] ─────────────────▶ (gone, number n free)
                                                                                 tags → no bucket, records → no bucket
```

- `DELETE` works from either assignment state and answers `204`; `404` when the bucket does not exist (unknown or already deleted), and nothing changes.
- A delete, an assignment, an unassignment, a registration under the bucket's number and a scan counting one of its tags are ordered by a lock on the bucket row (research R12): the one that comes second sees the committed result of the first.

### Repository additions

- `BucketRepository.findWithLockById(id)`, `findWithLockByNumber(number)`: `PESSIMISTIC_WRITE`, 5 s timeout.
- `TagRepository.clearBucket(bucket)`, `RecordRepository.clearBucket(bucket)`: bulk `update … set bucket = null where bucket = :bucket`.
