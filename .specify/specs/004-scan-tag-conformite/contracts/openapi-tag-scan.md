# Contract: `POST /api/tags/scan`

Changes to `src/main/resources/openapi/api.yaml`. The generated `TagApiDelegate.scanTag` signature doesn't change. Only a validation annotation is added to `ScanTagRequest.uid`.

## 1. `api.yaml` changes

### `ScanTagRequest.uid`

```yaml
uid:
  type: string
  pattern: '.*\S.*'
  description: Unique identifier of the scanned tag. Must contain at least one non-blank character; surrounding spaces are removed.
  example: E2000017221101891400A23G
```

### `/tags/scan` → `post.description`

Append:

```text
A blank uid is rejected with 400. For a reader in PRODUCTION mode, a scan of the same tag by the same
reader within app.scan.duplicate-window (10 s by default) of that tag's latest record creates no new
record: the response carries the existing record and the message "Duplicate read ignored". If that
repeat reports isCompliant false and the record is compliant, the record becomes non-compliant.
```

### `ScanTagResponse.message`

```yaml
message:
  type: string
  description: >
    Processing message. "Duplicate read ignored" when the scan repeated a recent one (no new record);
    registration messages for readers in ENREGISTREMENT mode; absent otherwise.
```

The `400` and `401` responses are already declared on the route. No new schema.

### `ApiExceptionHandler` (not in `api.yaml`)

A `MethodArgumentNotValidException` (any invalid `@Valid` body, this route included) is answered with `400` and a `ProblemDetail` whose `detail` lists `field: message` for each field error, for example `uid: must match ".*\S.*"` (research R1).

## 2. Responses

| Case | Status | Body | Record created |
|---|---|---|---|
| Missing or unknown `x-api-token`, or reader disabled | `401` | — | no |
| `uid` missing, empty or only spaces | `400` | `ProblemDetail`, `detail` names `uid` | no |
| `isCompliant` missing | `400` | `ProblemDetail`, `detail` names `isCompliant` | no |
| `PRODUCTION` reader, new tag or first read in the window | `200` | `uid`, `isCompliant` as sent, `processedAt` = new Record's date, no `message` | yes |
| `PRODUCTION` reader, same tag and same reader within the window | `200` | `uid`, `isCompliant` = existing Record's `compliant`, `processedAt` = existing Record's date, `message: "Duplicate read ignored"` | no |
| Same, but the repeat says `isCompliant: false` and the Record is compliant | `200` | as above, with `isCompliant: false` | no, the existing Record becomes non-compliant |
| `PRODUCTION` reader, same tag within the window but another reader | `200` | as a first read | yes |
| `ENREGISTREMENT` reader | `200` | unchanged from spec `003` | no |

## 3. Compatibility for reader devices

- The response shape is unchanged. `message` was already an optional field. In `PRODUCTION` mode it's only filled in for an ignored duplicate; registration reads keep their messages (spec `003`).
- A reader that sent blank UIDs used to get `200` (`500` in `PRODUCTION` mode, since the exception was unhandled). It now gets `400` in both modes and shouldn't retry.
