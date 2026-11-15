# Contract: record status and scan settings

Changes to `src/main/resources/openapi/api.yaml`. A new OpenAPI tag `ScanSettings` generates
`ScanSettingsApiDelegate`, implemented by a new `controller/ScanSettingsController`. One new operation joins tag `Record` (`RecordController`). The other changes add fields to existing schemas or
widen a description: no existing parameter, path or required request field changes, so the readers' firmware and a
cached front page keep working.

## Access matrix (spec `008`)

| Route | Anonymous | Opérateur | Administrateur | Kiosk token |
|---|---|---|---|---|
| `GET /scan-settings` (new) | 401 | 403 | 200 | 403 |
| `PUT /scan-settings` (new) | 401 | 403 | 200 | 403 |
| `PATCH /records/{id}/conformity`, record without anomaly (unchanged) | 401 | 204 | 204 | 204, own reader only |
| `PATCH /records/{id}/conformity`, record Doublon / perdu or Cheat | 401 | **403** | 204 | **403** |
| `GET /records/anomalies` (new) | 401 | 403 | 200 | 403 |
| `GET /records/readers/{uid}`, `GET /records/stats`, `GET /records/{id}/conformity-history` | unchanged | | | |
| `POST /tags/scan` (reader token) | unchanged | | | |

The kiosk token's `403` on `/scan-settings` and `/records/anomalies` comes from the kiosk chain's `denyAll()`. In
the user chain the `/api/records/anomalies` rule (Administrateur) must come before the generic `/api/records/**`
rule (Administrateur and Opérateur), like the conformity-history rule. The two `403` on an anomaly
record come from `RecordService`, after the route rule let the caller in (research R8). `PUT` needs the
`X-XSRF-TOKEN` header like every user-chain write.

## New paths

```yaml
  # -----------------------------------------------------------------------------------------------
  # Scan settings section (spec 015)
  # -----------------------------------------------------------------------------------------------
  /scan-settings:
    get:
      summary: The two scan delays an Administrateur can change
      description: >
        The delay during which a counted tag read again is ignored, and the gap under which two tags of one bucket
        read by one reader are a Cheat. A value never changed is the installation default, with no author and no
        date. Administrateur.
      operationId: getScanSettings
      tags: [ScanSettings]
      responses:
        "200":
          description: The settings in force
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ScanSettings"
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "500": { $ref: "#/components/responses/InternalError" }
    put:
      summary: Change the scan delays
      description: >
        Both values are sent. A value different from the one in force is stored with the session's user and the
        current time and applies from the next scan; a value equal to the one in force keeps its author and date.
        Records already stored are never re-evaluated. 0 disables the rule. Out-of-range or non-integer values
        answer 400 and nothing is saved. Administrateur.
      operationId: updateScanSettings
      tags: [ScanSettings]
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: "#/components/schemas/UpdateScanSettings"
      responses:
        "200":
          description: The settings after the change, as returned by GET
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/ScanSettings"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "500": { $ref: "#/components/responses/InternalError" }
```

```yaml
  /records/anomalies:
    get:
      summary: Records with status DOUBLON_PERDU or CHEAT in a period
      description: >
        The records of the period (and reader) whose status is DOUBLON_PERDU or CHEAT, newest first, paged. Same
        period parameters, resolution and 31-day limit as GET /records/stats. A record whose anomaly was lifted is
        no longer listed. Administrateur.
      operationId: listRecordAnomalies
      tags: [Record]
      parameters:
        # period, from, to, readerId: identical to GET /records/stats
        - $ref: "#/components/parameters/PageNumber"
        - $ref: "#/components/parameters/PageSize"
      responses:
        "200":
          description: One page of anomaly records
          content:
            application/json:
              schema:
                $ref: "#/components/schemas/AnomalyRecordsPage"
        "400": { $ref: "#/components/responses/InvalidRequest" }
        "401": { $ref: "#/components/responses/Unauthorized" }
        "403": { $ref: "#/components/responses/Forbidden" }
        "404": { $ref: "#/components/responses/NotFound" }
        "500": { $ref: "#/components/responses/InternalError" }
```

## New schemas

Named `AnomalyRecord` rather than `RecordAnomaly`, which is the entity enum (`entity/RecordAnomaly`).

```yaml
    AnomalyRecord:
      type: object
      required: [id, status, creationDate, tagUid, readerUid]
      properties:
        id: { type: string, format: uuid }
        status:
          $ref: "#/components/schemas/RecordStatus"   # DOUBLON_PERDU or CHEAT only
        creationDate: { type: string, format: date-time }
        tagUid: { type: string }
        readerUid: { type: string, description: Name of the line's reader }
        activityName: { type: string, nullable: true }
        pickerId: { type: string, format: uuid, nullable: true, description: Null for DOUBLON_PERDU }
        pickerFirstname: { type: string, nullable: true }
        pickerLastname: { type: string, nullable: true }

    AnomalyRecordsPage:
      type: object
      required: [content, metadata]
      properties:
        content:
          type: array
          items:
            $ref: "#/components/schemas/AnomalyRecord"
        metadata:
          $ref: "#/components/schemas/PageMetadata"
```

```yaml
    RecordStatus:
      type: string
      enum: [CONFORME, NON_CONFORME, DOUBLON_PERDU, CHEAT]
      description: >
        CONFORME and NON_CONFORME come from the reader's verdict or a manual change. DOUBLON_PERDU (a tag already
        counted and not registered into a bucket since, read again) and CHEAT (two tags of one bucket read together) are only set by the
        system; isCompliant is false for both.

    ScanSetting:
      type: object
      required: [value]
      properties:
        value:
          type: integer
          minimum: 0
        updatedAt:
          type: string
          format: date-time
          nullable: true
          description: Null while the installation default applies
        updatedBy:
          type: string
          nullable: true
          description: Username of the Administrateur who last changed it

    ScanSettings:
      type: object
      required: [rereadWindowMinutes, cheatWindowSeconds]
      properties:
        rereadWindowMinutes:
          $ref: "#/components/schemas/ScanSetting"
        cheatWindowSeconds:
          $ref: "#/components/schemas/ScanSetting"

    UpdateScanSettings:
      type: object
      required: [rereadWindowMinutes, cheatWindowSeconds]
      properties:
        rereadWindowMinutes:
          type: integer
          minimum: 0
          maximum: 1440
          example: 30
        cheatWindowSeconds:
          type: integer
          minimum: 0
          maximum: 60
          example: 2
```

## Changed schemas

| Schema | Change |
|---|---|
| `RecordSummary` | + `status` (`RecordStatus`, required). `isCompliant` stays and equals `status == CONFORME` |
| `RecordCounts` | + `cheats` (int64, ≥ 0, required): records of the count with status CHEAT, included in `nonCompliant` and in `total`. Inherited by `PickerStats` and `HourStats`. `total` and `nonCompliant` no longer include DOUBLON_PERDU records |
| `RecordStats` | + `duplicates` (int64, ≥ 0, required): DOUBLON_PERDU records of the period and reader, counted apart from `summary` |
| `ConformityChange` | + `previousStatus`, + `newStatus` (`RecordStatus`, required). `previousIsCompliant` / `newIsCompliant` stay |
| `ScanTagResponse` | `message` description: + `"Recent read ignored"` (a counted tag read again inside the re-read delay, no record). `"Tag already scanned today"` is kept and now announces a Doublon / perdu record, also when the rescan is on a later day; `"Second tag of the crate ignored"` now announces a crate turned Cheat; `isCompliant` is `false` for both |

## Changed behaviour of existing operations

- **`POST /tags/scan`** — same request, same response shape. Outcomes per rule: research R3.
- **`PATCH /records/{recordId}/conformity`** — description gains: on a record whose status is DOUBLON_PERDU or
  CHEAT, only an Administrateur is accepted (`403` otherwise); the record becomes CONFORME or NON_CONFORME as asked
  and a history entry is always written. `403` is already among the documented responses.
- **`GET /records/stats`** — same parameters. DOUBLON_PERDU records are left out of `summary`, `pickers[]`,
  `hours[]` and `activities` and counted in the new top-level `duplicates`; `cheats` is added to every count and
  reduces neither `total` nor the activity columns. This is the one change that is not purely additive: for a period
  after this version, `total` is lower than it would have been by the number of doublons.
