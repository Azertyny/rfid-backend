# Contract: dashboard statistics (007)

Changes to `src/main/resources/openapi/api.yaml`, made before any Java code (API-first). The generator adds
`getRecordStats` to `RecordApiDelegate` and the models below; `RecordController` implements it by delegating to
`RecordStatsService`.

## New path (Record section, placed before `/records/readers/{readerId}`)

```yaml
  /records/stats:
    get:
      summary: Scan statistics for the dashboard
      description: >
        Totals of the records created in a period, computed by the server: summary, one row per picker (records
        without a picker form one row with a null pickerId, last) and one entry per hour of day. Days and hours
        are in the station time zone (app.station.time-zone). Conformity is the current value of each record,
        after manual corrections. The picker rows, and the hour entries, each add up to the summary.
        Administrateur and Opérateur.
      operationId: getRecordStats
      tags: [Record]
      parameters:
        - in: query
          name: period
          required: false
          schema:
            $ref: "#/components/schemas/StatsPeriod"
        - in: query
          name: from
          required: false
          description: First day, inclusive. Required with CUSTOM, refused otherwise.
          schema: { type: string, format: date }
        - in: query
          name: to
          required: false
          description: Last day, inclusive. Required with CUSTOM, refused otherwise. At most 31 days from `from`.
          schema: { type: string, format: date }
        - in: query
          name: readerId
          required: false
          description: Only the records of this reader.
          schema: { type: string, format: uuid }
      responses:
        "200":
          description: Statistics of the period
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/RecordStats"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "500": { $ref: "#/components/responses/InternalError" }
```

## New schemas

```yaml
    StatsPeriod:
      type: string
      enum: [TODAY, YESTERDAY, LAST_7_DAYS, CUSTOM]
      default: TODAY
      description: LAST_7_DAYS is today and the 6 days before, in the station time zone.

    RecordCounts:
      type: object
      required: [total, nonCompliant]
      properties:
        total:        { type: integer, format: int64, minimum: 0 }
        nonCompliant: { type: integer, format: int64, minimum: 0 }

    PickerStats:
      allOf:
        - $ref: "#/components/schemas/RecordCounts"
        - type: object
          properties:
            pickerId:  { type: string, format: uuid, nullable: true, description: Null for records without a picker }
            firstname: { type: string, nullable: true }
            lastname:  { type: string, nullable: true }

    HourStats:
      allOf:
        - $ref: "#/components/schemas/RecordCounts"
        - type: object
          required: [hour]
          properties:
            hour: { type: integer, minimum: 0, maximum: 23, description: Hour of day in the station time zone }

    RecordStats:
      type: object
      required: [from, to, timeZone, summary, pickers, hours]
      properties:
        from:     { type: string, format: date, description: Resolved first day, inclusive }
        to:       { type: string, format: date, description: Resolved last day, inclusive }
        timeZone: { type: string, example: Europe/Paris }
        readerId: { type: string, format: uuid, nullable: true }
        summary:  { $ref: "#/components/schemas/RecordCounts" }
        pickers:
          type: array
          description: Pickers with at least one record, by lastname then firstname; the null-picker row last.
          items: { $ref: "#/components/schemas/PickerStats" }
        hours:
          type: array
          description: Always 24 entries, hour 0 to 23, summed over the days of the period.
          items: { $ref: "#/components/schemas/HourStats" }
```

## Invariants (checked by `RecordStatsApiTest`)

- `sum(pickers[].total) == summary.total` and `sum(pickers[].nonCompliant) == summary.nonCompliant`.
- `sum(hours[].total) == summary.total`, same for `nonCompliant`; `hours.length == 24`.
- `nonCompliant <= total` everywhere; no entry in `pickers` has `total == 0`.
- At most one entry in `pickers` has `pickerId == null`, and it is the last one.

## Errors

| Case | Status |
|---|---|
| `period=CUSTOM` without `from` or `to` | 400 |
| `from` after `to` | 400 |
| `to − from + 1 > 31` days | 400 |
| `from` or `to` with `TODAY`, `YESTERDAY` or `LAST_7_DAYS` | 400 |
| Unknown `period` value, badly formatted date or UUID | 400 |
| `readerId` of no reader | 404 |
| Not logged in | 401 |
| Logged in without Administrateur or Opérateur role | 403 (no such user today) |

## Security

Covered by the existing rule `/api/records/**` → Administrateur, Opérateur (`SecurityConfig.java:128`). Add the route to
`AccessMatrixSecurityTest` as `LOGGED_IN`.

## Example

`GET /api/records/stats?period=CUSTOM&from=2026-09-19&to=2026-09-25`

```json
{
  "from": "2026-09-19", "to": "2026-09-25", "timeZone": "Europe/Paris", "readerId": null,
  "summary": { "total": 1240, "nonCompliant": 31 },
  "pickers": [
    { "pickerId": "7c9e…", "firstname": "Amadou", "lastname": "Diallo", "total": 402, "nonCompliant": 6 },
    { "pickerId": "1f2a…", "firstname": "Valérie", "lastname": "Moreau", "total": 780, "nonCompliant": 22 },
    { "pickerId": null, "firstname": null, "lastname": null, "total": 58, "nonCompliant": 3 }
  ],
  "hours": [ { "hour": 0, "total": 0, "nonCompliant": 0 }, "…", { "hour": 7, "total": 96, "nonCompliant": 2 }, "…" ]
}
```

## Front contract (`front/index.html`)

- Calls only `GET /api/readers` (reader selector) and `GET /api/records/stats`, through `apiFetch`. No call to
  `/api/tags` or `/api/pickers`, no token, no hardcoded URL.
- Query built from the filter bar: `period`, plus `from`/`to` for `CUSTOM`, plus `readerId` unless "Tous les lecteurs".
- Displays `from`, `to` and `timeZone` from the response, not dates computed by the browser.
- CSV export as described in [research.md](../research.md) R10.
