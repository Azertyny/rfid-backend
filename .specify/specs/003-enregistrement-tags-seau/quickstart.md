# Quickstart: validating feature 003

## Automated

```zsh
mvn clean test -Dtest='TagServiceTest,RegistrationServiceTest,RegistrationApiTest,TagRegistrationApiTest,ReaderApiTest,ReaderScanSecurityTest,AccessMatrixSecurityTest'
mvn clean install   # full build, regenerates OpenAPI sources
```

Expected: all green. The cases checked are the rows of the status table in [contracts/openapi-tags-registration.md](contracts/openapi-tags-registration.md) §3. Timeout cases use a fixed `Clock` (research R5), not real waiting.

## Schema upgrade on an existing database

Start the app on the existing dev database (`./data/rfidbackdb.mv.db`) **without deleting it**. Expected: startup succeeds, `GET /api/readers` returns the existing readers with `mode: "PRODUCTION"`, and the tables `registration_session` and `registration_read` exist (H2 console). Before deploying, do the same against a copy of the prod PostgreSQL database.

## Manual (local app)

Prerequisites: start the app with a bootstrap admin (see `CLAUDE.md`), log in as that admin in `front/login.html`, and create two readers in `readers.html`: `Poste enregistrement` (token `TR`) and `Ligne 1` (token `TP`). A curl call stands in for each physical reader:

```zsh
scan() { curl -s -X POST localhost:8080/api/tags/scan -H 'Content-Type: application/json' -H "x-api-token: $1" -d "{\"uid\":\"$2\",\"isCompliant\":false}"; echo; }
```

1. **Mode**: in `readers.html`, switch `Poste enregistrement` to Enregistrement. `index.html` no longer shows it as a line.
2. **No session**: `scan TR T-001` → `isCompliant: true`, message "…ignored…". `reader.html` shows no new record for that reader.
3. **Start and reads**: open `tags.html`. The reader is preselected (the only one in Enregistrement mode). Click Démarrer, then run `scan TR T-001` twice and `scan TR T-002` → the page lists T-001 and T-002, once each.
4. **Save, add-only**: type bucket `12`, Enregistrer → `registeredCount 2, totalCount 2`, and the list empties. Start again, `scan TR T-003`, save on bucket `12` → `registeredCount 1, totalCount 3`. `GET /api/buckets` shows bucket 12 with T-001, T-002 and T-003.
5. **Move confirmation**: Start, `scan TR T-001`, type bucket `13` → the page flags T-001 "Seau 12". Enregistrer → confirmation dialog listing T-001 / 12. Cancel the dialog → nothing changes. Confirm → T-001 is on bucket 13.
6. **Reader busy**: keep a session open, then log in as a second Administrateur in a private window and click Démarrer on the same reader → "Lecteur utilisé par …".
7. **Abandoned page**: close the first window without cancelling. After `app.registration.session-timeout` (set it to `1m` in `application-dev.yml` to try this quickly), the second Administrateur can start.
8. **Production unchanged**: `scan TP T-003` → a `Record` appears in `reader.html` for `Ligne 1`, with the picker of bucket 12 if one is assigned.
9. **API guard**: `POST /api/tags/buckets/14` with `{"uids":["T-003"]}` from the browser console via `apiFetch` → `409` listing T-003 / 12. With `"moveConfirmed": true` → `200`.

Data model: [data-model.md](data-model.md). Design decisions: [research.md](research.md).
