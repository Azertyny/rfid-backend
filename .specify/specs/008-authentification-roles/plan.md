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
│   ├── openapi-kiosk.yaml  # Amendment 2026-09-25: kiosk changes to merge into api.yaml
│   └── front-auth.md       # Front behaviour contract (+ kiosk mode)
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

---

## Amendment 2026-09-25: line kiosk with the reader token

**Spec**: Clarifications 2026-09-25, FR-005a, FR-005b, US2 scenarios 6-7, SC-006; spec `005` FR-004 and US2 scenario 7.
**Research**: R11-R16. **Design**: [data-model.md](data-model.md#amendment-2026-09-25-kiosk-with-the-reader-token),
[contracts/openapi-kiosk.yaml](contracts/openapi-kiosk.yaml), [contracts/front-auth.md](contracts/front-auth.md),
[quickstart.md](quickstart.md#8-line-kiosk-with-the-reader-token-amendment-2026-09-25). The rest of this plan is delivered.

### Summary

The touch screen of a line (`reader.html` on the kiosk computer) works without a user login: it sends its line reader's
`x-api-token`. A third, stateless security chain takes every `/api/**` request carrying that header, allows only the
two record routes of `reader.html`, and `RecordService` limits them to the token's own reader. Conformity changes made
this way are credited to the reader, so the conformity history's author becomes "user or reader". The kiosk gets the
token from its launcher through the URL fragment; `front/` still holds no secret.

### Technical Context (changes only)

- **Target Platform** (supersedes the original section above): HTTPS behind Caddy (`deploy/web/Caddyfile`, spec `009`),
  no longer plain HTTP behind `nginx`. `/api/*` over plain HTTP is refused, so the kiosk token, sent on every call,
  never travels in clear. Caddy has no access log today; if one is enabled, `x-api-token` must be excluded (SC-006).
- **Storage**: `record_conformity_change` gets `author_reader_id` (added by Hibernate) and `author_id` becomes nullable
  through an idempotent startup statement, because `ddl-auto: update` never alters an existing column (research R14).
- **Performance**: one indexed lookup by `apitoken` per kiosk call, 2 per second per kiosk (research R16). No new target.
- **Constraints**: reader devices unchanged (SC-004); user chain and its matrix unchanged; token never in `front/` files
  or server logs (SC-003, SC-006).
- **Scale/Scope**: 2 routes opened to reader tokens, 1 schema changed (`ConformityChange`), 1 column added and 1 relaxed,
  1 new chain, 1 runner, 2 front files, 1 deploy doc section; ~5 test classes new or extended.

### Constitution Check (amendment)

Constitution still the unfilled template; gates from `CLAUDE.md` as above.

| Gate | Pre-design | Post-design |
|---|---|---|
| Layering | Pass | Pass: ownership rule in `RecordService`, chain in `SecurityConfig`, runner in `configuration` |
| API-first | Pass | Pass: `security` on the two operations and the `ConformityChange` change go into `api.yaml` first ([openapi-kiosk.yaml](contracts/openapi-kiosk.yaml)) |
| `mvn clean install` passes | Pass | Pass (planned): kiosk cases added to the access matrix, schema runner tested on H2 |

One deliberate exception to "no migration tool": the startup runner of R14, justified below.

### Source changes

```text
src/main/resources/openapi/api.yaml                  # merge openapi-kiosk.yaml (2 operations' security, ConformityChange)
src/main/java/com/rfidback/
├── configuration/
│   ├── SecurityConfig.java                          # + kioskSecurityFilterChain @Order(2); user chain → @Order(3)
│   └── ConformityAuthorSchemaUpgrade.java           # new: ApplicationRunner, DROP NOT NULL on author_id (R14)
├── security/ReaderApiTokenAuthenticationFilter.java # drop its own /api/tags/scan path check (R11)
├── entity/RecordConformityChangeEntity.java         # author optional, + authorReader, @PrePersist invariant
└── service/RecordService.java                       # own-reader checks (R12); author = user or reader; history mapping
src/test/java/com/rfidback/
├── security/AccessMatrixSecurityTest.java           # + profile READER_TOKEN (own reader / other reader)
├── security/KioskReaderTokenSecurityTest.java       # new: 200/204/401/403, no Set-Cookie, no CSRF needed, author READER
├── security/ReaderScanSecurityTest.java             # still green (scan unchanged)
├── service/RecordServiceTest.java                   # + own-reader and author cases
└── configuration/ConformityAuthorSchemaUpgradeTest.java # new: NOT NULL column becomes nullable, rerun is harmless
front/
├── auth.js                                          # kiosk mode (fragment → sessionStorage, x-api-token, 401 screen)
└── reader.html                                      # kiosk mode: skip selection and requireRole
deploy/INSTALL.md                                    # kiosk section (launcher example, rotation), rollback caveat (R14)
CLAUDE.md                                            # Security: the reader token also serves the line kiosk
```

### Implementation order

1. **Contract**: merge [openapi-kiosk.yaml](contracts/openapi-kiosk.yaml); `mvn generate-sources`; map `authorType` in
   `RecordService.toConformityChange` (all existing rows are `USER`).
2. **Schema**: entity change + `ConformityAuthorSchemaUpgrade` and its test.
3. **Security**: kiosk chain, filter path check removed; extend `AccessMatrixSecurityTest` first and see it fail.
4. **Service**: own-reader checks and reader author in `RecordService` (tests first).
5. **Front**: `auth.js` kiosk mode, `reader.html`.
6. **Docs**: `deploy/INSTALL.md` kiosk section and rollback caveat, `CLAUDE.md`; walk through quickstart step 8.

### Dependencies on other specs

- Spec `005`: FR-004 and the history's author (already aligned in its spec, marked "à livrer").
- Spec `002`: rotating a reader's token also cuts off its kiosk until its config file is updated (spec 008 edge case).
- Spec `003`: a reader in registration mode can still serve a kiosk; its record list is simply empty. No special case.

### Complexity Tracking (amendment)

| Violation | Why needed | Simpler alternative rejected because |
|---|---|---|
| Startup SQL runner despite "schema changes through entities only" (`CLAUDE.md`) | `ddl-auto: update` cannot relax `author_id`'s `NOT NULL`; without it the first kiosk change fails with `500` in production | Manual SQL step: easy to forget on deploy. Flyway: needs a baseline of the existing production schema, out of scope |

