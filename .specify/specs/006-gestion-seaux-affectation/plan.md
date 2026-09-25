# Implementation Plan: Gestion des seaux et affectation à un cueilleur

**Branch**: `006-gestion-seaux-affectation` | **Date**: 2026-09-25 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/006-gestion-seaux-affectation/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

The bucket backend already works: list, detail, assign (overwriting any existing assignment) and unassign (delivered by `001`). All of it is Administrateur-only since spec `008`. This plan closes the remaining gaps from the 2026-09-24 and 2026-09-25 clarifications:

1. **Picker API with several buckets (FR-006)**: `Picker.bucketNumbers` (sorted list, `[]` when none) replaces `bucketNumber`. `PickerService` groups buckets per picker instead of `toMap` / `findByPicker`, which fixes the `500` on `GET /api/pickers` and `GET /api/pickers/{id}` for a picker with 2 buckets.
2. **New admin page `front/buckets.html`** (US 1-3, FR-004a, FR-008, FR-009):
   - lists the buckets;
   - assigns or reassigns a bucket, choosing the picker from a filterable list of all pickers (loaded page by page) and confirming in a window that names the current picker;
   - unassigns a bucket after confirmation.
3. **Tests (SC-002)**: new `BucketServiceTest` and `BucketApiTest`, updated `PickerServiceTest`. Access rules are already covered by `AccessMatrixSecurityTest`.
4. **`doc/`**: fix the database diagram (1 → 0..n) and add the unassignment use case.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`); plain HTML/JS for the front, with no build step.

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA, Spring Security 6.5, OpenAPI Generator 7.6.0 (delegate pattern), Lombok. The front uses Bootstrap 5.3 and Font Awesome from the CDN, like the other pages. No new dependency.

**Storage**: H2 file (dev), PostgreSQL (prod), `ddl-auto: update`. No schema change ([data-model.md](data-model.md)).

**Testing**: JUnit 5 + Mockito (service); `@SpringBootTest` + MockMvc + `spring-security-test` on the `test` profile (HTTP). The front is checked by hand ([quickstart.md](quickstart.md)).

**Target Platform**: Linux container behind nginx on the local network; static front in `front/` served by `deploy/front`.

**Project Type**: web service (Spring Boot REST API) + static HTML/JS front.

**Performance Goals**: a few hundred pickers and buckets.
- `GET /buckets` runs 2 queries.
- Loading the page makes at most about 4 calls to `/pickers?size=100`.
- The picker list is filtered in the browser as you type, with no noticeable delay (a few hundred `<option>`s).

**Constraints**:
- API-first: change `api.yaml` before the code.
- No API change for picker selection (FR-009).
- `size` ≤ 100 on `/pickers`.
- Writes go through `apiFetch` (CSRF).

**Scale/Scope**:
- Backend: 1 schema changed in `api.yaml`, 2 description tweaks, `PickerService` (list, detail, create/update) and `BucketRepository` (+1/−1 method).
- Front: 1 new page, 5 nav bars touched.
- Tests: 2 new classes, 1 updated.
- Docs: 2 files in `doc/`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so, as in specs `001` and `008`, the conventions in `CLAUDE.md` are used as gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: the grouping happens in `PickerService`, the new query in `BucketRepository`; controllers do not change |
| API changes start in `api.yaml`; controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: `Picker.bucketNumbers` is changed in `api.yaml` first ([contracts/openapi-buckets.md](contracts/openapi-buckets.md)); delegate signatures stay the same |
| Routes follow the spec `008` access matrix; unlisted routes are denied | `CLAUDE.md` | Pass | Pass: no new route; `/api/buckets/**` stays Administrateur only and is already in `AccessMatrixSecurityTest` |
| Front is static HTML/JS without a build step; writes go through `apiFetch` | `CLAUDE.md` | Pass | Pass: `buckets.html` follows `pickers.html`, no library added |
| `mvn clean test` and `mvn clean install` pass on the `test` profile | `CLAUDE.md` | Pass | Pass (planned) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/006-gestion-seaux-affectation/
├── spec.md              # As-is spec + 2026-09-24/25 clarifications
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R9
├── data-model.md        # Phase 1: Bucket ↔ Picker cardinality, assignment lifecycle, Picker.bucketNumbers
├── quickstart.md        # Phase 1: validation guide
├── contracts/
│   └── openapi-buckets.md  # api.yaml changes + front page contract
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml             # Picker: bucketNumber → bucketNumbers (required array); PUT/DELETE bucket descriptions
src/main/java/com/rfidback/
├── service/
│   └── PickerService.java                      # listPickers: groupingBy; getPicker/createPicker/updatePicker: fill bucketNumbers
└── repository/
    └── BucketRepository.java                   # + findAllByPickerOrderByNumberAsc, − findByPicker
src/test/java/com/rfidback/
├── service/BucketServiceTest.java              # new (Mockito)
├── service/PickerServiceTest.java              # bucketNumbers: 0, 1 and 2 buckets
└── controller/BucketApiTest.java               # new (MockMvc, test profile, Administrateur) incl. picker with 2 buckets
front/
├── buckets.html                                # new: list, assign/reassign (filterable picker list + confirmation), unassign
└── index.html, pickers.html, readers.html, tags.html, users.html   # + "Seaux" nav link (data-admin-only)
doc/
├── 20251013-database_diagram.puml              # picker ||--o{ bucket
└── 20251116-use_cases.md                       # + "Désaffecter un seau", reassignment warning
```

`BucketService` and `BucketController` do not change: their behavior already matches FR-001 to FR-004a and FR-007.

**Structure Decision**: single Spring Boot project with the existing packages, plus one new static page in `front/`. No new package.

## Complexity Tracking

No Constitution Check violations.

## Follow-ups outside this plan

- Spec `006` header still says `Status: As-Is (reverse-engineered)`. Switch it to the target once implemented, and turn the "État actuel" wording of FR-005 into "Livré par `008`".
- Spec `001` FR-004 (`bucketNumber`) should point to FR-006 here once delivered.
