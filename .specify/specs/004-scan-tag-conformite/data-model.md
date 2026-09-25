# Data model: Scan d'un TAG par un lecteur

No new table and no new column. One index is added. See [research.md](research.md) R2–R4.

## Record (`record`) — changed

| Field | Column | Type | Notes |
|---|---|---|---|
| id | `id` | UUID | unchanged |
| picker | `picker_id` | FK → `picker`, nullable | unchanged: the picker of the tag's bucket at scan time |
| tag | `tag_id` | FK → `tag`, not null | unchanged |
| reader | `reader_id` | FK → `reader`, not null | unchanged |
| compliant | `conformity` | boolean, not null | set from the reader's `isCompliant`; lowered to `false` by a non-compliant repeat within the window (rule 3); can be overridden later by an operator (spec `005`) |
| creationDate | `creation_date` | timestamp with time zone, not null, not updatable | unchanged: `@CreationTimestamp` |
| comment | `comment` | varchar, nullable | unchanged: never set by a scan |

**New index**: `idx_record_reader_tag_date` on `(reader_id, tag_id, creation_date)`, declared in `@Table(indexes = …)`. It serves the deduplication lookup (R4).

## Scan rules (production mode)

Applied by `TagService.registerScan` to a scan from an active reader in `PRODUCTION` mode:

1. `uid` must contain at least one non-blank character. Otherwise → `400` with a `ProblemDetail` naming the field, nothing is written (FR-002, enforced by the contract, R1).
2. `uid` is trimmed. An unknown UID creates a `Tag` with no bucket (FR-002).
3. **Deduplication (FR-008)**: if the tag already existed and a `Record` exists for the **same reader** and the **same tag** with `creationDate` > now − `app.scan.duplicate-window` (default `10s`), no `Record` is created. If the scan says `isCompliant: false` and that Record is compliant, the Record's `compliant` becomes `false` (the non-compliant verdict wins within the window); a compliant repeat never changes it. The response carries that Record's values, after this update, and `message = "Duplicate read ignored"`.
4. Otherwise a new `Record` is created with the tag, the reader, the bucket's picker (or `null`) and `compliant = isCompliant` (FR-003, FR-004, FR-005).

The window is sliding: it starts at the most recent Record for that (reader, tag), and an ignored read creates no Record, so it does not extend the window. A tag that stays in front of a reader for 25 s with reads every second gives Records at about t = 0, 10 and 20 s. Each new Record restarts the window.

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `app.scan.duplicate-window` | `10s` | A scan from the same reader for the same tag within this time after its latest Record is ignored. `0s` disables deduplication. |
