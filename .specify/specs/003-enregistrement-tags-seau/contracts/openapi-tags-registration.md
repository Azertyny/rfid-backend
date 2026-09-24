# Contract changes: tag registration

Changes to make in `src/main/resources/openapi/api.yaml` before any code. Afterwards:

- `TagApiDelegate.registerTagsForBucket` keeps its signature. Only the request and response models gain fields.
- `ReaderApiDelegate` is unchanged. `UpdateReader` and `Reader` gain `mode`.
- A new OpenAPI tag `Registration` generates `RegistrationApiDelegate` with four methods: `startRegistrationSession`, `getRegistrationSession`, `cancelRegistrationSession`, `saveRegistrationSession`.

## 1. `components.schemas`

```yaml
    ReaderMode:
      type: string
      enum: [PRODUCTION, ENREGISTREMENT]
      description: >
        PRODUCTION: scans create compliance records. ENREGISTREMENT: scans become temporary
        registration reads for the tag-registration page, and no record is created.

    UpdateReader:
      type: object
      description: At least one field must be present.
      properties:
        active:
          type: boolean
        mode:
          $ref: "#/components/schemas/ReaderMode"

    Reader:            # add to the second allOf member
      properties:
        mode:
          $ref: "#/components/schemas/ReaderMode"
      required:
        - id
        - active
        - mode

    RegisterTagsRequest:
      type: object
      properties:
        uids:
          # unchanged, plus:
          maxItems: 100   # FR-011: more than 100 uids → 400
        moveConfirmed:
          type: boolean
          default: false
          description: >
            Must be true to move tags that currently belong to another bucket.
            Otherwise the request is refused with 409 and nothing is saved.
      required:
        - uids

    RegisterTagsResponse:
      type: object
      properties:
        bucketNumber:
          type: integer
          example: 12
        registeredCount:
          type: integer
          description: Number of unique, non-blank tag uids in this request
          example: 4
        totalCount:
          type: integer
          description: Number of tags linked to the bucket after this registration
          example: 6
      required:
        - bucketNumber
        - registeredCount
        - totalCount

    TagInOtherBucket:
      type: object
      properties:
        uid:
          type: string
        bucketNumber:
          type: integer
      required: [uid, bucketNumber]

    TagsInOtherBuckets:
      type: object
      description: 409 body when tags would be moved from another bucket without confirmation
      properties:
        message:
          type: string
        tags:
          type: array
          items:
            $ref: "#/components/schemas/TagInOtherBucket"
      required: [message, tags]

    StartRegistrationSession:
      type: object
      properties:
        readerId:
          type: string
          format: uuid
      required: [readerId]

    RegistrationRead:
      type: object
      properties:
        uid:
          type: string
        firstReadAt:
          type: string
          format: date-time
        bucketNumber:
          type: integer
          nullable: true
          description: Bucket the tag currently belongs to, or null if none
      required: [uid, firstReadAt]

    RegistrationSession:
      type: object
      properties:
        id:
          type: string
          format: uuid
        readerId:
          type: string
          format: uuid
        readerUid:
          type: string
        startedBy:
          type: string
          description: Username of the Administrateur who started the session
        startedAt:
          type: string
          format: date-time
        reads:
          type: array
          description: Tags read since Start, one entry per tag, oldest first
          items:
            $ref: "#/components/schemas/RegistrationRead"
      required: [id, readerId, readerUid, startedBy, startedAt, reads]

    ReaderBusy:
      type: object
      description: >
        409 body when the reader already has an open session. startedBy and startedAt are absent in the
        rare case where the reader stayed contended but its session could not be read back (T036).
      properties:
        message:
          type: string
        startedBy:
          type: string
        startedAt:
          type: string
          format: date-time
      required: [message]

    SaveRegistrationSession:
      type: object
      properties:
        bucketNumber:
          type: integer
          minimum: 1
        moveConfirmed:
          type: boolean
          default: false
      required: [bucketNumber]
```

## 2. `paths`

```yaml
  /tags/buckets/{bucketNumber}:
    post:
      description: >
        Add the provided tags to the bucket (created if unknown). Tags already linked to this
        bucket stay linked. Tags linked to another bucket are moved only when moveConfirmed is true.
      responses:
        "200": RegisterTagsResponse
        "400": InvalidRequest          # no usable uid
        "409": TagsInOtherBuckets      # new
        # 401/403 from the security chain

  /tags/registration-sessions:
    post:
      operationId: startRegistrationSession
      tags: [Registration]
      requestBody: StartRegistrationSession
      responses:
        "201": RegistrationSession     # reads: []
        "400": InvalidRequest          # reader disabled or not in ENREGISTREMENT mode
        "404": NotFound                # unknown reader
        "409": ReaderBusy

  /tags/registration-sessions/{sessionId}:
    get:
      operationId: getRegistrationSession
      tags: [Registration]
      responses:
        "200": RegistrationSession
        "403": Forbidden               # started by someone else
        "404": NotFound                # unknown, closed or expired
    delete:
      operationId: cancelRegistrationSession
      tags: [Registration]
      responses:
        "204": Deleted
        "403": Forbidden
        "404": NotFound

  /tags/registration-sessions/{sessionId}/save:
    post:
      operationId: saveRegistrationSession
      tags: [Registration]
      requestBody: SaveRegistrationSession
      responses:
        "200": RegisterTagsResponse    # session is closed
        "400": InvalidRequest          # no read in the session
        "403": Forbidden
        "404": NotFound
        "409": TagsInOtherBuckets      # session stays open
```

`POST /tags/scan` keeps its contract. For an `ENREGISTREMENT` reader, `isCompliant` is always `true` and `message` says the read was kept or ignored (research R2). Update the endpoint description to say so.

## 3. Status codes and access

Every route above falls under `/api/tags/**` → Administrateur only (anonymous `401`, Opérateur `403`), plus CSRF on writes. `SecurityConfig` does not change.

| Call | Case | Status |
|---|---|---|
| `PATCH /readers/{id}` `{}` | no field | `400` |
| `PATCH /readers/{id}` `{"mode":"ENREGISTREMENT"}` | | `200`, `mode` updated |
| `POST /tags/scan` from an `ENREGISTREMENT` reader | session open | `200`, read stored once, no `Record` |
| `POST /tags/scan` from an `ENREGISTREMENT` reader | no session | `200`, read dropped, no `Record` |
| `POST /tags/registration-sessions` | reader `PRODUCTION` or disabled | `400` |
| `POST /tags/registration-sessions` | reader busy, session still alive | `409` `ReaderBusy` |
| `POST /tags/registration-sessions` | reader busy, session expired | `201` (old one deleted) |
| `GET /tags/registration-sessions/{id}` | other Administrateur | `403` |
| `GET /tags/registration-sessions/{id}` | expired | `404` |
| `POST …/save` | no read | `400` |
| `POST …/save` | tag in another bucket, `moveConfirmed` false | `409`, nothing saved, session kept |
| `POST …/save` | same, `moveConfirmed` true | `200`, tag moved, session deleted |
| `POST /tags/buckets/{n}` | only blank uids | `400` |
| `POST /tags/buckets/{n}` | more than 100 uids (FR-011) | `400`, nothing written |
| `POST …/save` | more than 100 reads (FR-011) | `400`, nothing written, session kept |
| `POST /tags/buckets/{n}` | tag in another bucket, not confirmed | `409` |

## 4. Front contract (`front/tags.html`, new)

- Guard: `requireRole('ADMINISTRATEUR')`. Nav link "Tags" with `data-admin-only`, added to every page.
- Reader choice: `GET /readers`, keep `active && mode === 'ENREGISTREMENT'`. None → message plus link to `readers.html`, Start disabled. Exactly one → preselected.
- Start: `POST /tags/registration-sessions`. On `409`, show "Lecteur utilisé par {startedBy} depuis {startedAt}". Without `startedBy`, show "Lecteur occupé, réessayez dans un instant."
- While open: poll `GET /tags/registration-sessions/{id}` every `CONFIG.POLLING_INTERVAL`, with `silent: true`. Show the reads with a warning badge "Seau {bucketNumber}" when `bucketNumber` is set and differs from the typed bucket number. On `404`, show "Session expirée" and go back to the idle state.
- Save: `POST …/save` with `moveConfirmed: false`. On `409`, list the tags and their buckets in a confirmation dialog; on confirm, resend with `moveConfirmed: true`. On `200`, show `registeredCount` / `totalCount` and go back to idle.
- Cancel: `DELETE …/{id}`. On `pagehide`, the page also sends a best-effort `fetch(..., {method: 'DELETE', keepalive: true})` with the CSRF header. The timeout covers the cases where it does not arrive.
- `front/readers.html`: a Mode column, and a switch button that calls `PATCH /readers/{id}` `{mode}`.
- `front/index.html`: when building production lines, skip readers whose `mode` is `ENREGISTREMENT`.
