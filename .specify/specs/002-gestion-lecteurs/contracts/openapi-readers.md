# Contract changes: `/api/readers`

Changes to make in `src/main/resources/openapi/api.yaml`. After them, `ReaderApiDelegate` gains two methods (`updateReader`, `rotateReaderToken`). The two existing signatures stay the same.

## 1. `components.schemas`

```yaml
    CreateReader:
      type: object
      properties:
        uid:
          type: string
          description: Reader uid. Trimmed; must not be blank; unique ignoring case.
          example: Reader 1
          maxLength: 50
      required:
        - uid

    Reader:
      allOf:
        - $ref: "#/components/schemas/CreateReader"
        - type: object
          properties:
            id:
              type: string
              format: uuid
            active:
              type: boolean
              description: False when the reader is disabled; its token is then refused on /tags/scan.
            apitoken:
              # unchanged: only returned to Administrateurs
            creationDate:
              # unchanged
            updateDate:
              # unchanged
          required:
            - id
            - active

    UpdateReader:
      type: object
      description: At least one field must be present.
      properties:
        active:
          type: boolean
```

## 2. `paths`

`/readers`: `POST` adds `"409": { $ref: "#/components/responses/AlreadyExist" }`. Both operations add `401`/`403`, as the `/users` routes do.

New:

```yaml
  /readers/{readerId}:
    parameters:
      - in: path
        name: readerId
        required: true
        schema:
          type: string
          format: uuid
    patch:
      summary: Enable or disable a reader
      operationId: updateReader
      tags: [Reader]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/UpdateReader"
      responses:
        "200":
          description: Updated reader
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Reader"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }

  /readers/{readerId}/token:
    parameters:
      - in: path
        name: readerId
        required: true
        schema:
          type: string
          format: uuid
    post:
      summary: Generate a new API token for a reader
      description: The previous token is refused on /tags/scan from the next request on.
      operationId: rotateReaderToken
      tags: [Reader]
      responses:
        "200":
          description: Reader with its new apitoken
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Reader"
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
```

`/tags/scan`: add to the `401` description "or the reader is disabled".

## 3. Status codes by route and caller

| Route | Anonymous | Opérateur | Administrateur |
|---|---|---|---|
| `GET /api/readers` | 401 | 200, no `apitoken` | 200 with `apitoken` |
| `POST /api/readers` | 401 | 403 | 201 / 400 (blank, > 50) / 409 (duplicate ignoring case) |
| `PATCH /api/readers/{id}` | 401 | 403 | 200 / 400 (empty body, bad UUID) / 404 |
| `POST /api/readers/{id}/token` | 401 | 403 | 200 / 404 |
| `POST /api/tags/scan` (`x-api-token`) | — | — | 200 with an active reader's current token; 401 with an old token, an unknown one, or a disabled reader's |

State-changing calls need the CSRF header (`front/auth.js` `apiFetch` already sends it). No `SecurityConfig` change: `/api/readers/**` is already Administrateur-only.

## 4. Front contract

- `readers.html` reads `id` and `active`, and calls the two new routes with `apiFetch` (research R7).
- `index.html` skips readers with `active === false`. `reader.html` shows them with a label.

## 5. Amendment 2026-10-06: deleting a reader (FR-008)

Add to `/readers/{readerId}` (after `patch`). `ReaderApiDelegate` gains `deleteReader`.

```yaml
    delete:
      summary: Delete a deactivated reader
      description: >
        Soft delete: the reader no longer appears in GET /readers nor as a line, and every route naming it answers
        404. Its records, conformity changes and activity changes are kept, and its uid stays taken. The line is
        removed from its activities, its current activity is cleared and an open registration session is discarded.
        Refused with 409 while the reader is active: deactivate it first.
      operationId: deleteReader
      tags: [Reader]
      responses:
        "204": { $ref: "#/components/responses/Deleted" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "409": { $ref: "#/components/responses/Conflict" }
```

Descriptions to update in `api.yaml`: `GET /readers` ("deleted readers are not listed"), the `404` of `PATCH /readers/{readerId}`, `POST /readers/{readerId}/token`, `PUT /readers/{readerId}/activities`, `POST /tags/registration-sessions` and `GET`/`PUT /lines/{readerUid}/current-activity` ("unknown or deleted reader"), and `POST /readers` `409` ("also when a deleted reader had this uid").

| Route | Anonymous | Opérateur | Administrateur |
|---|---|---|---|
| `DELETE /api/readers/{id}` | 401 | 403 | 204 (deactivated) / 409 (active) / 404 (unknown, already deleted) / 400 (bad UUID) |
| `GET /api/readers` | 401 | 200, no deleted reader | 200, no deleted reader |
| any other route naming a deleted reader by `id`, and `/lines/{uid}/current-activity` | 401 | 403 or 404 | 404 |
| `POST /api/readers` with a deleted reader's `uid` (ignoring case) | 401 | 403 | 409 |
| `GET /api/records/readers/{uid}`, `GET /api/records/stats?readerId=` for a deleted reader | 401 | 200 | 200 |
| token routes with a deleted reader's token | 401 | — | — |

Front: `readers.html` shows "Supprimer" on deactivated rows only and calls `DELETE /readers/{id}` with `apiFetch` (research R14).
