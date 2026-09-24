# Data Model: Gestion des cueilleurs (001)

No schema change. `ddl-auto: update` has nothing new to create.

## Picker (`picker` table, `entity/PickerEntity.java`), unchanged

| Field | Type | Constraints | Source |
|---|---|---|---|
| `id` | UUID | PK, generated | |
| `lastname` | string(50) | not null; trimmed before write | FR-001 |
| `firstname` | string(50) | not null; trimmed before write | FR-001 |
| `comment` | string(280) | nullable; blank → `null` | FR-001 |
| `creationDate` | timestamp with offset | not null, not updatable | |

**Uniqueness**: (`lastname`, `firstname`) is unique, ignoring case, and enforced in the service only (no DB unique index). A duplicate on create or update returns `409` (FR-002, FR-005).

**Sortable fields** (FR-003, research R1/R2):

| `sort` field | Column | Tie-breakers appended |
|---|---|---|
| `lastname` | `lastname` | `firstname`, `id` |
| `firstname` | `firstname` | `lastname`, `id` |
| `creationDate` | `creationDate` | `lastname`, `firstname`, `id` |
| *(missing or blank)* | `lastname ASC, firstname ASC` | `id` |

Any other field, or a direction other than `asc`/`desc`, returns `400`.

## Bucket (`bucket` table), read-only from this module

`BucketEntity.picker` (FK `picker_id`, nullable, no cascade) links 0..n buckets to a picker.

**New repository method**: `BucketRepository.existsByPicker(PickerEntity)` → `boolean`.

## Lifecycle

```text
          POST /pickers                PUT /pickers/{id}
 (none) ───────────────▶ Picker ◀──────────────────────┐
                           │  └────────────────────────┘
                           │ DELETE /pickers/{id}
                           ▼
         ┌── has ≥ 1 bucket? ──yes──▶ 409, picker kept
         └──no──▶ 204, picker removed
```

Before a picker can be deleted, each of their buckets must be unassigned with `DELETE /api/buckets/{bucketId}/picker` (spec `006`).
