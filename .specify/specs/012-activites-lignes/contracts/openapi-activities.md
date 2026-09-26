# Contract: activities, line associations, current activity

Changes to `src/main/resources/openapi/api.yaml`. A new OpenAPI tag `Activity` generates `ActivityApiDelegate`,
implemented by a new `controller/ActivityController`. `PUT /readers/{readerId}/activities` belongs to the `Reader` tag
(`ReaderController`). Everything else in the file is unchanged apart from the additions below.

## Access matrix additions (spec `008`)

| Route | Anonymous | Opérateur | Administrateur | Kiosk token (own line) | Kiosk token (other line) |
|---|---|---|---|---|---|
| `GET /activities` | 401 | 200 | 200 | 403 | 403 |
| `POST /activities` | 401 | 403 | 201 | 403 | 403 |
| `PATCH /activities/{id}` | 401 | 403 | 200 | 403 | 403 |
| `DELETE /activities/{id}` | 401 | 403 | 204 | 403 | 403 |
| `PUT /readers/{readerId}/activities` | 401 | 403 | 200 | 403 | 403 |
| `GET /lines/{readerUid}/current-activity` | 401 | 200 | 200 | 200 | 403 |
| `PUT /lines/{readerUid}/current-activity` | 401 | 200 | 200 | 200 | 403 |

Kiosk-token `403`s on the non-kiosk routes come from the kiosk chain's `denyAll()`; the other-line `403` from
`LineActivityService`.

## New paths

```yaml
  # -----------------------------------------------------------------------------------------------
  # Activity section (spec 012)
  # -----------------------------------------------------------------------------------------------
  /activities:
    get:
      summary: List all activities with their lines
      description: Active and disabled activities, sorted by name, each with the ids of the readers (lines) it is associated with.
      operationId: listActivities
      tags: [Activity]
      responses:
        "200":
          description: Activities
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ActivitiesList"
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
    post:
      summary: Create an activity
      operationId: createActivity
      tags: [Activity]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/CreateActivity"
      responses:
        "201":
          description: Created activity, active, associated with no line
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Activity"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "409": { $ref: "#/components/responses/AlreadyExist" }

  /activities/{activityId}:
    parameters:
      - in: path
        name: activityId
        required: true
        schema:
          type: string
          format: uuid
    patch:
      summary: Rename, disable or re-enable an activity
      description: >
        Disabling an activity that is the current activity of some lines needs confirmed=true; otherwise the answer is
        409 naming those lines and nothing changes. Once confirmed, those lines have no current activity.
      operationId: updateActivity
      tags: [Activity]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/UpdateActivity"
      responses:
        "200":
          description: Updated activity
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/Activity"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "409":
          description: >
            Disabling would clear the current activity of lines without confirmation (body LinesLosingActivity).
            A name already used also answers 409, with the usual ProblemDetail body (no readerUids).
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/LinesLosingActivity"
    delete:
      summary: Delete an activity that was never used
      description: Refused with 409 when a record carries it or it was ever a line's current activity; disable it instead.
      operationId: deleteActivity
      tags: [Activity]
      responses:
        "204": { $ref: "#/components/responses/Deleted" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "409": { $ref: "#/components/responses/Conflict" }

  /readers/{readerId}/activities:
    parameters:
      - in: path
        name: readerId
        required: true
        schema:
          type: string
          format: uuid
    put:
      summary: Set the activities a line can run
      description: >
        Replaces the reader's associated activities. Only readers in PRODUCTION mode (400 otherwise). Removing the
        line's current activity needs confirmed=true; otherwise 409 and nothing changes.
      operationId: setReaderActivities
      tags: [Reader]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/SetReaderActivities"
      responses:
        "200":
          description: The line's activities after the change
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/LineActivity"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "409":
          description: The line's current activity would be removed without confirmation
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/LinesLosingActivity"

  /lines/{readerUid}/current-activity:
    parameters:
      - in: path
        name: readerUid
        required: true
        description: Reader uid (the line)
        schema:
          type: string
    get:
      summary: Current activity of a line
      description: >
        Opérateur or Administrateur session, or the token of this reader (line kiosk); another reader's token gets 403.
        An activity chosen before today (station time zone) is reported as none.
      operationId: getLineActivity
      tags: [Activity]
      security:
        - SessionCookie: []
        - ReaderApiToken: []
      responses:
        "200":
          description: Current and available activities of the line
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/LineActivity"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
    put:
      summary: Choose the current activity of a line
      description: >
        activityId null clears it. The activity must be associated with the line and active (400 otherwise); the
        reader must be in PRODUCTION mode. Records scanned from now on carry it, until the next change or midnight
        (station time). A change is logged with the user or the kiosk's reader as author; choosing the current value
        again writes nothing.
      operationId: setLineActivity
      tags: [Activity]
      security:
        - SessionCookie: []
        - ReaderApiToken: []
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/SetLineActivity"
      responses:
        "200":
          description: Current and available activities of the line after the change
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/LineActivity"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
```

## New schemas

```yaml
    CreateActivity:
      type: object
      properties:
        name:
          type: string
          description: Trimmed; not blank; unique ignoring case and surrounding spaces.
          maxLength: 100
          example: Fraise gariguette
      required: [name]

    UpdateActivity:
      type: object
      description: At least one of name and active.
      properties:
        name:
          type: string
          maxLength: 100
        active:
          type: boolean
        confirmed:
          type: boolean
          default: false
          description: Confirms clearing the current activity of the lines named in a previous 409.

    ActivityRef:
      type: object
      properties:
        id: { type: string, format: uuid }
        name: { type: string }
      required: [id, name]

    Activity:
      allOf:
        - $ref: "#/components/schemas/ActivityRef"
        - type: object
          required: [active, readerIds]
          properties:
            active:
              type: boolean
            readerIds:
              type: array
              description: Readers (lines) the activity is associated with
              items: { type: string, format: uuid }
            creationDate: { type: string, format: date-time }
            updateDate: { type: string, format: date-time }

    ActivitiesList:
      type: object
      properties:
        activities:
          type: array
          items:
            $ref: "#/components/schemas/Activity"
      required: [activities]

    SetReaderActivities:
      type: object
      properties:
        activityIds:
          type: array
          uniqueItems: true
          items: { type: string, format: uuid }
        confirmed:
          type: boolean
          default: false
      required: [activityIds]

    LinesLosingActivity:
      type: object
      description: 409 body when a change would clear the current activity of lines without confirmation
      properties:
        message: { type: string }
        readerUids:
          type: array
          items: { type: string }
      required: [message, readerUids]

    SetLineActivity:
      type: object
      properties:
        activityId:
          type: string
          format: uuid
          nullable: true
          description: The activity to make current; null or absent for none
      # Not required: the generator would add @NotNull and refuse null (found during implementation, T002).

    LineActivity:
      type: object
      properties:
        readerUid: { type: string }
        currentActivity:
          allOf:
            - $ref: "#/components/schemas/ActivityRef"
          nullable: true
        setAt:
          type: string
          format: date-time
          nullable: true
        availableActivities:
          type: array
          description: Associated and active activities, sorted by name
          items:
            $ref: "#/components/schemas/ActivityRef"
        recordsWithoutActivityToday:
          type: integer
          minimum: 0
          description: Records of this line since midnight (station time) without an activity
      required: [readerUid, currentActivity, availableActivities, recordsWithoutActivityToday]
```

## Changed schemas and descriptions

- `RecordSummary` gains two optional, nullable properties (FR-017):

  ```yaml
        activityId:
          type: string
          format: uuid
          nullable: true
          description: Activity current on the line when the record was scanned
        activityName:
          type: string
          nullable: true
  ```

- `ReaderApiToken.description`: add `GET/PUT /lines/{its uid}/current-activity` to the kiosk routes.
- `POST /tags/scan`: request and response unchanged (FR-016); its description gains one sentence: "The record carries
  the line's current activity, if any."
