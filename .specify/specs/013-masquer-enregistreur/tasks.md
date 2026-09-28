# Tasks: Masquer l'enregistreur dans les choix de ligne

**Input**: Design documents from `.specify/specs/013-masquer-enregistreur/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md),
[contracts/ui-reader-lists.md](contracts/ui-reader-lists.md), [quickstart.md](quickstart.md)

**Tests**: no automated test task — no front test tooling in the repository and no Java change (research R7).
Validation is manual, per [quickstart.md](quickstart.md).

**Organization**: one phase per user story; the two stories touch different files and are independent.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: user story of the task (US1, US2)

## Phase 1: Setup

None: branch `013-masquer-enregistreur` exists, no dependency or configuration to add.

## Phase 2: Foundational

None: no API, entity or shared front code change (plan, research R1). Both stories only read `mode` and `active`
from the existing `GET /api/readers` response.

---

## Phase 3: User Story 1 - L'Opérateur ne voit que les lignes de production au choix de la ligne (Priority: P1) 🎯 MVP

**Goal**: the line chooser shown to a logged-in Opérateur or Administrateur lists only `PRODUCTION` readers, active
or disabled (FR-001, FR-004).

**Independent Test**: with `L1`, `L2` (disabled) in `PRODUCTION` and `E` in `ENREGISTREMENT`, a logged-in Opérateur
sees `L1` and `L2` (« désactivé ») on `front/reader.html`, not `E` (quickstart scenarios 1, 2, 5, 6, 7).

### Implementation for User Story 1

- [X] T001 [P] [US1] In `loadReaders()` of `front/reader.html`, build the grid from
  `(data.readers || []).filter(reader => reader.mode === 'PRODUCTION')` instead of `data.readers` (research R2); keep
  the `inactive` class and `<small>désactivé</small>` for `active === false` unchanged (research R3); add a one-line
  comment above the filter: lines only, a reader in registration mode is not a line (spec 013). Leave the kiosk path in
  `window.onload` (`isKioskMode()` → `selectReader`) untouched.
- [X] T002 [US1] In the same `loadReaders()` of `front/reader.html`, run the empty-list check on the filtered list and
  change its text from « Aucun lecteur configuré dans l'API » to « Aucune ligne de production configurée », keeping the
  existing red inline style (research R4, contract "Line chooser").

**Checkpoint**: quickstart scenarios 1, 2, 5 (line chooser part), 6 (line chooser part) and 7 pass.

---

## Phase 4: User Story 2 - Le filtre "Lecteur" du tableau de bord ne propose pas l'enregistreur (Priority: P2)

**Goal**: the dashboard « Lecteur » filter lists « Tous les lecteurs » then only `PRODUCTION` readers, with the
« (désactivé) » suffix kept and no « (enregistrement) » suffix; "Tous les lecteurs" figures and CSV unchanged
(FR-002, FR-003, FR-004).

**Independent Test**: with the same readers, `front/index.html` lists « Tous les lecteurs », `L1`,
`L2 (désactivé)`; `E`'s record of today is still counted under « Tous les lecteurs » and in the CSV export
(quickstart scenarios 3, 4, 5, 6).

### Implementation for User Story 2

- [X] T003 [P] [US2] In `loadReaders()` of `front/index.html`, filter
  `readers = readers.filter(reader => reader.mode === 'PRODUCTION')` before the sort (research R2), delete the line
  `if (reader.mode === 'ENREGISTREMENT') label += ' (enregistrement)';`, and replace the comment above the function
  ("All readers, including disabled ones and those now in registration mode…") with: production readers only,
  including disabled ones, which keep their past records; a reader in registration mode is not a line and its past
  records stay in « Tous les lecteurs » (spec 013, replaces spec 007 research R6). Leave `loadStats`,
  `buildStatsQuery` and `csvFileName` unchanged (research R5).

**Checkpoint**: quickstart scenarios 3, 4, 5 (dashboard part) and 6 (dashboard part) pass.

---

## Phase 5: Polish & Cross-Cutting Concerns

- [X] T004 [P] In `.specify/specs/007-tableau-de-bord/spec.md`, append to FR-001 (line 70): « *Remplacé en partie
  par la spec `013` : les lecteurs en mode `ENREGISTREMENT` ne sont plus listés ; leurs lectures passées restent
  dans « Tous les lecteurs ».* »
- [X] T005 [P] In `.specify/specs/007-tableau-de-bord/research.md`, section `## R6. Reader filter`, add a last bullet
  `- **Superseded (spec 013)**: readers in ENREGISTREMENT mode are no longer listed; their past production records
  stay in "Tous les lecteurs".`
- [X] T006 Run `mvn clean test` (backend unchanged, must stay green) and walk through all 7 scenarios of
  `.specify/specs/013-masquer-enregistreur/quickstart.md`; set **Status** in
  `.specify/specs/013-masquer-enregistreur/spec.md` to `Delivered (<date>)` once they pass.

---

## Dependencies & Execution Order

### Phase Dependencies

- Setup and Foundational: empty.
- US1 (Phase 3) and US2 (Phase 4): independent, different files; either order.
- Polish (Phase 5): T004, T005 any time; T006 after T001–T003.

### User Story Dependencies

- **US1 (P1)**: none.
- **US2 (P2)**: none; does not need US1.

### Within Each User Story

- US1: T002 after T001 (same function in the same file).
- US2: single task.

### Parallel Opportunities

- T001, T003, T004, T005 touch four different files and can run together.

## Parallel Example

```text
T001 [US1] front/reader.html      — filter the line chooser
T003 [US2] front/index.html       — filter the dashboard selector
T004       007 spec.md            — FR-001 note
T005       007 research.md        — R6 note
```

## Implementation Strategy

### MVP First (User Story 1 Only)

1. T001, T002 → validate quickstart scenarios 1, 2, 7: Opérateurs can no longer open the enregistreur by mistake.

### Incremental Delivery

1. US1 (T001–T002) → the daily source of errors is removed.
2. US2 (T003) → dashboard filter cleaned.
3. Polish (T004–T006) → docs of spec 007 aligned, full validation, one PR to `dev`.

## Notes

- No change to `api.yaml`, Java code or tests: if one seems needed, the design (research R1) is being departed from.
- Commit after each phase or as one commit for the whole feature; open the PR with `--base dev`.

## Phase 6: Convergence

- [X] T007 In `loadReaders()` of `front/index.html`, split line 254 so that `select.appendChild(new Option(label, reader.id));` is back on its own line after `if (reader.active === false) label += ' (désactivé)';`, indented like the surrounding lines (behavior unchanged) per T003 (partial)
