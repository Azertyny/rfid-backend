# Implementation Plan: Masquer l'enregistreur dans les choix de ligne

**Branch**: `013-masquer-enregistreur` | **Date**: 2026-09-28 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/013-masquer-enregistreur/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Readers in `ENREGISTREMENT` mode are no longer offered as lines:

1. **Line chooser** (FR-001, User Story 1): `front/reader.html` `loadReaders` keeps only `mode === 'PRODUCTION'`
   readers from `GET /api/readers`, for both roles; empty result → « Aucune ligne de production configurée » (R1, R2,
   R4). Kiosk mode never shows the chooser and is unchanged.
2. **Dashboard filter** (FR-002, FR-003, User Story 2): `front/index.html` `loadReaders` applies the same filter and
   drops the « (enregistrement) » suffix; `GET /records/stats` is untouched, so "Tous les lecteurs" and the CSV
   export still count those readers' past production records (R5). Replaces spec `007` FR-001 / research R6 for
   registration readers; both get a note pointing here (R7).

No API, entity or Java change.

## Technical Context

**Language/Version**: front: plain JavaScript in static HTML, no build. Backend (Java 21) unchanged.

**Primary Dependencies**: Bootstrap 5 (dashboard), `front/auth.js` (`apiFetch`, `requireRole`, kiosk mode). No new
dependency.

**Storage**: N/A (no data change, see [data-model.md](data-model.md)).

**Testing**: manual, [quickstart.md](quickstart.md) — the repository has no front test tooling; `mvn clean test`
must stay green (R7).

**Target Platform**: browsers of the line touch screens and of the office (pages served by the `rfid-web` image).

**Project Type**: web service (Spring Boot REST API) + static front (`/front`); only the front changes.

**Performance Goals**: none new: one `GET /readers` per page load, as today, over a handful of readers.

**Constraints**: API and access rules unchanged (FR-005); a reader's current mode, at page load, decides (FR-004);
disabled production readers stay listed (R3).

**Scale/Scope**: 2 functions in 2 files (`front/reader.html`, `front/index.html`), 2 doc notes in spec `007`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template: no ratified principle to check. Project rules from
`CLAUDE.md` checked instead:

- **API-first** (`api.yaml` before code): no endpoint changes → nothing to edit. ✅
- **Security matrix** (spec `008`): no route or role change (FR-005). ✅
- **Tests on the `test` profile**: no Java change; existing suite rerun. ✅

Post-design re-check (after Phase 1): unchanged, pass.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/013-masquer-enregistreur/
├── plan.md              # This file
├── research.md          # Phase 0: R1–R7
├── data-model.md        # Phase 1: no change, fields used
├── quickstart.md        # Phase 1: manual validation
├── contracts/
│   └── ui-reader-lists.md   # Phase 1: content of both lists
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
front/
├── reader.html          # loadReaders(): filter mode === 'PRODUCTION', empty-list message
└── index.html           # loadReaders(): same filter, drop « (enregistrement) », rewrite comment

.specify/specs/007-tableau-de-bord/
├── spec.md              # FR-001: note "registration readers no longer listed, spec 013"
└── research.md          # R6: same note
```

**Structure Decision**: existing layout; only `front/` and spec `007` docs are touched.

## Complexity Tracking

No violation to justify.
