# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

RFID Back — a Spring Boot 3.5.7 (Java 21) backend for tracking fruit-harvest logistics via RFID. Domain
(entities/docs use French terms):

- **Picker** (cueilleur) — a harvester
- **Bucket** (seau) — container assigned to a picker
- **Tag** — RFID tag attached to a bucket
- **Reader** (lecteur) — RFID reader device that scans tags; authenticates via its own token, not a user login
- **Record** — a scan event linking tag/bucket/picker, carries a conformity flag that can be revised later

## Commands

```zsh
mvn clean install     # full build check (also regenerates OpenAPI sources)
mvn clean test        # run all tests
mvn clean test -Dtest=TagServiceTest            # run a single test class
mvn clean test -Dtest=TagServiceTest#methodName # run a single test method
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
                      # run locally (dev profile, port 8080); the variables create the first Administrateur
```

If tests fail with `NoClassDefFoundError` on a class name without its package (e.g. `TagRepository`) or an
"Unresolved compilation problem", it is not the code: the VS Code Java extension compiles into the same
`target/classes` and races Maven's build. Rerun `mvn clean test`, pause the extension, or build in a copy of the repo.

Tests run on the `test` profile (`src/test/resources/application-test.yml`, in-memory H2), never on `dev`, whose
H2 database is a file in the repo.

Frontend (`/front`) is plain static HTML/JS with no build step — served via `deploy/front` (nginx config)
or opened directly; talks to the backend through `front/config.js`.

## Architecture

### API-first via OpenAPI codegen — this is the part that isn't obvious from browsing controllers

`src/main/resources/openapi/api.yaml` is the source of truth for the REST API. The `openapi-generator-maven-plugin`
(configured in `pom.xml`, runs on `generate-sources`) generates, into `target/generated-sources/openapi`:
- `com.rfidback.generated.api.*ApiDelegate` interfaces (one per OpenAPI tag: Reader, Picker, Tag, Bucket, Record,
  Auth, User)
- `com.rfidback.generated.model.*` request/response DTOs

Hand-written controllers in `src/main/java/com/rfidback/controller` implement the generated `*ApiDelegate`
interfaces (delegate pattern) rather than using `@RequestMapping` directly. **To change or add an endpoint, edit
`api.yaml` first**, then implement/update the corresponding method in the delegate-implementing controller — the
generated interface is what defines the method signature. Run `mvn generate-sources` (or any `mvn` build) to
regenerate before the IDE/compiler will recognize a changed signature.

### Layering

Standard layered structure under `src/main/java/com/rfidback/`:
`controller` (implements generated delegates) → `service` → `repository` (Spring Data JPA) → `entity`.
`configuration` holds `SecurityConfig`, `PasswordConfig` and `CorsConfig`; `security` holds the authentication pieces.

### Security: two kinds of callers, two filter chains

`SecurityConfig` defines two `SecurityFilterChain`s:
- **Reader devices** (`@Order(1)`, only `POST /api/tags/scan`): stateless, `x-api-token` header checked by
  `ReaderApiTokenAuthenticationFilter`. Controllers acting for a reader take it from `SecurityContextHolder` as a
  `ReaderAuthentication` (see `TagController.scanTag`). This filter is a `@Component` whose automatic servlet
  registration is disabled on purpose, so it only runs inside its chain.
- **Human users** (`@Order(2)`, everything else): server-side session opened by `POST /api/auth/login`
  (`AuthService`), with two roles `ADMINISTRATEUR` / `OPERATEUR` (`entity/Role`, users in table `app_user`). The
  route × role matrix lives in this chain's `authorizeHttpRequests` (spec `.specify/specs/008-*/spec.md`); unlisted
  routes are denied. Writes need CSRF: the `XSRF-TOKEN` cookie echoed as the `X-XSRF-TOKEN` header, which
  `front/auth.js` (`apiFetch`) does for every page.

The first Administrateur is created at startup from `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD`
(`BootstrapAdminRunner`) when no enabled Administrateur exists; others are managed through `/api/users`. Disabling
a user, changing their role or resetting their password expires their open sessions (`SessionRegistry`).

### Persistence

H2 by default (dev), file-based at `./data/rfidbackdb.mv.db`; PostgreSQL driver is also on the classpath for other
profiles. `spring.jpa.hibernate.ddl-auto: update` — there is no migration tool (Flyway/Liquibase) yet, so schema
changes happen by editing entities and letting Hibernate update the schema at boot. Config is split across
`application.yml` (activates the `dev` profile) plus `application-dev.yml` / `application-prod.yml`.

### Docs

`/doc` has PlantUML diagrams (principle diagram, tag lifecycle, DB diagram) and `20251116-use_cases.md`
(French use-case specs for picker/tag/bucket management) — check these before assuming behavior for a flow that
isn't obvious from code alone.
