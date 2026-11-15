# Implementation Plan: Renommer les « seaux » en « paquets »

**Branch**: `017-renommage-seaux-paquets` | **Date**: 2026-11-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/017-renommage-seaux-paquets/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

The container of tags is displayed as « paquet » instead of « seau »; only French text changes:

1. **Front** (FR-001–FR-003, User Stories 1–3): 45 whole-word occurrences in 10 pages are replaced word for word, per
   [contracts/ui-vocabulary.md](contracts/ui-vocabulary.md) — the menu entry « Seaux » → « Paquets » on all 10 pages
   with its icon `fa-fill` → `fa-box` (R4), the assignment page (`buckets.html`, 18), tag registration (`tags.html`,
   13), and four help/error texts in `index.html`, `pickers.html`, `settings.html`. « Erreur réseau » and « caisse »
   are left alone (R2, FR-009).
2. **Docs** (FR-008, User Story 4): four French descriptions in `api.yaml`, `doc/20251116-use_cases.md` (32) and the
   `CLAUDE.md` glossary (R6). Delivered specs are kept.

No Java, entity, data, URL or API-contract change (R1, R5).

## Technical Context

**Language/Version**: front: plain HTML/JavaScript, no build. `api.yaml` descriptions only (OpenAPI 3); Java 21
backend unchanged.

**Primary Dependencies**: Bootstrap 5, Font Awesome 6.0.0 / 6.4.0 from the CDN (`fa-box` exists in both, R4). No new
dependency.

**Storage**: N/A — no data change, see [data-model.md](data-model.md).

**Testing**: whole-word search plus manual pass, [quickstart.md](quickstart.md); `mvn clean test` must stay green
(R7). No front test tooling in the repository.

**Target Platform**: office browsers and line touch screens (pages served by the `rfid-web` image).

**Project Type**: web service (Spring Boot REST API) + static front (`/front`); only the front and docs change.

**Performance Goals**: none (text only).

**Constraints**: URLs, API paths/payloads and reader exchanges unchanged (FR-007); whole-word replacement only, so
« réseau » survives (R2).

**Scale/Scope**: 10 front pages (45 strings + 10 icons), 4 `api.yaml` descriptions, 1 use-case doc (32), 1 line of
`CLAUDE.md`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template: no ratified principle to check. Project rules from
`CLAUDE.md` checked instead:

- **API-first** (`api.yaml` before code): only descriptions change, no signature or schema → generated interfaces
  identical apart from Javadoc. ✅
- **Security matrix** (spec `008`): no route or role change. ✅
- **Persistence** (`ddl-auto: update`, no migration tool): no entity change, so nothing for Hibernate to alter (R1). ✅
- **Tests on the `test` profile**: no Java change; existing suite rerun. ✅

Post-design re-check (after Phase 1): unchanged, pass.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/017-renommage-seaux-paquets/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R7
├── data-model.md        # Phase 1: no change, term mapping
├── quickstart.md        # Phase 1: search checks + manual pass
├── contracts/
│   └── ui-vocabulary.md # Phase 1: every string before/after
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
front/
├── buckets.html         # menu + 18 strings (title, headings, modals, messages, empty state)
├── tags.html            # menu + 13 strings (heading, field, badges, move modal, messages)
├── index.html           # menu + Cheat link tooltip
├── pickers.html         # menu + delete refusal alert
├── settings.html        # menu + 2 lines of Cheat gap help
└── activities.html, anomalies.html, readers.html, users.html, work-hours.html   # menu only

src/main/resources/openapi/api.yaml   # 4 French field descriptions
doc/20251116-use_cases.md             # 32 occurrences
CLAUDE.md                             # glossary: Bucket (paquet, formerly seau)
```

**Structure Decision**: existing layout; only `front/`, `api.yaml` descriptions and docs are touched.

## Complexity Tracking

No violation to justify.
