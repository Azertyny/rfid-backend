# Contract changes: `/api/pickers`

Changes to make in `src/main/resources/openapi/api.yaml`. Only descriptions and response references change, so the generated `PickerApiDelegate` signatures stay the same (research R4).

## 1. `components.parameters.PageSort`: document the allow-list

```yaml
    PageSort:
      name: sort
      in: query
      description: |
        Sort order, format `field,direction`.
        Allowed fields: `lastname`, `firstname`, `creationDate`.
        Allowed directions: `asc`, `desc` (case-insensitive).
        Default when missing: `lastname,asc` then `firstname,asc`.
        Any other value returns 400.
      schema:
        type: string
      example: lastname,asc
```

No `pattern` (see research R1: it would produce an unmapped `ConstraintViolationException` path and a second copy of the allow-list).

## 2. `components.responses`: add `Conflict`

```yaml
    Conflict:
      description: Retourné lorsque l'action est impossible car la ressource est encore utilisée
```

## 3. `DELETE /pickers/{pickerId}`: use it

```yaml
        "409": { $ref: "#/components/responses/Conflict" }
```

## 4. `DELETE /buckets/{bucketId}/picker`: new, from spec 006 FR-007 (research R7)

Add under the existing `/buckets/{bucketId}/picker` path, next to `put`:

```yaml
    delete:
      summary: Unassign the picker from a bucket
      operationId: unassignBucketFromPicker
      tags: [Bucket]
      responses:
        "204":
          description: Seau désaffecté (aussi quand il n'avait pas de cueilleur)
        "404": { $ref: "#/components/responses/NotFound" }
        "500": { $ref: "#/components/responses/InternalError" }
```

This adds `unassignBucketFromPicker(UUID bucketId)` to the generated `BucketApiDelegate`. Access: Administrateur only, through the existing `/api/buckets/**` rule.

## Resulting behaviour

| Request | Condition | Status |
|---|---|---|
| `GET /pickers` | no `sort` | 200, ordered by lastname then firstname |
| `GET /pickers?sort=firstname,desc` | allowed | 200, ordered by firstname descending |
| `GET /pickers?sort=foo,asc` / `sort=lastname,up` / `sort=lastname` | not allowed | 400 |
| `GET /pickers?size=500` / `page=-1` | outside `@Min`/`@Max` | 400 (was 500, research R5) |
| `DELETE /pickers/{id}` | unknown id | 404 |
| `DELETE /pickers/{id}` | ≥ 1 bucket assigned | 409, nothing deleted |
| `DELETE /pickers/{id}` | no bucket | 204 |
| `DELETE /buckets/{id}/picker` | bucket exists (assigned or not) | 204 |
| `DELETE /buckets/{id}/picker` | unknown bucket | 404 |

Roles are unchanged (spec `008`): `GET` requires Opérateur or Administrateur, and `POST`/`PUT`/`DELETE` require Administrateur.

## Front contract (`front/`)

- `pickers.html` `confirmDelete`: on `409`, keep the modal open and show "Ce cueilleur a encore un seau affecté : désaffectez-le avant de le supprimer." instead of the generic error. On `404`, refresh the list.
- `pickers.html` list: keeps sending `sort=lastname,asc`, which is still valid.
- `index.html`: replace `/pickers?size=500` with a loop over `/pickers?size=100&page=N` until `metadata.hasNext` is `false`.

---

## 5. Amendment 2026-10-11: logical deletion (FR-010)

No schema or signature change; descriptions only, so the contract says what the code does:

- `DELETE /pickers/{pickerId}`: description → "Deletes the picker logically: they disappear from the lists and can no longer be read, changed, assigned a bucket or given hours (404), but their records and hours stay and keep their name on the dashboard. Their name stays taken. Refused with 409 while a bucket is assigned to them." Responses unchanged: `204`, `404` (unknown or already deleted), `409`, `401`, `403`.
- `GET /pickers`: description gains "Deleted pickers are not listed."
- `GET`, `PUT /pickers/{pickerId}`: `404` description → "Picker not found (unknown or deleted)".
- `PUT /buckets/{bucketId}/picker`: `404` also when the picker is deleted.
- `GET /work-hours`: "Deleted pickers are not listed."; `PUT /work-hours`: `404` also for an entry naming a deleted picker.

| Route | Deleted picker |
|---|---|
| `GET /api/pickers` | not in `content`; `metadata` counts live pickers only |
| `GET`/`PUT`/`DELETE /api/pickers/{id}` | `404` |
| `POST /api/pickers` with their name | `409` |
| `PUT /api/buckets/{id}/picker` with their id | `404` |
| `GET /api/work-hours?day=` | not listed |
| `PUT /api/work-hours` with their id | `404` |
| `GET /api/records/stats` | unchanged: their crates and hours, under their name |

Front: only the confirmation text of `pickers.html` changes (research R13): "Cette action est irréversible. Ses lectures et ses heures restent comptées sur le tableau de bord."
