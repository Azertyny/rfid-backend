# Contract: bucket assignment (006)

## 1. `api.yaml` changes

### `components.schemas.Picker`: replace `bucketNumber` with `bucketNumbers`

```yaml
    Picker:
      allOf:
        - $ref: "#/components/schemas/CreatePicker"
        - type: object
          properties:
            id:
              type: string
              format: uuid
              description: Picker unique identifier
            creationDate:
              type: string
              format: date-time
              description: Picker creation date
            bucketNumbers:
              type: array
              description: Numbers of the buckets currently assigned to the picker, ascending; empty when none
              items:
                type: integer
              example: [12, 47]
          required:
            - id
            - creationDate
            - bucketNumbers
```

This affects `GET /pickers` (`PickersPage.content[]`) and `GET /pickers/{pickerId}`. `POST` and `PUT /pickers*` return the same schema, with the list filled in when the picker has buckets (the value from the detail route). Operation signatures do not change, so `PickerController` does not change.

### `/buckets*`: descriptions only

No change to the schema or the signatures. The descriptions are updated so the contract says what the code does:

- `PUT /buckets/{bucketId}/picker` → description: "Assign an existing bucket to an existing picker. If the bucket already has a picker, it is replaced (a picker may have several buckets)."
- `DELETE /buckets/{bucketId}/picker` → keep `404` when the bucket does not exist; `204` also when it had no picker.

### Unchanged responses (for reference)

| Route | Success | Errors |
|---|---|---|
| `GET /api/buckets` | `200 BucketsList`, sorted by `number` asc; each bucket has `tags: string[]` and `picker: {lastname, firstname, comment} \| null` | `401`, `403` |
| `GET /api/buckets/{id}` | `200 BucketWithTagsAndPicker` | `404`, `401`, `403` |
| `PUT /api/buckets/{id}/picker` body `{"pickerId": uuid}` | `204` | `400` (body/uuid), `404` (bucket or picker), `401`, `403` |
| `DELETE /api/buckets/{id}/picker` | `204` | `404`, `401`, `403` |

Access: all four routes are Administrateur only (`SecurityConfig.java:125`, spec `008`). `PUT` and `DELETE` need CSRF (`X-XSRF-TOKEN`, set by `apiFetch`).

## 2. Front contract: `front/buckets.html`

| Step | Call | Page behaviour |
|---|---|---|
| Load | `requireRole('ADMINISTRATEUR')`, then `GET /buckets` and `GET /pickers?size=100&page=N&sort=lastname,asc` until `metadata.hasNext == false` | Table of buckets; picker list cached in memory |
| "Affecter" / "Réaffecter" | none | Modal step 1: filter field + `<select size="8">` of "Nom Prénom" (value = id) |
| "Suivant" | none | Step 2: "Affecter le seau n°X à A ?" or, if the bucket already has a picker, "Seau n°X est actuellement affecté à B, le réaffecter à A ?" |
| "Valider" | `PUT /buckets/{id}/picker` `{"pickerId": id}` | `204` → close, reload `GET /buckets`; `404` → "Ce seau ou ce cueilleur n'existe plus", reload lists |
| "Désaffecter" (only when a picker is set) | none | Modal: "Retirer B du seau n°X ?" |
| "Confirmer" | `DELETE /buckets/{id}/picker` | `204` → close, reload; `404` → same message as above |

Navigation: a "Seaux" link (`buckets.html`, `data-admin-only`) is added to the nav bar in `index.html`, `pickers.html`, `readers.html`, `tags.html` and `users.html`.
