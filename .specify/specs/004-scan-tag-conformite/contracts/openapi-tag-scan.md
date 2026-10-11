# Contract: `POST /api/tags/scan`

Changes to `src/main/resources/openapi/api.yaml`. The generated `TagApiDelegate.scanTag` signature doesn't change. §1 is the
2026-09-24 delivery; §4 is the 2026-10-11 amendment, which changes descriptions only.

## 1. `api.yaml` (delivered 2026-09-24)

`ScanTagRequest.uid` carries `pattern: '.*\S.*'`. A blank `uid` gets `400` with a `ProblemDetail` naming the field, through
`ApiExceptionHandler` (research R1). The `400` and `401` responses are declared on the route.

## 2. Responses (`PRODUCTION` reader, after the amendment)

Rules are applied in this order, first match wins ([data-model.md](../data-model.md) §Scan rules).

| Case | Status | `isCompliant` | `processedAt` | `message` | Record created | Tag after |
|---|---|---|---|---|---|---|
| Missing or unknown `x-api-token`, or reader disabled | `401` | — | — | — | no | — |
| `uid` missing, empty or only spaces; `isCompliant` missing | `400` | — | — | — | no | — |
| `uid` not in the reference list (spec `010`) | `200` | `true` | now | `"Tag not in reference list, ignored"` | no | not stored |
| Same reader, tag's counting Record last read less than 10 s ago (rule 1) | `200` | that Record's, lowered if this read says `false` | that Record's creation | `"Duplicate read ignored"` | no | unchanged |
| Tag in bucket B, same reader counted another tag of B less than 2 s ago (rule 2) | `200` | the crate Record's, lowered if this read says `false` | the crate Record's creation | `"Second tag of the crate ignored"` | no | out of B |
| Tag counted today, not the same pass (any reader) (rule 3) | `200` | `false`, whatever was sent | new Record's | `"Tag already scanned today"` | yes, non-compliant, no picker | still out of any bucket |
| Otherwise: tag in a bucket, new tag, or counted on an earlier day (rule 4) | `200` | as sent | new Record's | absent | yes, picker from the bucket or none | out of its bucket |

`uid` is always the uid the reader sent (trimmed), including for a crate's second tag, whose Record was created for the crate's first tag.

`ENREGISTREMENT` readers: unchanged (spec `003`, spec `011`).

## 3. Compatibility for reader devices

- The response shape is unchanged. `message` was already optional; it takes two new values in `PRODUCTION` mode.
- `isCompliant: false` on a rescan is new behavior from the reader's point of view: a reader that raises an alert on a non-compliant answer will now raise it for a tag reused the same day. That is the intent of FR-009.
- No retry is expected on any of these `200` answers.

## 4. `api.yaml` changes (2026-10-11)

### `/tags/scan` → `post.description`

Replace the deduplication paragraph with:

```text
A blank uid is rejected with 400. For a reader in PRODUCTION mode:
- a read of a tag by the same reader less than app.scan.duplicate-window (10 s by default) after the last read of the
  record that counted it is the same pass: no new record, message "Duplicate read ignored". If it reports isCompliant
  false and that record is compliant, the record becomes non-compliant;
- a tag of bucket B read by the same reader less than app.scan.crate-window (2 s by default) after a record counted
  another tag of B is the second tag of that crate: no new record, the tag leaves its bucket, message
  "Second tag of the crate ignored";
- a tag already counted today (station day), read again, gives a new non-compliant record with no picker, whatever
  isCompliant says: message "Tag already scanned today";
- otherwise a new record is created and the tag leaves its bucket. Registering the tag into a bucket again resets it.
```

### `ScanTagResponse.message`

```yaml
message:
  type: string
  description: >
    Processing message, absent when a new record counted the tag. "Duplicate read ignored" for a repeat of the same pass,
    "Second tag of the crate ignored" for a crate's second tag (no new record), "Tag already scanned today" for a
    non-compliant rescan, "Tag not in reference list, ignored" for a uid outside the reference list; registration
    messages for readers in ENREGISTREMENT mode.
```

No schema, route or status change.
