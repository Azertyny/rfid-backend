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
