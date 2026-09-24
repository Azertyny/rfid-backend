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
