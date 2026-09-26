# Contract changes: Contrôle des tags par rapport à la liste de référence

Changes to `src/main/resources/openapi/api.yaml`, made before any code (API-first). Decisions: [research](../research.md)
R5, R6.

## Unchanged

- `POST /tags/scan` and `ScanTagRequest` / `ScanTagResponse`: readers change nothing (FR-005, FR-006). An off-list tag
  scanned by a `PRODUCTION` reader creates its Record as today; an `ENREGISTREMENT` reader still gets "read kept".

## New route: `GET /tags/off-list`

```yaml
/tags/off-list:
  get:
    summary: List known tags that are not in the reference list
    description: >
      Tags known to the application (registered on a bucket or created by a scan) whose uid is not in the
      reference tag list shipped with this version. Administrateur only.
    operationId: listOffListTags
    tags: [Tag]
    responses:
      "200":
        content:
          application/json:
            schema: { $ref: "#/components/schemas/OffListTagsList" }
      "401": { $ref: "#/components/responses/Unauthorized" }
      "403": { $ref: "#/components/responses/Forbidden" }
      "500": { $ref: "#/components/responses/InternalError" }
```

Security: already covered by `/api/tags/**` → `ADMINISTRATEUR` in the user chain; no `SecurityConfig` change. Not
reachable with a reader token (kiosk chain allows two routes only).

```yaml
OffListTag:
  type: object
  properties:
    uid:          { type: string, example: E2000017221101891400A23G }
    bucketNumber: { type: integer, nullable: true }
    recordCount:  { type: integer, format: int64 }
    lastRecordAt: { type: string, format: date-time, nullable: true }
    createdAt:    { type: string, format: date-time }
  required: [uid, recordCount, createdAt]

OffListTagsList:
  type: object
  properties:
    referenceListSize: { type: integer, description: Number of uids in the reference list }
    tags:
      type: array
      items: { $ref: "#/components/schemas/OffListTag" }
  required: [referenceListSize, tags]
```

## Changed schemas

| Schema | Change |
|---|---|
| `RegisterTagsRequest` | + `offListConfirmed: boolean, default false` — "Must be true to register tags that are not in the reference list. Otherwise the request is refused with 409 and nothing is saved." |
| `SaveRegistrationSession` | + `offListConfirmed: boolean, default false` (same meaning) |
| `TagsInOtherBuckets` (409 body) | + `offListTags: array of string`, required. `tags` stays required and may be empty. Description: "409 body when the registration needs confirmation: tags that would be moved from another bucket, and/or tags not in the reference list." |
| `RegistrationRead` | + `offList: boolean`, required — "True when the uid is not in the reference list" |
| `RecordSummary` | + `tagOffList: boolean`, required — "True when the record's tag is not in the reference list" |

## Changed descriptions

- `POST /tags/buckets/{bucketNumber}` and `POST /tags/registration-sessions/{sessionId}/save`: add "Tags not in the
  reference list are registered only when offListConfirmed is true"; `409` description becomes "Some tags need
  confirmation (move from another bucket, or not in the reference list)".

## Response table for a registration

| In another bucket (unconfirmed) | Off-list (unconfirmed) | Answer |
|---|---|---|
| none | none | `200`, as today |
| some | none | `409`, `tags` filled, `offListTags` empty (as today plus an empty array) |
| none | some | `409`, `tags` empty, `offListTags` filled |
| some | some | `409`, both filled |

"Unconfirmed" means the matching flag is `false`. The lists in a `409` are always complete (for the front's single
dialog), including the kind already confirmed.

## Error bodies

`409` bodies stay rendered by `ApiExceptionHandler` (handler renamed with the exception, R5); message:
"Some tags need confirmation; resend with moveConfirmed and/or offListConfirmed set to true".
