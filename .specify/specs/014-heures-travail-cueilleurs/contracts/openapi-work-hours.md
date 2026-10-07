# Contract: work hours and per-picker activity columns

Changes to `src/main/resources/openapi/api.yaml`. A new OpenAPI tag `WorkHours` generates `WorkHoursApiDelegate`,
implemented by a new `controller/WorkHoursController`. `GET /records/stats` (tag `Record`, `RecordController`) keeps
its parameters and gains response fields only, so the current dashboard keeps working until the page is updated.

## Access matrix additions (spec `008`)

| Route | Anonymous | Opérateur | Administrateur | Kiosk token |
|---|---|---|---|---|
| `GET /work-hours` | 401 | 403 | 200 | 403 |
| `PUT /work-hours` | 401 | 403 | 200 | 403 |
| `GET /records/stats` (unchanged) | 401 | 200 | 200 | 403 |

Kiosk-token `403` comes from the kiosk chain's `denyAll()`; `PUT` needs the `X-XSRF-TOKEN` header like every
user-chain write.

## New paths

```yaml
  # -----------------------------------------------------------------------------------------------
  # Work hours section (spec 014)
  # -----------------------------------------------------------------------------------------------
  /work-hours:
    get:
      summary: Work hours of every picker for one day
      description: >
        Every picker, by lastname then firstname, with the hours entered for the day (null when none), the number of
        records attributed to the picker that day on all readers, and missingHours when there are records but no
        hours. The day is a day of the station time zone; today by default, a future day is refused. Administrateur.
      operationId: getWorkDay
      tags: [WorkHours]
      parameters:
        - in: query
          name: day
          required: false
          schema:
            type: string
            format: date
      responses:
        "200":
          description: The day's hours
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/WorkDay"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "500": { $ref: "#/components/responses/InternalError" }
    put:
      summary: Set the work hours of several pickers for one day
      description: >
        For each entry, sets the picker's hours for the day (replacing the previous value) or, with hours null,
        removes them. Pickers not listed are unchanged. All or nothing: an invalid entry (hours not a multiple of
        0.25 between 0.25 and 24, a picker listed twice) answers 400, an unknown picker 404, and nothing is saved.
        A day after today in the station time zone is refused (400). The session's user and the current time are
        stored as the last change of each written entry. Administrateur.
      operationId: saveWorkDay
      tags: [WorkHours]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/SaveWorkDayRequest"
      responses:
        "200":
          description: The day after the change, as returned by GET
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/WorkDay"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "409": { $ref: "#/components/responses/Conflict" }
        "500": { $ref: "#/components/responses/InternalError" }
```

`409` only when a concurrent save of the same picker and day still collides after one retry (research R3).

## New schemas

```yaml
    WorkDay:
      type: object
      required: [day, today, pickers]
      properties:
        day:
          type: string
          format: date
        today:
          type: boolean
          description: The day is today in the station time zone
        pickers:
          type: array
          items:
            $ref: "#/components/schemas/WorkDayPicker"

    WorkDayPicker:
      type: object
      required: [pickerId, firstname, lastname, records, missingHours]
      properties:
        pickerId:
          type: string
          format: uuid
        firstname:
          type: string
        lastname:
          type: string
        hours:
          type: number
          format: double
          nullable: true
          description: Hours entered for the day, a multiple of 0.25; null when none
        records:
          type: integer
          format: int64
          minimum: 0
          description: Records attributed to the picker that day, all readers
        missingHours:
          type: boolean
          description: records > 0 and hours is null
        updatedAt:
          type: string
          format: date-time
          nullable: true
        updatedBy:
          type: string
          nullable: true
          description: Username of the Administrateur who last changed the hours

    SaveWorkDayRequest:
      type: object
      required: [day, entries]
      properties:
        day:
          type: string
          format: date
        entries:
          type: array
          minItems: 1
          maxItems: 500
          items:
            $ref: "#/components/schemas/WorkHoursEntry"

    WorkHoursEntry:
      type: object
      required: [pickerId]
      properties:
        pickerId:
          type: string
          format: uuid
        hours:
          type: number
          format: double
          nullable: true
          minimum: 0.25
          maximum: 24
          multipleOf: 0.25
          description: Null removes the picker's hours for the day

    ActivityColumn:
      type: object
      properties:
        activityId:
          type: string
          format: uuid
          nullable: true
          description: Null for the records without activity ("Sans activité")
        name:
          type: string
          nullable: true

    ActivityCount:
      type: object
      required: [total]
      properties:
        activityId:
          type: string
          format: uuid
          nullable: true
        total:
          type: integer
          format: int64
          minimum: 1
```

Bean validation is on for the generated delegates (`@Valid`), so `minimum`, `maximum`, `minItems` and `maxItems` are
rejected by Spring with a `400` before the service runs. The service checks every rule again (including the quarter
hour, as `multipleOf` on a `double` is not relied on) and is the only source of the `400` for a duplicated picker or
an off-quarter value; both paths answer `400`, with different messages.

## Changed schemas

```yaml
    PickerStats:
      allOf:
        - $ref: "#/components/schemas/RecordCounts"
        - type: object
          required: [activities]          # added
          properties:
            pickerId: …                   # unchanged
            firstname: …
            lastname: …
            activities:                   # added
              type: array
              description: Records of the picker per activity, non-zero entries only; they add up to total.
              items:
                $ref: "#/components/schemas/ActivityCount"
            workHours:                    # added
              type: number
              format: double
              nullable: true
              description: >
                Sum of the picker's hours over the period. Null when none were entered, for the null-picker row,
                and whenever readerId is set (hours are not per line).

    RecordStats:
      required: [from, to, timeZone, summary, pickers, hours, activities, includesToday]   # 2 added
      properties:
        …                                 # unchanged
        pickers:
          description: >
            Pickers with at least one record and, when readerId is not set, pickers with hours in the period
            (zero counts); by lastname then firstname; the null-picker row last.
        activities:                       # added
          type: array
          description: >
            The activities carried by at least one record of the period (and reader), by name, then a null entry
            when some records carry no activity.
          items:
            $ref: "#/components/schemas/ActivityColumn"
        workHours:                        # added
          type: number
          format: double
          nullable: true
          description: Sum of the hours of all pickers over the period; null when readerId is set.
        includesToday:                    # added
          type: boolean
          description: The period contains today in the station time zone.
```

`GET /records/stats` description gains: "Each picker row's activities add up to its total. Hours are the hours
entered by the Administrateur (spec 014), summed over the period, and only given without a reader."

## Error cases

| Case | Status |
|---|---|
| `day` after today (station zone), GET or PUT | 400 |
| `hours` < 0.25, > 24, or not a multiple of 0.25 | 400 |
| Same `pickerId` twice in `entries` | 400 |
| Empty `entries` or more than 500 | 400 |
| Unknown `pickerId` | 404 |
| Missing or wrong CSRF header on PUT | 403 |
