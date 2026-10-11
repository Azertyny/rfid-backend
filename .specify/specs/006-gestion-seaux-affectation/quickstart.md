# Quickstart: validating bucket assignment (006)

What each step should return is described in [contracts/openapi-buckets.md](contracts/openapi-buckets.md), and the assignment states in [data-model.md](data-model.md).

## 1. Automated tests

```zsh
mvn clean install                                  # regenerates the OpenAPI sources (bucketNumbers)
mvn clean test -Dtest='BucketServiceTest,BucketApiTest,PickerServiceTest,PickerApiTest,AccessMatrixSecurityTest'
mvn clean test                                     # full suite, must stay green
```

Expected results:

- `BucketServiceTest` and `BucketApiTest` pass: list, detail, assign, reassign, unassign, and the `404`s.
- In `BucketApiTest`, a picker with 2 buckets gets `200` from both `GET /api/pickers` and `GET /api/pickers/{id}`, with `bucketNumbers` holding 2 sorted numbers. This check fails before the change (`500`).
- `AccessMatrixSecurityTest` still gives `401` to anonymous callers, `403` to Opérateurs and `2xx`/`4xx` (not `403`) to Administrateurs on `/api/buckets/**`.

## 2. Manual check of the front

Start the app:

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

Open `front/login.html` and log in as `admin`. You need at least 2 pickers (`pickers.html`) and 3 buckets. Buckets are created by tag registration (`tags.html`, or `POST /api/tags/buckets/{n}` with `{"uids":["..."]}`).

| # | Action | Expected |
|---|---|---|
| 1 | Open "Seaux" from the nav bar | Buckets listed by increasing number, with tag count and "Non affecté" |
| 2 | Bucket 1 → "Affecter", type part of a picker's name (try without accents) | The list narrows as you type; choose picker A → "Suivant" → "Affecter le seau n°1 à A ?" → "Valider" |
| 3 | Look at the table | Bucket 1 shows A; "Désaffecter" now appears on it |
| 4 | Assign bucket 2 to A too | Allowed; `pickers.html` and `GET /api/pickers` still load (no `500`), and A has `bucketNumbers: [1, 2]` |
| 5 | Bucket 1 → "Réaffecter" → picker B | Confirmation names A: "Seau n°1 est actuellement affecté à A, le réaffecter à B ?" → after "Valider", bucket 1 shows B |
| 6 | Bucket 1 → "Désaffecter" → "Annuler" | Nothing sent (check the Network tab); still B |
| 7 | Bucket 1 → "Désaffecter" → "Confirmer" | "Non affecté", no "Désaffecter" button |
| 8 | Unassign bucket 2, then delete picker A in `pickers.html` | Deletion succeeds (`204`), no `409` |
| 9 | Log in as an Opérateur and open `buckets.html` | Access-denied message; "Seaux" link hidden |
| 10 | With more than 100 pickers (for example, created through the API), open the picker list | Every picker is listed, sorted by last name (several `/pickers?page=N` calls in Network) |

---

## 3. Amendment 2026-10-11: deleting a bucket (FR-010)

Contract: [contracts/openapi-buckets.md](contracts/openapi-buckets.md) §3; data: [data-model.md](data-model.md), amendment.

### Automated tests

```zsh
mvn clean test -Dtest='BucketServiceTest,BucketApiTest,TagServiceTest,TagScanApiTest,AccessMatrixSecurityTest'
mvn clean test                                     # full suite
```

Expected: the delete cases of research R14 pass, and `AccessMatrixSecurityTest` gives `401` anonymous, `403` Opérateur on `DELETE /api/buckets/{id}`.

### Manual check

App started and logged in as `admin` as in §2. Prepare bucket 12 with 2 tags of the reference list (`tags.html`) assigned to picker A, and bucket 13 with no tag nor picker.

| # | Action | Expected |
|---|---|---|
| 1 | Bucket 13 → "Supprimer" | "Supprimer le seau n°13 ?" with no tag or picker sentence → "Confirmer" → bucket 13 gone |
| 2 | Bucket 12 → "Supprimer" → "Annuler" | Nothing sent (Network tab); bucket 12 unchanged |
| 3 | Scan one tag of bucket 12 with a production reader, note `GET /api/records/stats` | One record for A |
| 4 | Bucket 12 → "Supprimer" | Text says "Son tag sera libéré…" (the scanned tag already left the bucket) and names A → "Confirmer" → bucket 12 gone; `pickers.html`: A has no bucket 12 |
| 5 | `GET /api/records/stats` again | Same totals as in step 3; the record still shows A on `reader.html` |
| 6 | Scan the other (freed) tag | A record with no picker |
| 7 | Register 1 tag under number 12 in `tags.html` | Accepted without move confirmation; bucket 12 is back, "Non affecté", 1 tag |
| 8 | Delete picker A in `pickers.html` (if they have no other bucket) | Not `409` any more. **But** A has a record since step 3, and deleting a picker with records answers `500` (pre-existing, see research "Deviation found by T044"); to see `204`, use a picker with no record |
| 9 | As an Opérateur, `DELETE /api/buckets/{id}` | `403` |
