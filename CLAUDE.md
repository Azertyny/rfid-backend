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
mvn test              # run all tests
mvn test -Dtest=TagServiceTest                 # run a single test class
mvn test -Dtest=TagServiceTest#methodName       # run a single test method
mvn spring-boot:run   # run the app locally (dev profile active by default, port 8080)
```

Frontend (`/front`) is plain static HTML/JS with no build step — served via `deploy/front` (nginx config)
or opened directly; talks to the backend through `front/config.js`.

## Architecture

### API-first via OpenAPI codegen — this is the part that isn't obvious from browsing controllers

`src/main/resources/openapi/api.yaml` is the source of truth for the REST API. The `openapi-generator-maven-plugin`
(configured in `pom.xml`, runs on `generate-sources`) generates, into `target/generated-sources/openapi`:
- `com.rfidback.generated.api.*ApiDelegate` interfaces (one per OpenAPI tag: Reader, Picker, Tag, Bucket, Record)
- `com.rfidback.generated.model.*` request/response DTOs

Hand-written controllers in `src/main/java/com/rfidback/controller` implement the generated `*ApiDelegate`
interfaces (delegate pattern) rather than using `@RequestMapping` directly. **To change or add an endpoint, edit
`api.yaml` first**, then implement/update the corresponding method in the delegate-implementing controller — the
generated interface is what defines the method signature. Run `mvn generate-sources` (or any `mvn` build) to
regenerate before the IDE/compiler will recognize a changed signature.

### Layering

Standard layered structure under `src/main/java/com/rfidback/`:
`controller` (implements generated delegates) → `service` → `repository` (Spring Data JPA) → `entity`.
`security` holds reader-token authentication (`ReaderApiTokenAuthenticationFilter`, `ReaderAuthentication`); readers
authenticate as a distinct principal type from any admin/user login — controllers that act on behalf of a reader
pull it out of `SecurityContextHolder` as a `ReaderAuthentication` (see `TagController.scanTag`), not from
`@AuthenticationPrincipal` in the usual user sense. `configuration` holds `SecurityConfig` and `CorsConfig`.

### Persistence

H2 by default (dev), file-based at `./data/rfidbackdb.mv.db`; PostgreSQL driver is also on the classpath for other
profiles. `spring.jpa.hibernate.ddl-auto: update` — there is no migration tool (Flyway/Liquibase) yet, so schema
changes happen by editing entities and letting Hibernate update the schema at boot. Config is split across
`application.yml` (activates the `dev` profile) plus `application-dev.yml` / `application-prod.yml`.

### Docs

`/doc` has PlantUML diagrams (principle diagram, tag lifecycle, DB diagram) and `20251116-use_cases.md`
(French use-case specs for picker/tag/bucket management) — check these before assuming behavior for a flow that
isn't obvious from code alone.
