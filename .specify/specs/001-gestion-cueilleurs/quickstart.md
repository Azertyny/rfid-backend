# Quickstart: validating feature 001

## Automated

```zsh
mvn clean test -Dtest='PickerServiceTest,PickerApiTest,AccessMatrixSecurityTest'
mvn clean install   # full build, regenerates OpenAPI sources
```

Expected: all green. The status codes checked are the ones in the table in [contracts/openapi-pickers.md](contracts/openapi-pickers.md).

## Manual (local app)

Prerequisites: start the app with a bootstrap admin (see `CLAUDE.md`), open `front/login.html`, and log in as that admin.

1. **Sort**: in the browser console on any logged-in page, run
   `apiFetch('/pickers?sort=creationDate,desc').then(r => r.status)` → `200`, newest picker first.
   `apiFetch('/pickers?sort=foo,asc').then(r => r.status)` → `400`.
2. **Dashboard**: open `front/index.html` with more than 100 pickers (or temporarily lower the loop's page size) → all picker names appear, and there is no `500` in the network tab (research R5).
3. **Delete blocked**: create picker P in `pickers.html`. There is no bucket page, so assign an existing bucket B from the console:
   `apiFetch('/buckets/<B id>/picker', {method:'PUT', headers:{'Content-Type':'application/json'}, body: JSON.stringify({pickerId:'<P id>'})})`
   (bucket ids come from `apiFetch('/buckets')`). Then delete P in `pickers.html` → the modal stays open with the "seau affecté" message and P is still listed.
4. **Delete allowed**: `apiFetch('/buckets/<B id>/picker', {method:'DELETE'}).then(r => r.status)` → `204`, then delete P → P disappears from the list.
5. **Regression**: create a picker whose name differs from an existing one only by case → error, and the API answered `409`.

Data model details: [data-model.md](data-model.md). Design decisions: [research.md](research.md).
