# Research: Renommer les « seaux » en « paquets »

No `NEEDS CLARIFICATION` was left in the Technical Context; the decisions below fix the scope and the method of the
rename.

## R1 — Rename displayed text only, keep `bucket` everywhere technical

- **Decision**: only French text changes. `/api/buckets`, the `Bucket*` OpenAPI schemas and fields (`bucketNumber`,
  `bucketId`…), the `bucket` table and columns, Java classes, `front/buckets.html` and its links stay as they are.
- **Rationale**: FR-007 (addresses and reader exchanges unchanged) and FR-006 (no data change). The technical names
  are English and never shown to users, so they carry no « seau ». Renaming them would touch the API contract, the
  schema (no migration tool, `ddl-auto: update` would create a new table and orphan the old one), the readers'
  callers and every test, for no user-visible gain.
- **Alternatives considered**: a full rename to `package`/`parcel` (contract break, data migration, risky with
  `ddl-auto`); renaming only `buckets.html` to `paquets.html` (breaks bookmarks, against FR-007).

## R2 — Inventory: 45 occurrences in 10 pages, not a blind search-and-replace

- **Decision**: replace word by word with the table in [contracts/ui-vocabulary.md](contracts/ui-vocabulary.md),
  matching « seau » / « seaux » as a whole word only.
- **Rationale**: a substring search for `seau` also hits « ré**seau** » (« Erreur réseau », about 20 times, and the
  only hit in `front/login.html`), which must not become « répaquet ». Whole-word count (Unicode-aware):
  `buckets.html` 19, `tags.html` 14, `settings.html` 3, `index.html` 2, `pickers.html` 2, and 1 each in
  `activities.html`, `anomalies.html`, `readers.html`, `users.html`, `work-hours.html` (the menu entry). `reader.html`
  (kiosk), `login.html`, `auth.js` and `config.js` have none.
- **Alternatives considered**: `sed 's/seau/paquet/g'` — breaks « réseau ».

## R3 — Grammar: the word swaps alone

- **Decision**: replace the noun only, keeping case and number: « Seaux » → « Paquets », « Seau » → « Paquet »,
  « seau » → « paquet », « seaux » → « paquets ».
- **Rationale**: both nouns are masculine and start with a consonant, so determiners and contractions (« le », « du »,
  « au », « ce », « un », « leur », « N° du ») and agreements (« affecté », « désaffecté ») stay right (FR-002).

## R4 — Menu icon: `fa-box`

- **Decision**: the menu entry's `fas fa-fill` (a filling bucket) becomes `fas fa-box` on all 10 pages.
- **Rationale**: FR-003 asks for a neutral parcel/box icon. `fa-box` is a free solid icon in Font Awesome 6.0.0
  (9 pages) and 6.4.0 (`index.html`); `fa-fill` is used only for this menu entry (10 occurrences), so nothing else
  changes look.
- **Alternatives considered**: `fa-boxes-stacked` (reads as stock or warehouse); `fa-cube` (too abstract next to the
  other entries).

## R5 — No server message to change

- **Decision**: no Java change.
- **Rationale**: FR-004 covers messages shown on screen whatever their origin. The only server text a page displays
  about this flow is the `detail` of a refused tag registration (`tags.html`), whose messages (`TagService`: tags off
  the reference list, too many tags, no uid) do not name the container. The other server messages that do name it
  (`Bucket … not found`, `Picker … still has … bucket`) are English, technical and never displayed: the pages show
  their own French message for those statuses (`buckets.html` `GONE_MESSAGE`, `pickers.html` 409 alert).

## R6 — Living docs updated, delivered specs kept

- **Decision**: update `src/main/resources/openapi/api.yaml` (4 French descriptions: `Identifiant du seau` ×2,
  `Seau affecte au cueilleur`, `Seau désaffecté…`), `doc/20251116-use_cases.md` (32 occurrences) and the glossary
  line of `CLAUDE.md` (`**Bucket** (paquet, formerly seau)`). Leave `.specify/specs/001`–`015` and the PlantUML
  diagrams (English `Bucket` only) untouched.
- **Rationale**: FR-008. The `api.yaml` descriptions only end up in generated Javadoc: no signature changes, the
  build regenerates them. Keeping « formerly seau » in the glossary lets a reader of older specs map the two words.

## R7 — Verification: whole-word search plus the existing suite

- **Decision**: done when a whole-word search finds no « seau » in `front/`, `api.yaml`, the use-case doc and
  `CLAUDE.md` (except « formerly seau »), the manual pass of [quickstart.md](quickstart.md) succeeds, and
  `mvn clean test` stays green.
- **Rationale**: there is no front test tooling; the search is the measurable check of SC-001, and the unchanged Java
  suite covers SC-002 (behaviour unchanged).
