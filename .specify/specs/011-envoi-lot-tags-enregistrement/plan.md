# Implementation Plan: Envoi groupé des tags par le lecteur d'enregistrement

**Branch**: `011-envoi-lot-tags-enregistrement` | **Date**: 2026-09-26 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/011-envoi-lot-tags-enregistrement/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

A registration reader (mode `ENREGISTREMENT`) gets its own route, `POST /api/tags/registration-reads`, taking
`{ "uids": [...] }` in one call:

1. **Route and security** (FR-001–FR-003): new `Registration` operation `recordRegistrationReads`; the reader token
   chain matches it next to `/api/tags/scan`, so missing, unknown or disabled tokens and user sessions get `401`
   (R1, R2). A `PRODUCTION` reader gets `403` (R3).
2. **Batch into the session** (FR-004–FR-010, FR-013): `RegistrationService.recordReads` trims, drops blanks,
   de-duplicates and validates (≤ 100 distinct, ≤ 50 characters, ≤ 1,000 raw elements → else `400`, R4). In one
   transaction it locks the reader's session row, inserts the UIDs the session lacks and touches the session once;
   one retry on a unique-constraint race (R5). No session or an expired one → `200` with `sessionOpen: false`.
3. **Unchanged** (FR-011, FR-012): `/tags/scan`, `recordRead`, saving a session, `front/tags.html`.

No schema change, no new dependency, no front change.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Data JPA, Spring Security 6.5, OpenAPI Generator (delegate
pattern), Lombok. No new dependency.

**Storage**: H2 (dev, test) / PostgreSQL (prod); existing tables `registration_session`, `registration_read`. One
`SELECT … FOR UPDATE` per call (R5).

**Testing**: JUnit 5 + Mockito (`RegistrationServiceTest`), `@SpringBootTest` + MockMvc on the `test` profile (new
`RegistrationReadsApiTest`, `ReaderScanSecurityTest`) (R9).

**Target Platform**: the `rfid-backend` container on the VPS; the enregistreur on the Administrateur's station.

**Project Type**: web service (Spring Boot REST API) + static front (`/front`, untouched).

**Performance Goals**: none new (spec Assumptions: one or two enregistreurs, a few calls a minute). A call does one
lock, one `IN` query and at most 100 inserts.

**Constraints**: API-first (`api.yaml` before code); tag-by-tag path unchanged (FR-011); all or nothing per call
(FR-010); reader payload defined by us (clarification Q2).

**Scale/Scope**: 1 route, 2 schemas, 1 service method, 2 repository methods, 1 security matcher, 1 new test class,
2 test classes extended, docs (`CLAUDE.md`, `deploy/INSTALL.md`).

No `NEEDS CLARIFICATION` remains: the spec questions were answered on 2026-09-26, the rest is settled in
[research.md](research.md).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so as in earlier specs the conventions in
`CLAUDE.md` are the gates:

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the layering (controller → service → repository → entity) | `CLAUDE.md` | Pass | Pass: `RegistrationController` resolves the reader and delegates; rules in `RegistrationService`; two query methods in the repositories |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: [contracts/openapi-registration-reads.md](contracts/openapi-registration-reads.md); `recordRegistrationReads` added to `RegistrationApiDelegate` |
| Reader devices stay on the stateless token chain | `CLAUDE.md` | Pass | Pass: the `@Order(1)` chain matches the new route; same filter, no CSRF, no session (R2) |
| Routes follow the spec `008` access matrix | `CLAUDE.md` | Pass | Pass: reader-only route like `/tags/scan`; user sessions refused (`401`), covered in `ReaderScanSecurityTest` |
| Schema changes through entities with `ddl-auto: update` | `CLAUDE.md` | Pass | Pass: no schema change (R8) |
| Tests run on the `test` profile; `mvn clean install` passes | `CLAUDE.md` | Pass | Pass (planned, R9) |

No violations, so Complexity Tracking stays empty.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/011-envoi-lot-tags-enregistrement/
├── spec.md              # Spec + 2026-09-26 clarifications (FR-008 bounds added during planning)
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R9
├── data-model.md        # Phase 1: request rules, outcomes, repository additions
├── quickstart.md        # Phase 1: automated and manual checks
├── contracts/
│   └── openapi-registration-reads.md   # Phase 1: api.yaml changes
├── checklists/
│   └── requirements.md
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml                        # new path + 2 schemas, ReaderApiToken description
src/main/java/com/rfidback/
├── configuration/SecurityConfig.java                      # reader chain matches /api/tags/registration-reads
├── controller/RegistrationController.java                 # recordRegistrationReads: reader from SecurityContext
├── service/RegistrationService.java                       # recordReads: validation, mode check, locked transaction, retry
└── repository/
    ├── RegistrationSessionRepository.java                 # findLockedByReader (PESSIMISTIC_WRITE)
    └── RegistrationReadRepository.java                    # findUidsBySessionAndUidIn

src/test/java/com/rfidback/
├── controller/RegistrationReadsApiTest.java               # new
├── service/RegistrationServiceTest.java                   # production 403, retry
└── security/ReaderScanSecurityTest.java                   # 401 cases on the new route

CLAUDE.md                                                  # reader chain now covers two routes
deploy/INSTALL.md                                          # enregistreur target: /api/tags/registration-reads
```

**Structure Decision**: the existing single Spring Boot project; the reader resolution in `TagController` (from
`ReaderAuthentication`) is reused in `RegistrationController`, moved to a small shared helper if both need it.

## Complexity Tracking

No violations to justify.
