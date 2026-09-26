# Contract: `POST /tags/registration-reads`

Changes to `src/main/resources/openapi/api.yaml`. Everything else in the file is unchanged.

## New path

```yaml
  /tags/registration-reads:
    post:
      summary: Send the tags read by a registration reader in one call
      description: >
        Called by a reader in ENREGISTREMENT mode with the list of tags it has read. Each distinct, non-blank uid
        becomes a read of the reader's open registration session, as if sent one by one to /tags/scan; all of them
        or none are kept. No tag, bucket or record is created. Surrounding spaces are removed and blank elements
        ignored. Without an open session (or with an expired one) nothing is kept and the answer is still 200.
        A reader in PRODUCTION mode is refused with 403. More than 100 distinct uids, none at all, or a uid longer
        than 50 characters is refused with 400.
      operationId: recordRegistrationReads
      tags: [Registration]
      security:
        - ReaderApiToken: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/RegistrationReadsRequest"
      responses:
        "200":
          description: Batch processed
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/RegistrationReadsResponse"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403":
          description: The reader is not in ENREGISTREMENT mode
        "409":
          description: >
            Concurrent reads of the same tags, or the reader's session stayed locked too long; nothing was kept,
            the reader may send the batch again
        "500": { $ref: "#/components/responses/InternalError" }
```

## New schemas

```yaml
    RegistrationReadsRequest:
      type: object
      properties:
        uids:
          type: array
          description: >
            Tags read by the reader. Repeats and blank elements are allowed; at most 100 distinct non-blank uids.
          minItems: 1
          maxItems: 1000
          items:
            type: string
            example: E2806915000040287477C993
      required:
        - uids

    RegistrationReadsResponse:
      type: object
      properties:
        sessionOpen:
          type: boolean
          description: False when no registration session was open on the reader; nothing was kept then.
          example: true
        receivedCount:
          type: integer
          description: Distinct non-blank uids in the request.
          example: 12
        addedCount:
          type: integer
          description: Uids newly added to the session (already present ones are not counted). 0 without a session.
          example: 12
        processedAt:
          type: string
          format: date-time
          example: "2026-09-26T09:30:00Z"
        message:
          type: string
          description: '"Registration reads kept", or "Registration reads ignored: no session started".'
          example: Registration reads kept
      required:
        - sessionOpen
        - receivedCount
        - addedCount
        - processedAt
        - message
```

## Updated description

`components.securitySchemes.ReaderApiToken.description`: add `POST /tags/registration-reads` next to
`POST /tags/scan`.

## Examples

```http
POST /api/tags/registration-reads
x-api-token: <token of an ENREGISTREMENT reader>
Content-Type: application/json

{ "uids": ["E2806915000040287477C993", " E2806915000050287477D48C ", "E2806915000040287477C993", ""] }
```

```json
{ "sessionOpen": true, "receivedCount": 2, "addedCount": 2,
  "processedAt": "2026-09-26T09:30:00Z", "message": "Registration reads kept" }
```

Same call from a `PRODUCTION` reader → `403`, `application/problem+json`, detail
"This route is reserved for readers in ENREGISTREMENT mode".
