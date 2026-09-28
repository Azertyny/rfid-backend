# Research: Masquer l'enregistreur dans les choix de ligne

No `NEEDS CLARIFICATION` was left in the Technical Context; the decisions below fix how the two lists are filtered.

## R1 — Filter in the page, not in the API

- **Decision**: `front/reader.html` (`loadReaders`) and `front/index.html` (`loadReaders`) keep calling
  `GET /api/readers` unchanged and drop the readers whose `mode` is not `PRODUCTION` before building their list.
- **Rationale**: `mode` is a required field of `Reader` in `api.yaml`, returned to both roles; there are a handful of
  readers, so filtering client-side costs nothing. FR-005 asks for no change in access rights, and the other pages
  (`readers.html`, `activities.html`, `tags.html`) still need every reader from the same route.
- **Alternatives considered**: a `mode` query parameter on `GET /readers` (edits `api.yaml`, `ReaderController`,
  `ReaderService` and tests for a filter the page can apply itself); hiding registration readers from Opérateurs in
  the API (changes access rights, against FR-005, and the dashboard is also used by Administrateurs).

## R2 — Keep `mode === 'PRODUCTION'`, not `mode !== 'ENREGISTREMENT'`

- **Decision**: a reader is listed when `reader.mode === 'PRODUCTION'`.
- **Rationale**: FR-001 and FR-002 list "only the readers in production mode"; a reader that is not a production line
  (today only `ENREGISTREMENT`, any future mode too) is not a line to choose.
- **Alternatives considered**: excluding `ENREGISTREMENT` only — equivalent today, but would show a future non-line
  mode by default.

## R3 — Active and disabled readers stay listed

- **Decision**: the `active` flag is not part of the filter; the « désactivé » marks stay as they are
  (`reader.html` class `inactive` + `<small>désactivé</small>`, `index.html` suffix « (désactivé) »).
- **Rationale**: spec User Story 1 scenario 3 and User Story 2 scenario 2: disabled production readers keep their
  history and stay selectable, as before.

## R4 — Empty list on the line chooser

- **Decision**: when no reader is left after the filter, `reader.html` shows its existing message in the grid,
  worded for lines: « Aucune ligne de production configurée ». The dashboard shows only « Tous les lecteurs », as it
  already does with no reader.
- **Rationale**: spec edge case "Aucun lecteur en mode production". The test runs after filtering, otherwise an
  installation with only an enregistreur shows an empty grid without explanation.
- **Alternatives considered**: keeping « Aucun lecteur configuré dans l'API » — wrong when an enregistreur exists.

## R5 — Dashboard: remove the « (enregistrement) » suffix, keep "Tous les lecteurs" totals

- **Decision**: delete the suffix line in `index.html` and rewrite the comment above `loadReaders` (it cites
  research R6 of spec `007`, now replaced). `GET /records/stats` is unchanged, so "Tous les lecteurs" and the CSV
  export still count every production record of the period, including those of a reader now in registration (FR-003).
  `readersById` only holds listed readers; it is used for the CSV file name of the selected reader, which is always
  a listed one.
- **Rationale**: FR-002, FR-003, SC-003.

## R6 — Mode read at page load

- **Decision**: both lists are built once at page load, as today; no polling of `/readers` is added.
- **Rationale**: FR-004 and the spec edge case "Changement de mode pendant qu'une page est ouverte". A reader switched
  back to `PRODUCTION` appears at the next load.

## R7 — Tests and documentation

- **Decision**: no automated test: the repository has no front test tooling (see spec `007`, "Pas de test"), and no
  Java code changes. Validation is manual, following [quickstart.md](quickstart.md); `mvn clean test` still runs to
  confirm nothing else moved. Spec `007` FR-001 and research R6 get a one-line note pointing to spec `013`.
- **Alternatives considered**: introducing a JS test harness for two one-line filters — out of proportion.
