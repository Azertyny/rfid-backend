# Data model: Scan d'un TAG par un lecteur

The 2026-10-11 amendment adds three nullable columns and replaces one index. See [research.md](research.md) R7–R13 (R2–R4 for the
first delivery).

## Record (`record`) — changed

| Field | Column | Type | Notes |
|---|---|---|---|
| id | `id` | UUID | unchanged |
| picker | `picker_id` | FK → `picker`, nullable | the picker of the tag's bucket at scan time; `null` for a tag without a bucket, including every KO rescan |
| tag | `tag_id` | FK → `tag`, not null | the tag that created the Record (the first tag of a crate) |
| reader | `reader_id` | FK → `reader`, not null | unchanged |
| activity | `activity_id` | FK → `activity`, nullable | unchanged (spec `012`) |
| compliant | `conformity` | boolean, not null | the reader's `isCompliant`, or `false` for a rescan the same day (FR-009); lowered by a non-compliant read it absorbs (rules 1–2); overridden by an Opérateur (spec `005`) |
| creationDate | `creation_date` | timestamp with time zone, not null | unchanged: `@CreationTimestamp`; sets the Record's station day for rule 3 |
| **lastReadAt** | `last_read_at` | timestamp with time zone, **nullable** | **new**: set at creation, then to now on every read the Record absorbs (rules 1–2). `null` on Records from before the deployment, which never match a window (R7) |
| **bucket** | `bucket_id` | FK → `bucket`, **nullable** | **new**: the bucket the tag left when this Record counted it; `null` for a tag without a bucket and for KO rescans (R10) |
| comment | `comment` | varchar, nullable | unchanged |

**Indexes**: `idx_record_reader_bucket_date` on `(reader_id, bucket_id, creation_date)` is new and serves the crate lookup (R10).
`idx_record_reader_tag_date` is no longer declared; existing databases keep it. The other indexes are unchanged.

## Tag (`tag`) — changed

| Field | Column | Type | Notes |
|---|---|---|---|
| id, uid, creationDate | | | unchanged |
| bucket | `bucket_id` | FK → `bucket`, nullable | set by registration (spec `003`); **now set to `null` when a scan counts the tag** (rules 2 and 4) |
| **countedRecord** | `counted_record_id` | FK → `record`, **nullable** | **new**: the Record that counted the tag since its last registration into a bucket: its own Record, the crate's Record (rule 2), or its rescan Record (rule 3). Set to `null` by `registerTagsForBucket` (R8) |

### Tag lifecycle (no state column: the two fields are the state)

| `bucket` | `countedRecord` | Meaning | Next production read |
|---|---|---|---|
| set | `null` | registered, not yet scanned | rule 2 if it belongs to a crate already counted, else rule 4 |
| `null` | set | counted (out of its bucket) | rule 1 if still the same pass, else rule 3 (counting Record from today) or rule 4 (earlier day) |
| `null` | `null` | never registered (created by a scan), or from before the deployment and not in a bucket | rule 4 |
| set | set | not reachable: registration clears `countedRecord`, and counting clears `bucket` | — |

Registering the tag into a bucket (direct or through a registration session) always brings it back to the first row.

## Scan rules (production mode)

Applied by `TagService.registerScan` to a read from an active `PRODUCTION` reader R at time `now`, after the blank-UID check
(FR-002), the off-list check (spec `010`) and the tag lookup or creation. First match wins (R13):

1. **Same pass** (FR-008, R7): `countedRecord` = L with `L.reader = R` and `L.lastReadAt > now − duplicate-window` →
   no Record. `L.lastReadAt = now`; if the read says `isCompliant: false`, L is compliant and no Opérateur changed it (spec `005`, FR-006), L becomes non-compliant.
   Response: L's values, `message: "Duplicate read ignored"`.
2. **Second tag of a crate** (FR-010, R10): `bucket` = B and a Record C exists with `C.reader = R`, `C.bucket = B`, `C.tag ≠` this tag, `C.creationDate > now − crate-window` → no Record. The tag leaves B, `countedRecord = C`, `C.lastReadAt = now`, same non-compliant rule on C. Response: C's values, `message: "Second tag of the crate ignored"`.
3. **Rescan today** (FR-009, R9): `countedRecord` = L with `L.creationDate ≥` start of today (station zone) → new Record with `compliant = false`, `picker = null`, `bucket = null`, `lastReadAt = now`, the line's activity; the tag's `countedRecord` = the new Record. L is unchanged. Response: `isCompliant: false`, `message: "Tag already scanned today"`.
4. **Count** (FR-003, FR-004, FR-009): new Record with the reader's verdict, `picker` = the bucket's picker or `null`, `bucket` = the tag's bucket, `lastReadAt = now`, the line's activity; the tag leaves its bucket and `countedRecord` = the new Record. Response as before, no `message`.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `app.scan.duplicate-window` | `10s` | A read by the same reader less than this after the Record's **last read** is the same pass (rule 1). `0s` turns rule 1 off. |
| `app.scan.crate-window` | `2s` | **New**: a tag of the same bucket first read by the same reader less than this after the crate's Record was created joins that crate (rule 2). `0s` turns rule 2 off. |
