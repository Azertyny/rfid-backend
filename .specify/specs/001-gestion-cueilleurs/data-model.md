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

---

## Amendment 2026-10-11: logical deletion (FR-010)

### Picker: one new column

| Field | Type | Rules |
|---|---|---|
| `deletedAt` (`deleted_at`) | OffsetDateTime, nullable | `null` = live picker; set once by `DELETE /pickers/{id}`, never cleared |

`isDeleted()` ⇔ `deletedAt != null`. Added by `ddl-auto: update`; no data upgrade (existing rows are live).

### Lifecycle (amended)

```text
          POST /pickers                PUT /pickers/{id}
 (none) ───────────────▶ Live ◀──────────────────────┐
                           │  └─────────────────────┘
                           │ DELETE /pickers/{id}  (picker row locked)
                           ▼
         ┌── has ≥ 1 bucket? ──yes──▶ 409, picker unchanged
         └──no──▶ 204, deleted_at = now ──▶ Deleted (final)
```

| | Live | Deleted |
|---|---|---|
| `GET /pickers` | listed | not listed |
| `GET` / `PUT` / `DELETE /pickers/{id}` | 200 / 200 / 204 or 409 | 404 |
| Assign a bucket to them | 204 | 404 |
| `GET /work-hours?day=` | listed | not listed |
| `PUT /work-hours` naming them | saved | 404 |
| Name (FR-002) | taken | **still taken** (409 on a duplicate) |
| Records, hours, conformity changes | kept | **kept**, shown under their name on the dashboard |

### Repository changes

- `PickerRepository`: + `findAllByDeletedAtIsNull(Pageable)`, `findAllByDeletedAtIsNull()`, `findWithLockById(id)` (`PESSIMISTIC_WRITE`, 5 s). The duplicate-name queries are unchanged (they see deleted rows on purpose).
- `PickerWorkDayRepository.deleteByPicker`: removed (no caller left).
