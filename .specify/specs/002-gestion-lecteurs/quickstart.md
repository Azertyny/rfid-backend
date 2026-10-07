# Quickstart: validating feature 002

## Automated

```zsh
mvn clean test -Dtest='ReaderServiceTest,ReaderApiTest,ReaderScanSecurityTest,ReaderTokenVisibilityTest,AccessMatrixSecurityTest'
mvn clean install   # full build, regenerates OpenAPI sources
```

Expected: all green. The status codes checked are the ones in the table in [contracts/openapi-readers.md](contracts/openapi-readers.md) §3.

## Schema upgrade on an existing database

Start the app on the existing dev database (`./data/rfidbackdb.mv.db`) **without deleting it**. Expected: startup succeeds, and `GET /api/readers` returns the readers that were already there with `active: true` (research R4). Before deploying, do the same check against a copy of the prod PostgreSQL database.

## Manual (local app)

Prerequisites: start the app with a bootstrap admin (see `CLAUDE.md`), open `front/login.html`, log in as that admin, and open `front/readers.html`.

1. **Validation**: create `"   "` → error, and the API answered `400`. Create `Poste A`, then `  poste a ` → error, and the API answered `409`.
2. **Rotation**: note Poste A's token T1, then click Régénérer la clé → the modal shows T2.
   `curl -s -o /dev/null -w '%{http_code}\n' -X POST localhost:8080/api/tags/scan -H 'Content-Type: application/json' -H 'x-api-token: T1' -d '{"uid":"X","isCompliant":true}'` → `401`. The same call with T2 → `200`.
3. **Deactivation**: Désactiver Poste A → its badge shows Désactivé, and the curl with T2 → `401`. Réactiver → the curl with T2 → `200` again.
4. **Front display**: log in as an Opérateur. `index.html` has no line for a disabled reader. `reader.html` lists it with the "désactivé" label, and its last records still load.

## After deploying

The token that used to be hard-coded in `front/index.html` (`176c77ca…`) must be treated as compromised (spec Edge Cases). Rotate the token of the reader that holds it, then reconfigure that device with the new token.

Data model details: [data-model.md](data-model.md). Design decisions: [research.md](research.md).

## Amendment 2026-10-06: deleting a reader (FR-008)

Automated:

```zsh
mvn clean test -Dtest='ReaderServiceTest,ReaderApiTest,ReaderScanSecurityTest,AccessMatrixSecurityTest,RecordApiTest,RecordStatsApiTest,LineActivityApiTest'
```

Schema: start on the existing dev database. Expected: startup succeeds, `reader.deleted_at` exists and is null for every reader, and `GET /api/readers` lists the same readers as before.

Manual, logged in as an Administrateur on `front/readers.html`:

1. **Active reader**: Poste A is active, so it has no "Supprimer" button. `curl -X DELETE` on its id (with session cookie and CSRF header) → `409`.
2. **Setup**: in `activities.html`, associate an activity with Poste A and make it current on the line. Scan a few tags with its token so it has records. Note today's dashboard totals.
3. **Delete**: Désactiver Poste A, then Supprimer and confirm. Expected: it disappears from `readers.html`, from the line selectors of `index.html` and `reader.html`, and from the lines of its activity in `activities.html`.
4. **History**: the dashboard totals for today are unchanged. `GET /api/records/readers/Poste%20A` still returns its records with `uid` `Poste A`.
5. **Token**: the scan curl with its token → `401`.
6. **uid**: create `poste a` → `409`.
7. **Other routes**: `PATCH /api/readers/{id}` `{"active":true}` and `DELETE /api/readers/{id}` again → `404`.
