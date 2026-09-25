# Contract: `/api/records` routes

Changes to `src/main/resources/openapi/api.yaml`, Record section. One new route and two new schemas. The signatures
of the existing `RecordApiDelegate` methods do not change. The generator adds one method,
`listRecordConformityChanges`.

## 1. New route: `GET /records/{recordId}/conformity-history`

```yaml
  /records/{recordId}/conformity-history:
    get:
      summary: List compliance changes of a record
      description: >
        Every manual compliance change of the record, oldest first. The first change's previousIsCompliant is the
        reader's original verdict. Empty list if the record was never changed. Administrateur only.
      operationId: listRecordConformityChanges
      tags: [Record]
      parameters:
        - in: path
          name: recordId
          required: true
          description: Record identifier
          schema:
            type: string
            format: uuid
      responses:
        "200":
          description: Compliance changes of the record
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ConformityChangesList"
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "500": { $ref: "#/components/responses/InternalError" }
```

## 2. New schemas

```yaml
    ConformityChange:
      type: object
      properties:
        previousIsCompliant:
          type: boolean
          description: Compliance before the change
          example: true
        newIsCompliant:
          type: boolean
          description: Compliance after the change
          example: false
        authorUsername:
          type: string
          description: Username of the user who made the change
          example: operateur1
        changedAt:
          type: string
          format: date-time
          description: Date of the change
          example: "2024-01-15T09:31:12Z"
      required:
        - previousIsCompliant
        - newIsCompliant
        - authorUsername
        - changedAt

    ConformityChangesList:
      type: object
      properties:
        changes:
          type: array
          items:
            $ref: "#/components/schemas/ConformityChange"
      required:
        - changes
```

## 3. Existing route: `PATCH /records/{recordId}/conformity`

Description becomes:

```text
Sets the record's compliance to the given value. If the value differs from the current one, the change is
recorded in the record's compliance history with the logged-in user as author, and duplicate scans no longer
change the record. If it is the current value, nothing is written (idempotent).
```

Add responses (already returned today, declared for completeness):

```yaml
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
```

## 4. Existing route: `GET /records/readers/{readerId}`

Add `401` and `403` responses. No other change.

## 5. Response table

| Route | Caller | Case | Status | Body |
|---|---|---|---|---|
| `PATCH …/conformity` | Administrateur or Opérateur | value differs | `204` | — (record updated, 1 history row) |
| `PATCH …/conformity` | Administrateur or Opérateur | same value as current | `204` | — (nothing written) |
| `PATCH …/conformity` | Administrateur or Opérateur | unknown record | `404` | `ProblemDetail` |
| `PATCH …/conformity` | Administrateur or Opérateur | `isCompliant` missing | `400` | `ProblemDetail` |
| `GET …/conformity-history` | Administrateur | record with n changes | `200` | `{ "changes": [n items, oldest first] }` |
| `GET …/conformity-history` | Administrateur | never changed | `200` | `{ "changes": [] }` |
| `GET …/conformity-history` | Administrateur | unknown record | `404` | `ProblemDetail` |
| `GET …/conformity-history` | Opérateur | any | `403` | — |
| any `/api/records/**` | not logged in | any | `401` | — |

## 6. Access rule (`SecurityConfig`, not in `api.yaml`)

Before the `/api/records/**` rule:

```java
.requestMatchers(path(HttpMethod.GET, "/api/records/*/conformity-history")).hasRole(ADMINISTRATEUR)
```

Spec `008` matrix gets a row: `GET /api/records/{id}/conformity-history` | — | — | ✓ | spec `005`.

## 7. Reader device and front compatibility

- `POST /api/tags/scan` contract is unchanged. A duplicate scan of a record an Opérateur changed returns that
  record's current value with `message: "Duplicate read ignored"`, as it already does for any duplicate.
- `front/reader.html` is unchanged: it keeps sending the opposite of the displayed value.
