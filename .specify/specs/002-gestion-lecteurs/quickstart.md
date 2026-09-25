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
