# Implementation Plan: Authentification et rôles

**Branch**: `008-authentification-roles` (work currently on `dev`) | **Date**: 2026-09-24 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `.specify/specs/008-authentification-roles/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

Add user login and two roles (Administrateur, Opérateur) to an API where today only `POST /api/tags/scan` is protected. Users log in through a JSON endpoint that opens a server-side session (cookie), protected by CSRF. A second, stateless security chain keeps reader devices on their `x-api-token` exactly as today. The access matrix from the spec is enforced by URL rules in `SecurityConfig`. The first Administrateur comes from environment variables; Administrateurs manage the other accounts through a new `/api/users` API and a minimal `users.html` page. The front gets a shared `auth.js`, a `login.html` page, and loses its hardcoded reader token.

## Technical Context

**Language/Version**: Java 21 (`pom.xml`)

**Primary Dependencies**: Spring Boot 3.5.7, Spring Security 6.5.6, Spring Data JPA, OpenAPI Generator 7.6.0 (delegate pattern), Lombok. No new dependency.

**Storage**: H2 file (dev), PostgreSQL 16 (prod, `deploy/docker-compose.yml`); schema via `ddl-auto: update`. New table `app_user`.

**Testing**: JUnit 5, Mockito, `spring-security-test` + MockMvc (already in `pom.xml`); new `test` profile with in-memory H2.

**Target Platform**: Linux container behind `nginx` on the counting line's local network; static front served by the same `nginx`.

**Project Type**: web service (Spring Boot REST API) + static HTML/JS front in `front/`.

**Performance Goals**: no new target; login ≤ 1 s (bcrypt cost default). Polling screens (`reader.html` every 500 ms, `index.html` every 3 s) must not hit the database for authentication on each call (research R6).

**Constraints**: API-first (every new route declared in `api.yaml` first); reader devices unchanged (SC-004); no secret in `front/` (SC-003); single app instance (in-memory sessions); plain HTTP on the local network (accepted risk, research).

**Scale/Scope**: < 200 pickers (spec `001`), a handful of users and readers; 12 functional requirements, ~20 routes in the matrix, 6 front pages touched or added.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

`.specify/memory/constitution.md` is still the unfilled template, so there are no ratified principles to check. The project conventions in `CLAUDE.md` are used as gates instead (`AGENTS.md` has been removed from the working tree):

| Gate | Source | Pre-design | Post-design |
|---|---|---|---|
| Keep the existing layering (controller → service → repository → entity, plus `security`, `configuration`) | `CLAUDE.md` | Pass | Pass: every new class fits an existing package |
| API changes start in `api.yaml`, controllers implement generated delegates | `CLAUDE.md` | Pass | Pass: `AuthApiDelegate`, `UserApiDelegate`; login is not a hidden `formLogin` endpoint (research R2) |
| `mvn test` and `mvn clean install` pass | `CLAUDE.md` | Pass | Pass (planned): new tests run in an isolated `test` profile (research R10) |

No violations, so Complexity Tracking stays empty. Recommendation: ratify a constitution (`/speckit-constitution`) before the next features.

## Project Structure

### Documentation (this feature)

```text
.specify/specs/008-authentification-roles/
├── spec.md              # Consolidated auth spec
├── plan.md              # This file
├── research.md          # Phase 0: decisions R1-R10
├── data-model.md        # Phase 1: User entity, Role enum
├── quickstart.md        # Phase 1: end-to-end validation
├── contracts/
│   ├── openapi-auth.yaml   # Fragment to merge into api.yaml
│   └── front-auth.md       # Front behaviour contract
└── tasks.md             # Phase 2 (/speckit-tasks, not created here)
```

### Source Code (repository root)

```text
src/main/resources/openapi/api.yaml            # + Auth, User tags; Reader.apitoken optional; SessionCookie scheme
src/main/resources/application.yml             # + app.security.bootstrap-admin.*, session cookie settings
src/main/java/com/rfidback/
├── configuration/
│   └── SecurityConfig.java                    # rewrite: reader chain (@Order 1) + user chain (@Order 2), CSRF, session registry
├── security/
│   ├── ReaderApiTokenAuthenticationFilter.java   # keep; stop Boot auto-registering it as a global filter
│   ├── ReaderAuthentication.java                  # unchanged
│   ├── AppUserDetailsService.java                 # new: loads UserEntity
│   ├── CsrfCookieFilter.java                      # new: forces the XSRF-TOKEN cookie
│   └── BootstrapAdminRunner.java                  # new: creates first Administrateur
├── entity/
│   ├── UserEntity.java                        # new (table app_user)
│   └── Role.java                              # new enum
├── repository/UserRepository.java             # new
├── service/
│   ├── AuthService.java                       # new: login/logout/me, session strategy
│   ├── UserService.java                       # new: CRUD, last-admin guard, session expiry
│   └── ReaderService.java                     # change: apitoken only for Administrateur
├── controller/
│   ├── AuthController.java                    # new: implements AuthApiDelegate
│   └── UserController.java                    # new: implements UserApiDelegate
└── exception/
    ├── UserNotFoundException.java             # new (404)
    ├── UserAlreadyExistsException.java        # new (409)
    └── LastAdministratorException.java        # new (409)

src/test/
├── resources/application-test.yml             # new: in-memory H2
└── java/com/rfidback/
    ├── RfidBackApplicationTests.java          # switch to test profile
    └── security/
        ├── AccessMatrixSecurityTest.java      # parametrized route × profile → status
        ├── AuthFlowSecurityTest.java          # login/logout/me, disabled user, CSRF
        └── UserManagementSecurityTest.java    # 409 duplicate, last admin, session expiry

front/
├── auth.js          # new: apiFetch, requireRole, logout
├── login.html       # new
├── users.html       # new (Administrateur)
├── index.html       # remove API_TOKEN, use apiFetch, requireRole(OP, ADMIN)
├── reader.html      # stop requiring/sending apitoken, use apiFetch
├── pickers.html     # use apiFetch, requireRole(ADMIN)
└── readers.html     # use apiFetch, requireRole(ADMIN)

deploy/
├── docker-compose.yml     # pass APP_BOOTSTRAP_ADMIN_USERNAME / _PASSWORD
├── .env.example           # document the two variables
└── .env.local.example     # idem
```

**Structure Decision**: keep the existing single Spring Boot project and its layering (`controller` → `service` → `repository` → `entity`, plus `security` and `configuration`), and the static `front/` folder served by `nginx`. No new module.

## Implementation Order

1. **Contract**: merge `contracts/openapi-auth.yaml` into `api.yaml`; `mvn generate-sources`. Fix `ReaderService` for the now-optional `apitoken` (still set for everyone at this step).
2. **Users**: `Role`, `UserEntity`, `UserRepository`, `AppUserDetailsService`, `BootstrapAdminRunner`, `test` profile.
3. **Security chains**: rewrite `SecurityConfig` (two chains, matrix, CSRF, session registry); stop global registration of the reader filter. At this point every existing page breaks until step 5 — do steps 3 to 5 in the same change set.
4. **Auth and user APIs**: `AuthService` + `AuthController` (with explicit session strategy, research R2), `UserService` + `UserController`, `apitoken` hidden for Opérateurs.
5. **Front**: `auth.js`, `login.html`, `users.html`, update the four existing pages; remove the hardcoded token.
6. **Tests**: access-matrix, auth-flow and user-management tests; run `mvn clean install`.
7. **Deploy**: bootstrap-admin variables in `deploy/`; walk through `quickstart.md`.

## Dependencies on Other Specs

- Unblocks: `005` (override history needs an author), `002` (token rotation needs an Administrateur), `003` and `006` (their new front pages need login).
- After this feature, the leaked token from `front/index.html` is removed from the file but still valid: rotate it once `002`'s rotation route exists (spec `007`).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations.
