# Tasks: Renommer les « seaux » en « paquets »

**Input**: Design documents from `.specify/specs/017-renommage-seaux-paquets/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/ui-vocabulary.md](contracts/ui-vocabulary.md), [quickstart.md](quickstart.md)

**Tests**: no automated test task — no front test tooling in the repository and no Java change (research R5, R7).
Validation is the whole-word search and the manual pass of [quickstart.md](quickstart.md); `mvn clean test` must
stay green.

**Organization**: one phase per user story. Every replacement is listed with its line and exact text in
[contracts/ui-vocabulary.md](contracts/ui-vocabulary.md).

**Rule for every task** (research R2, R3): replace « seau » / « seaux » **as a whole word only**, keeping case and
number (« Seaux » → « Paquets », « Seau » → « Paquet », « seau » → « paquet », « seaux » → « paquets »). Never touch
« réseau » (« Erreur réseau »), « caisse(s) », URLs, element ids or JS identifiers (`bucketNumber`, `selectedBucket`,
`buckets.html`…). Do not use a blind `sed 's/seau/paquet/g'`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: user story of the task (US1–US4)

## Phase 1: Setup

- [X] T001 Create and switch to branch `017-renommage-seaux-paquets` from the current branch
  (`git switch -c 017-renommage-seaux-paquets`), so the rename does not land on `016-logos-vegelink`

## Phase 2: Foundational

None: no API, entity, data or shared front code change (plan, research R1).

---

## Phase 3: User Story 1 - Lire « paquet » partout dans l'interface (Priority: P1) 🎯 MVP

**Goal**: the menu and the assignment page speak of « paquets ».

**Independent Test**: on any page, the menu entry reads « Paquets » with a box icon; on `buckets.html`, title, headings,
column, the three windows, every message and the empty state say « paquet » (quickstart steps 1–2).

- [X] T002 [US1] Menu entry on all 10 pages: replace `<i class="fas fa-fill me-1"></i>Seaux` with
  `<i class="fas fa-box me-1"></i>Paquets` (FR-003, research R4; the link target `buckets.html` stays) in
  `front/activities.html`, `front/anomalies.html`, `front/buckets.html`, `front/index.html`, `front/pickers.html`,
  `front/readers.html`, `front/settings.html`, `front/tags.html`, `front/users.html`, `front/work-hours.html`
- [X] T003 [US1] Assignment page: replace the 18 remaining occurrences listed under « `front/buckets.html` » in
  `contracts/ui-vocabulary.md` — `<title>`, heading, subtitle, column « N° du paquet », modal titles (assign,
  unassign, delete), `assignTitle`, assign/reassign confirmations, unassign and delete questions, `GONE_MESSAGE`,
  « Ce paquet n'existe plus. », load errors and the empty-state message (both words on that line) — in
  `front/buckets.html`

**Checkpoint**: menu and assignment page done; whole-word search on `front/buckets.html` returns nothing.

---

## Phase 4: User Story 2 - Enregistrer des tags dans un paquet (Priority: P1)

**Goal**: the tag registration page speaks of « paquet ».

**Independent Test**: register tags on a new number, then on another number, then with an empty number; every
heading, label, badge, window and message says « paquet » (quickstart step 3).

- [X] T004 [P] [US2] Tag registration: replace the 13 occurrences listed under « `front/tags.html` » in
  `contracts/ui-vocabulary.md` — heading, help text, label « Numéro de paquet », column « Paquet actuel », move-modal
  title and text (two words on line 152), badges « Déjà sur ce paquet » / « Paquet N », the empty-number warning, the
  success message, the moved-tags list item and the refusal message — in `front/tags.html` (after T002 if run by the
  same person, since T002 also edits this file's menu)

**Checkpoint**: whole-word search on `front/tags.html` returns nothing.

---

## Phase 5: User Story 3 - Textes explicatifs et icône cohérents (Priority: P2)

**Goal**: the remaining help and error texts say « paquet » (the icon itself is done by T002).

**Independent Test**: settings Cheat help, dashboard Cheat link tooltip, refusal to delete a picker with a paquet
(quickstart step 4).

- [X] T005 [P] [US3] Dashboard: in the Cheat link `title`, « deux tags d'un même seau » → « deux tags d'un même
  paquet » in `front/index.html`
- [X] T006 [P] [US3] Pickers: in the 409 delete alert, « encore un seau affecté » → « encore un paquet affecté » in
  `front/pickers.html`
- [X] T007 [P] [US3] Settings: in both lines of the Cheat gap help, « deux tags d'un même seau » → « deux tags d'un même
  paquet » in `front/settings.html` (« caisses » on the second line stays)

**Checkpoint**: whole-word search on `front/` returns nothing.

---

## Phase 6: User Story 4 - Documentation à jour (Priority: P3)

**Goal**: the living docs speak of « paquet »; delivered specs `001`–`015` stay untouched.

**Independent Test**: whole-word search on the three files below finds only « formerly seau » in `CLAUDE.md`.

- [X] T008 [P] [US4] API descriptions: « Identifiant du seau » → « Identifiant du paquet » (two fields), « Seau
  affecte au cueilleur » → « Paquet affecté au cueilleur », « Seau désaffecté (aussi quand il n'avait pas de
  cueilleur) » → « Paquet désaffecté (aussi quand il n'avait pas de cueilleur) » in
  `src/main/resources/openapi/api.yaml` — `description` values only, no name, path, type or schema change
- [X] T009 [P] [US4] Use cases: replace the 32 whole-word occurrences, keeping grammar, in `doc/20251116-use_cases.md`
- [X] T010 [P] [US4] Glossary: `- **Bucket** (seau) — container assigned to a picker` →
  `- **Bucket** (paquet, formerly seau) — container of RFID tags assigned to a picker` in `CLAUDE.md`

---

## Phase 7: Polish & Cross-Cutting Concerns

- [X] T011 Run the automated checks of `quickstart.md` section 1: whole-word search over `front/*.html`, `front/*.js`,
  `src/main/resources/openapi/api.yaml`, `doc/20251116-use_cases.md` prints nothing; `CLAUDE.md` keeps only
  « formerly seau »; `fa-fill` count 0 and `fa-box me-1"></i>Paquets` on 10 pages; `grep -c 'Erreur réseau' front/*.html`
  unchanged from before the feature
- [X] T012 Run `mvn clean test` and confirm the suite is green (api.yaml descriptions regenerate Javadoc only)
- [X] T013 Manual pass of `quickstart.md` section 2 (steps 1–6, as Administrateur then Opérateur), comparing each text
  with `contracts/ui-vocabulary.md`
- [X] T014 Set **Status** to `Delivered (<date>) — see [plan.md](plan.md) and [tasks.md](tasks.md)` in
  `.specify/specs/017-renommage-seaux-paquets/spec.md`

---

## Dependencies & Execution Order

- **Setup (T001)** first.
- **User stories** are independent of each other: US1 (T002–T003), US2 (T004), US3 (T005–T007), US4 (T008–T010).
  The only overlap is file-level: T002 edits the menu line of `buckets.html`, `tags.html`, `index.html`,
  `pickers.html`, `settings.html`, which T003–T007 also edit (other lines). Do T002 first, or apply each file's
  edits together.
- **Polish (T011–T014)** after all stories; T014 last.

## Parallel Opportunities

- After T002: T003, T004, T005, T006, T007 (different files).
- US4 (T008, T009, T010) in parallel with everything, from the start.

```text
# After T002, in parallel:
T003 front/buckets.html   T004 front/tags.html   T005 front/index.html
T006 front/pickers.html   T007 front/settings.html
T008 api.yaml             T009 doc/20251116-use_cases.md   T010 CLAUDE.md
```

## Implementation Strategy

- **MVP** = US1 (T002–T003): the menu and the assignment page already show the new word everywhere it is most
  visible.
- Then US2 (registration), US3 (help texts), US4 (docs); each is checked by the whole-word search on its files.
- The whole feature is small (≈ 60 text edits); doing it in one pass and one commit is fine, followed by T011–T013.
