---

description: "Task list for 008 — Authentification et rôles"
---

# Tasks: Authentification et rôles

**Input**: Design documents from `.specify/specs/008-authentification-roles/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/](contracts/), [quickstart.md](quickstart.md)

**Tests**: Included. The spec requires it (SC-001: "vérifié par des tests automatisés") and research R10 defines the test approach. Write each story's tests first and make sure they fail before implementing.

**Organization**: Tasks are grouped by user story so each story can be implemented and tested as its own increment.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an unfinished task)
- **[Story]**: User story from spec.md (US1, US2, US3)
- Paths are relative to the repository root. Java sources: `src/main/java/com/rfidback/`; tests: `src/test/java/com/rfidback/`; front: `front/`.

## Conventions used by every task

- API-first: any new route or schema change goes into `src/main/resources/openapi/api.yaml` first, then `mvn generate-sources`; controllers implement the generated `*ApiDelegate` (see `controller/TagController.java`).
- Follow the existing style: Lombok (`@RequiredArgsConstructor`, `@Builder`, `@Getter`/`@Setter`), exceptions annotated with `@ResponseStatus` (see `exception/PickerAlreadyExistsException.java`).
- Integration tests: `@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles("test")`, using `spring-security-test` helpers (`user(...)`, `csrf()`), already in `pom.xml`.
- Usernames are normalized with `trim().toLowerCase(Locale.ROOT)` everywhere (storage, login, lookup).

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: test isolation, configuration and contract changes that everything else builds on.

- [X] T001 Create `src/test/resources/application-test.yml`: H2 in-memory datasource (`jdbc:h2:mem:rfidback-test;DB_CLOSE_DELAY=-1`, driver `org.h2.Driver`), `spring.jpa.hibernate.ddl-auto: create-drop`, `app.cors.allowed-origins: "*"`, `app.security.allow-h2-console: false`, and empty `app.security.bootstrap-admin.username` / `.password`, so tests never touch the file database `./data/rfidbackdb`
- [X] T002 Switch `src/test/java/com/rfidback/RfidBackApplicationTests.java` from `@ActiveProfiles("dev")` to `@ActiveProfiles("test")`
- [X] T003 [P] In `src/main/resources/application.yml`, add `app.security.bootstrap-admin.username: ${APP_BOOTSTRAP_ADMIN_USERNAME:}` and `app.security.bootstrap-admin.password: ${APP_BOOTSTRAP_ADMIN_PASSWORD:}`, and session cookie settings `server.servlet.session.timeout: 30m`, `server.servlet.session.cookie.http-only: true`, `server.servlet.session.cookie.same-site: lax`
- [X] T004 [P] In `deploy/docker-compose.yml` pass `APP_BOOTSTRAP_ADMIN_USERNAME` and `APP_BOOTSTRAP_ADMIN_PASSWORD` to the `app` service environment, and document both variables (placeholder values only, no real secret) in `deploy/.env.example` and `deploy/.env.local.example`
- [X] T005 Merge `.specify/specs/008-authentification-roles/contracts/openapi-auth.yaml` into `src/main/resources/openapi/api.yaml`: add paths `/auth/login`, `/auth/logout`, `/auth/me` (tag `Auth`) and `/users`, `/users/{userId}`, `/users/{userId}/password` (tag `User`); add schemas `Role`, `LoginRequest`, `CurrentUser`, `CreateUser`, `UpdateUser`, `ResetPassword`, `User`, `UsersList`; add response `Forbidden`; add security scheme `SessionCookie`; add root-level `security: [SessionCookie: []]`; set `security: []` on `/auth/login`; keep `security: [ReaderApiToken: []]` on `/tags/scan`; remove `apitoken` from the `required` list of the existing `Reader` schema
- [X] T006 Run `mvn generate-sources` then `mvn -q compile`; fix any compile error in `service/ReaderService.java` or `controller/ReaderController.java` caused by the regenerated `Reader` model (behaviour unchanged at this step: `apitoken` is still set for every caller)

**Checkpoint**: project compiles, `mvn test` passes on the in-memory `test` profile, generated `AuthApiDelegate` and `UserApiDelegate` exist under `target/generated-sources/openapi`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: user store, password hashing, first Administrateur and the two security chains. Every user story depends on this phase.

**⚠️ CRITICAL**: at the end of this phase every route except `POST /api/auth/login`, `/actuator/health` and `POST /api/tags/scan` requires a logged-in user, so the existing front pages stop working until Phase 3 (US1) is done. Deliver Phases 2 and 3 in the same change set (plan.md, Implementation Order step 3).

- [X] T007 [P] Create enum `src/main/java/com/rfidback/entity/Role.java` with values `ADMINISTRATEUR`, `OPERATEUR` and a method `authority()` returning `"ROLE_" + name()`
- [X] T008 [P] Create `src/main/java/com/rfidback/entity/UserEntity.java` per data-model.md: `@Table(name = "app_user")`, `id` UUID (`GenerationType.UUID`), `username` (length 50, unique, not null), `passwordHash` (length 100, not null), `role` (`@Enumerated(EnumType.STRING)`, not null), `enabled` (not null), `creationDate` (`@CreationTimestamp`, not updatable), `updateDate` (`@UpdateTimestamp`); same Lombok annotations as `entity/ReaderEntity.java`
- [X] T009 Create `src/main/java/com/rfidback/repository/UserRepository.java` (`JpaRepository<UserEntity, UUID>`) with `Optional<UserEntity> findByUsername(String username)`, `boolean existsByUsername(String username)`, `long countByRoleAndEnabledTrue(Role role)`, `List<UserEntity> findAllByOrderByUsernameAsc()`
- [X] T010 Create `src/main/java/com/rfidback/configuration/PasswordConfig.java` exposing a `PasswordEncoder` bean built with `PasswordEncoderFactories.createDelegatingPasswordEncoder()` (separate class to avoid a circular dependency with `SecurityConfig`)
- [X] T011 Create `src/main/java/com/rfidback/security/AppUserDetailsService.java` implementing `UserDetailsService`: normalize the username, load it with `UserRepository.findByUsername`, return `org.springframework.security.core.userdetails.User` with the password hash, authority `role.authority()` and `disabled(!enabled)`; throw `UsernameNotFoundException` when absent
- [X] T012 Create `src/main/java/com/rfidback/security/BootstrapAdminRunner.java` (`ApplicationRunner`, `@Component`) reading `app.security.bootstrap-admin.username` / `.password`: if `countByRoleAndEnabledTrue(ADMINISTRATEUR) == 0` and both values are non-blank, save an enabled `ADMINISTRATEUR` with the normalized username and the encoded password and log `Bootstrap administrator '<username>' created`; if no enabled Administrateur exists and a value is blank, log a WARN explaining that nobody can log in; otherwise do nothing. Never log the password
- [X] T013 Create `src/main/java/com/rfidback/security/CsrfCookieFilter.java` (`OncePerRequestFilter`) that reads the `CsrfToken` request attribute and calls `getToken()` so the `XSRF-TOKEN` cookie is written on the first response (research R4)
- [X] T014 Rewrite `src/main/java/com/rfidback/configuration/SecurityConfig.java` (research R3, R4, R6) with:
  - beans `SessionRegistry` (`SessionRegistryImpl`), `HttpSessionEventPublisher`, `CookieCsrfTokenRepository` (`withHttpOnlyFalse()`, shared instance), `SecurityContextRepository` (`HttpSessionSecurityContextRepository`), `AuthenticationManager` (`ProviderManager` over a `DaoAuthenticationProvider` using `AppUserDetailsService` and the `PasswordEncoder`), and a `SessionAuthenticationStrategy` bean = `CompositeSessionAuthenticationStrategy` of `ConcurrentSessionControlAuthenticationStrategy(registry)` with `setMaximumSessions(-1)`, `ChangeSessionIdAuthenticationStrategy`, `RegisterSessionAuthenticationStrategy(registry)` and `CsrfAuthenticationStrategy(csrfTokenRepository)`;
  - a `FilterRegistrationBean<ReaderApiTokenAuthenticationFilter>` with `setEnabled(false)` so Spring Boot stops registering that `@Component` as a global servlet filter;
  - chain `@Order(1)` with `securityMatcher("/api/tags/scan")`: stateless session, CSRF disabled, `ReaderApiTokenAuthenticationFilter` added before `UsernamePasswordAuthenticationFilter`, `anyRequest().authenticated()`, entry point `HttpStatusEntryPoint(UNAUTHORIZED)`;
  - chain `@Order(2)` for everything else: `cors(withDefaults())`; CSRF with the shared repository and a `CsrfTokenRequestAttributeHandler`, ignoring `POST /api/auth/login` (and `/h2-console/**` only when `app.security.allow-h2-console` is true); `CsrfCookieFilter` added after `BasicAuthenticationFilter`; session management `IF_REQUIRED`, `sessionFixation().changeSessionId()`, `maximumSessions(-1).sessionRegistry(registry).expiredSessionStrategy(...)` writing status 401; `formLogin` and `httpBasic` disabled; entry point `HttpStatusEntryPoint(UNAUTHORIZED)`; authorization: `dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.FORWARD).permitAll()` (so `@ResponseStatus` errors such as 404 are not turned into 403), `OPTIONS /**` permitAll, `POST /api/auth/login` permitAll, `/actuator/health` permitAll, `/h2-console/**` permitAll when allowed, frame options disabled when allowed (as today), `anyRequest().authenticated()` (the role matrix is added in US2);
  - one short English comment above the two chains explaining why readers and users have separate chains

**Checkpoint**: the app starts; with `APP_BOOTSTRAP_ADMIN_*` set, the first Administrateur is created once; anonymous `GET /api/pickers` → 401; `POST /api/tags/scan` with a valid reader token still works.

---

## Phase 3: User Story 1 — Se connecter et se déconnecter (Priority: P1) 🎯 MVP

**Goal**: users log in with username and password, get a session, can call `GET /api/auth/me`, and log out; every front page sends users to a login page when they are not logged in; no reader token left in the front.

**Independent Test**: start with `APP_BOOTSTRAP_ADMIN_*` set, log in as that Administrateur (quickstart.md step 3), call `GET /api/auth/me`, log out, check `GET /api/auth/me` → 401; in a browser, `http://localhost/` redirects to `login.html` and works after login.

### Tests for User Story 1 ⚠️ (write first, must fail)

- [X] T015 [P] [US1] Create `src/test/java/com/rfidback/security/AuthFlowSecurityTest.java` (fixture: an enabled Administrateur and a disabled Opérateur saved through `UserRepository` with the `PasswordEncoder`, cleaned between tests): anonymous `GET /api/auth/me` → 401; login with good credentials → 200 with `username` and `role`, and a session; login with a wrong password and login with the disabled account → both 401 with the same body; login with `"  ADMIN "` style username (spaces, upper case) succeeds; the session id changes at login (session fixation); logout with `csrf()` → 204, then `GET /api/auth/me` with the same session → 401; an authenticated `POST` without a CSRF token → 403
- [X] T016 [P] [US1] Create `src/test/java/com/rfidback/security/ReaderScanSecurityTest.java`: with a `ReaderEntity` saved through `ReaderRepository`, `POST /api/tags/scan` with its `x-api-token` → 200 and no `Set-Cookie` header and no CSRF token needed; missing token → 401; unknown token → 401 (spec SC-004)
- [X] T017 [P] [US1] Create `src/test/java/com/rfidback/security/BootstrapAdminRunnerTest.java` (Mockito, like `service/TagServiceTest.java`): creates the admin when none exists and both values are set (username normalized, password encoded, role `ADMINISTRATEUR`, enabled); does nothing when an enabled Administrateur exists; does nothing when a value is blank

### Implementation for User Story 1

- [X] T018 [P] [US1] Create `src/main/java/com/rfidback/exception/InvalidCredentialsException.java` annotated `@ResponseStatus(HttpStatus.UNAUTHORIZED)`, with one generic message used for every login failure (never say whether the username or the password was wrong)
- [X] T019 [US1] Create `src/main/java/com/rfidback/service/AuthService.java` (research R2): `login(username, password, request, response)` authenticates a `UsernamePasswordAuthenticationToken.unauthenticated(normalizedUsername, password)` with the `AuthenticationManager` (any `AuthenticationException` → `InvalidCredentialsException`), then calls the `SessionAuthenticationStrategy` bean, builds a new `SecurityContext`, sets it in `SecurityContextHolder` and saves it with the `SecurityContextRepository`, and returns a generated `CurrentUser`; `logout(request, response, authentication)` delegates to `SecurityContextLogoutHandler`; `currentUser()` reads the username and role from `SecurityContextHolder`
- [X] T020 [US1] Create `src/main/java/com/rfidback/controller/AuthController.java` (`@Service`, implements `AuthApiDelegate`): obtain the `HttpServletRequest` / `HttpServletResponse` from `RequestContextHolder` (implemented this way: the generated controller never passes a request to the delegate, so `getRequest()` is always empty); `login` → 200 `CurrentUser`; `logout` → 204; `getCurrentUser` → 200
- [X] T021 [P] [US1] Create `front/auth.js` per `contracts/front-auth.md`: `getCookie(name)`; `apiFetch(path, options)` prefixing `CONFIG.API_URL`, adding `X-XSRF-TOKEN` from the `XSRF-TOKEN` cookie on POST/PUT/PATCH/DELETE, redirecting to `login.html?next=<encoded current path>` on 401, showing an "Accès refusé" alert on 403, unless `options.silent` is true (for background polling), and returning the `Response` otherwise; `requireRole(...roles)` calling `GET /auth/me` (redirect to login on 401; if `roles` is non-empty and the user's role is not in it, replace the page body with an "Accès refusé" message) and resolving with the current user; `logout()` posting `/auth/logout` then going to `login.html`
- [X] T022 [P] [US1] Create `front/login.html` (Bootstrap 5.3.0 from the same CDN as `front/pickers.html`, loads `config.js`): username/password form posting JSON to `CONFIG.API_URL + "/auth/login"` with `fetch`; on 200 go to the `next` query parameter only if it is a relative path of an existing page (starts with a page name, no `//` or scheme, to avoid open redirects), otherwise to `reader.html` for `OPERATEUR` and `index.html` for `ADMINISTRATEUR`; on 401 show "Identifiant ou mot de passe incorrect"
- [X] T023 [US1] Update `front/pickers.html` and `front/readers.html`: add `<script src="config.js">` then `<script src="auth.js">`; replace every `fetch(\`${API_URL}...\`)` with `apiFetch(...)` on the matching path and drop the local `API_URL` constant; call `requireRole()` (any logged-in user for now) before the first data load; add a "Déconnexion" button calling `logout()` in the existing navbar
- [X] T024 [US1] Update `front/index.html`: remove `const API_TOKEN = "176c77ca..."` (line 155) and every `'x-api-token'` header; add `config.js` and `auth.js`; use `apiFetch` for `/readers` and `/pickers?size=500`; stop polling `/tags`: remove the `setInterval(fetchData, 3000)` call and the initial `fetchData()`, and show the "Erreur API" status once on load, since `GET /api/tags` doesn't exist and the dashboard's data source stays deferred (spec `007`). Otherwise an Opérateur would get a 403 every 3 s, because `/api/tags/**` is Administrateur-only (T028); call `requireRole()` before the first load; add the "Déconnexion" button to the navbar
- [X] T025 [US1] Update `front/reader.html`: load `auth.js` after `config.js`; use `apiFetch` for `/readers`, `/records/readers/{uid}` and `/records/{id}/conformity`; remove the `x-api-token` headers and `CURRENT_READER.token`; delete the `if (!reader.apitoken)` check in `selectReader` (lines 156-159) so a reader can be selected without a token; call `requireRole()` before `loadReaders()`

**Checkpoint**: MVP. Only the bootstrap Administrateur exists; after login every page works as before; logout works; `grep -rn "x-api-token\|API_TOKEN" front/` returns nothing.

---

## Phase 4: User Story 2 — Accès selon le rôle (Priority: P1)

**Goal**: enforce the access matrix of spec.md: Opérateurs can read pickers and readers (without tokens), use records and the dashboard, and nothing else.

**Independent Test**: the access-matrix test passes for anonymous, Opérateur and Administrateur on every route; manually, an Opérateur (created by US3, or inserted with `UserRepository` in a test) gets `GET /api/readers` without `apitoken` and 403 on `POST /api/pickers` (quickstart.md step 4).

### Tests for User Story 2 ⚠️ (write first, must fail)

- [X] T026 [P] [US2] Create `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java`: a `@ParameterizedTest` with a `@MethodSource` listing every route of the spec matrix (method, path with a random UUID or number where needed, minimal JSON body) × profile (anonymous, `user("op").roles("OPERATEUR")`, `user("admin").roles("ADMINISTRATEUR")`, always with `csrf()`, plus one extra case: anonymous `POST /api/pickers` **without** `csrf()` → 403 (spec FR-004); expected outcome per cell: `401` for anonymous on protected routes, `403` when the role is not allowed, and for allowed cells only assert the status is neither 401 nor 403 (the controller may answer 404/400 for fake ids). Cover at least: `GET /api/pickers`, `GET /api/pickers/{id}`, `POST /api/pickers`, `PUT /api/pickers/{id}`, `DELETE /api/pickers/{id}`, `GET /api/readers`, `POST /api/readers`, `POST /api/tags/buckets/{n}`, `GET /api/buckets`, `GET /api/buckets/{id}`, `PUT /api/buckets/{id}/picker`, `GET /api/records/readers/{uid}`, `PATCH /api/records/{id}/conformity`, `GET /api/users`, `POST /api/users`, `PATCH /api/users/{id}`, `PUT /api/users/{id}/password`, `GET /api/auth/me`, `POST /api/auth/logout`, `GET /actuator/health`
- [X] T027 [P] [US2] Create `src/test/java/com/rfidback/security/ReaderTokenVisibilityTest.java`: with one saved reader, `GET /api/readers` as `OPERATEUR` → 200 and `$.readers[0].apitoken` does not exist; as `ADMINISTRATEUR` → `$.readers[0].apitoken` exists (spec FR-006, SC-002)

### Implementation for User Story 2

- [X] T028 [US2] In `src/main/java/com/rfidback/configuration/SecurityConfig.java` chain `@Order(2)`, replace `anyRequest().authenticated()` with the matrix, most specific rules first: `/api/users/**` → `hasRole("ADMINISTRATEUR")`; `GET /api/pickers` and `GET /api/pickers/*` → `hasAnyRole("ADMINISTRATEUR","OPERATEUR")`; `/api/pickers/**` → `hasRole("ADMINISTRATEUR")`; `GET /api/readers` → both roles; `/api/readers/**` → `ADMINISTRATEUR`; `/api/tags/**` → `ADMINISTRATEUR` (the scan route is handled by chain 1); `/api/buckets/**` → `ADMINISTRATEUR`; `/api/records/**` → both roles; `/api/auth/**` → `authenticated()`; `anyRequest().denyAll()` (keep the ERROR/FORWARD dispatcher, OPTIONS, login, health and h2-console rules from T014 above these)
- [X] T029 [US2] In `src/main/java/com/rfidback/service/ReaderService.java`, `getReaders()` sets `apitoken` only when the current `Authentication` (from `SecurityContextHolder`) has authority `ROLE_ADMINISTRATEUR`; `createReader` keeps returning it (route already restricted to Administrateurs)
- [X] T030 [US2] Narrow the front checks: `requireRole('ADMINISTRATEUR')` in `front/pickers.html` and `front/readers.html`; `requireRole('ADMINISTRATEUR','OPERATEUR')` in `front/index.html` and `front/reader.html`; in the navbars of `front/index.html`, `front/pickers.html` and `front/readers.html`, hide the `pickers.html` and `readers.html` links when the current user is an `OPERATEUR` (cosmetic only, the API enforces access)

**Checkpoint**: access matrix enforced; Opérateurs never receive a reader token; US1 still works.

---

## Phase 5: User Story 3 — Gérer les comptes (Priority: P2)

**Goal**: Administrateurs list, create, enable/disable users, change roles and reset passwords; disabling, role change and password reset close the user's sessions; the last enabled Administrateur is protected.

**Independent Test**: as the bootstrap Administrateur, create an Opérateur, log in with it, disable it from another session, and check its session now gets 401 and a new login fails (quickstart.md steps 3 and 6).

### Tests for User Story 3 ⚠️ (write first, must fail)

- [X] T031 [P] [US3] Create `src/test/java/com/rfidback/security/UserManagementSecurityTest.java` (logged in as an Administrateur, with `csrf()`): create an Opérateur → 201, body has no password field; create `"OP1"` when `"op1"` exists → 409; invalid username or 7-character password → 400; `GET /api/users` lists users sorted by username; `PATCH` with an empty body → 400; disable a user → that user's existing `MockHttpSession` (obtained by logging in) now gets 401 on `GET /api/auth/me` and a new login gets 401; change a user's role → their existing session gets 401; reset a password → old password 401, new password 200; disable or demote the last enabled Administrateur → 409; unknown user id → 404
- [X] T032 [P] [US3] Create `src/test/java/com/rfidback/service/UserServiceTest.java` (Mockito): username normalization on create; last-Administrateur guard for disable and for role change (only when the target is an enabled Administrateur and `countByRoleAndEnabledTrue(ADMINISTRATEUR) == 1`); `SessionRegistry` sessions of the target user are expired on disable, role change and password reset

### Implementation for User Story 3

- [X] T033 [P] [US3] Create `src/main/java/com/rfidback/exception/UserNotFoundException.java` (`@ResponseStatus(NOT_FOUND)`), `UserAlreadyExistsException.java` (`CONFLICT`) and `LastAdministratorException.java` (`CONFLICT`) in `src/main/java/com/rfidback/exception/`
- [X] T034 [US3] Create `src/main/java/com/rfidback/service/UserService.java`: `listUsers()`; `createUser(CreateUser)` (normalize username, `existsByUsername` → `UserAlreadyExistsException`, encode password, enabled by default); `updateUser(UUID, UpdateUser)` (both fields null → `ResponseStatusException(BAD_REQUEST)`; last-Administrateur guard → `LastAdministratorException`; apply changes; expire sessions); `resetPassword(UUID, ResetPassword)` (encode, save, expire sessions); private `expireSessions(String username)` iterating `SessionRegistry.getAllPrincipals()`, matching `UserDetails.getUsername()`, and calling `expireNow()` on `getAllSessions(principal, false)`; `@Transactional` on write methods; mapping to the generated `User` model never exposes `passwordHash`
- [X] T035 [US3] Create `src/main/java/com/rfidback/controller/UserController.java` (`@Service`, implements `UserApiDelegate`): `listUsers` → 200, `createUser` → 201, `updateUser` → 200, `resetUserPassword` → 204
- [X] T036 [P] [US3] Create `front/users.html` (same layout and navbar as `front/pickers.html`, loads `config.js` and `auth.js`, `requireRole('ADMINISTRATEUR')`): table of users (username, role, enabled, creation date); "Nouvel utilisateur" modal (username, password, role) → `POST /users`; role select and enable/disable switch per row → `PATCH /users/{id}`; "Réinitialiser le mot de passe" prompt → `PUT /users/{id}/password`; show the API message on 409 (duplicate username, last Administrateur) and 400
- [X] T037 [US3] Add an "Utilisateurs" link to `users.html` in the navbars of `front/index.html`, `front/pickers.html` and `front/readers.html`, hidden for `OPERATEUR` like the links handled in T030

**Checkpoint**: all three stories work; an Administrateur can create Opérateurs, and US2 can be validated manually end to end.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T038 [P] Update `CLAUDE.md` (Architecture section): two security chains (reader token for `/api/tags/scan`, user session elsewhere), roles and where the matrix lives (`SecurityConfig`), CSRF cookie/header for front calls, `APP_BOOTSTRAP_ADMIN_*`, and the `test` profile for tests
- [X] T039 [P] Update `deploy/INSTALL.md`: set `APP_BOOTSTRAP_ADMIN_USERNAME` / `APP_BOOTSTRAP_ADMIN_PASSWORD` before the first start, first login at `http://<host>/login.html`, change the bootstrap password afterwards, and the known risk that credentials travel in clear over HTTP on the line network
- [X] T040 Check that no secret remains in the front: `grep -rn "x-api-token\|API_TOKEN\|apitoken" front/` only matches the Administrateur-only token column in `front/readers.html` (spec SC-003)
- [X] T041 Run `mvn clean install` (all tests green), then walk through `.specify/specs/008-authentification-roles/quickstart.md` steps 2 to 7 against the Docker deployment

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependency. T005 → T006 in order; T001 → T002.
- **Foundational (Phase 2)**: depends on Phase 1. T007/T008 → T009 → T011 → T012; T010 before T011/T012; T013 and T011 before T014.
- **US1 (Phase 3)**: depends on Phase 2. Must ship together with Phase 2, because Phase 2 locks every page.
- **US2 (Phase 4)**: depends on Phase 3 (the front changes in T030 build on T023-T025).
- **US3 (Phase 5)**: depends on Phase 2 for the backend (T031-T035) and on Phase 3 for the front (T036-T037, which use `auth.js`).
- **Polish (Phase 6)**: after the desired stories.

### User Story Dependencies

- **US1 (P1)**: independent once Phase 2 is done; it is the MVP.
- **US2 (P1)**: builds on US1's front files; its automated tests do not need US3 (they use mock users), but manual testing needs an Opérateur account, which only US3 can create through the app.
- **US3 (P2)**: backend is independent of US1/US2; its page reuses `auth.js` from US1.

### Within Each User Story

- Tests first, and they must fail.
- Exceptions and services before controllers; backend before front.
- Same-file tasks are sequential: `SecurityConfig.java` (T014 → T028), `front/index.html` (T024 → T030 → T037), `front/pickers.html` and `front/readers.html` (T023 → T030 → T037), `front/reader.html` (T025 → T030).

### Parallel Opportunities

- Phase 1: T003 and T004 in parallel with T001/T002 and T005.
- Phase 2: T007, T008, T010, T013 in parallel.
- US1: tests T015-T017 in parallel; T018, T021, T022 in parallel with each other.
- US2: T026 and T027 in parallel.
- US3: T031, T032, T033, T036 in parallel; US3 backend (T031-T035) can run in parallel with US2 once Phase 3 is done.
- Polish: T038 and T039 in parallel.

---

## Parallel Example: User Story 1

```bash
# Tests first, together:
Task: "AuthFlowSecurityTest in src/test/java/com/rfidback/security/AuthFlowSecurityTest.java"
Task: "ReaderScanSecurityTest in src/test/java/com/rfidback/security/ReaderScanSecurityTest.java"
Task: "BootstrapAdminRunnerTest in src/test/java/com/rfidback/security/BootstrapAdminRunnerTest.java"

# Then independent files, together:
Task: "InvalidCredentialsException in src/main/java/com/rfidback/exception/InvalidCredentialsException.java"
Task: "front/auth.js per contracts/front-auth.md"
Task: "front/login.html"
```

## Parallel Example: User Story 3

```bash
Task: "UserManagementSecurityTest in src/test/java/com/rfidback/security/UserManagementSecurityTest.java"
Task: "UserServiceTest in src/test/java/com/rfidback/service/UserServiceTest.java"
Task: "User exceptions in src/main/java/com/rfidback/exception/"
Task: "front/users.html"
```

---

## Implementation Strategy

### MVP First (Phases 1-3)

1. Phase 1: Setup (test profile, config, contract).
2. Phase 2: Foundational (user store, bootstrap admin, two security chains).
3. Phase 3: US1 (login/logout, front login, token removed from the front).
4. **STOP and VALIDATE**: quickstart.md steps 2, 3, 5 and 7 (login part). Only the bootstrap Administrateur exists, so no role issue can arise yet.
5. Deploy: this alone closes the anonymous access and removes the leaked token from the front (the token itself still has to be rotated later, spec `007`).

### Incremental Delivery

1. Setup + Foundational + US1 → MVP, deploy together.
2. + US2 → role matrix and hidden reader tokens (automated tests validate it; manual check once US3 exists).
3. + US3 → account management, Opérateurs can be created; validate US2 manually.
4. Polish → docs and full quickstart.

---

## Notes

- [P] = different files, no dependency on an unfinished task.
- Commit after each task or logical group; never commit Phase 2 without Phase 3.
- Out of scope here (other specs): reader token rotation (`002`), compliance override history (`005`), dashboard data source (`007`), registration mode and temporary reads (`003`), bucket and tag-registration pages (`003`, `006`).

## Phase 7: Convergence

- [X] T042 Omit the `apitoken` key entirely (not just `null`) from `GET /api/readers` responses for Opérateurs, scoped to the `Reader` model only (a global `spring.jackson.default-property-inclusion: non_null` would also drop other null fields such as `pickerId`), and make `src/test/java/com/rfidback/security/ReaderTokenVisibilityTest.java` assert the key is absent per FR-006 (partial)
- [X] T043 Review the bootstrap-username-already-taken guard in `src/main/java/com/rfidback/security/BootstrapAdminRunner.java` (skips creation and logs an error): either keep it and describe it as an edge case in spec/research R7, or remove it along with `doesNothing_whenBootstrapUsernameIsAlreadyTaken` in `BootstrapAdminRunnerTest` per plan: research R7 (unrequested)

---

# Amendment 2026-09-25: line kiosk with the reader token

**Input**: [plan.md § Amendment 2026-09-25](plan.md#amendment-2026-09-25-line-kiosk-with-the-reader-token), research
R11-R16, [data-model.md § Amendment](data-model.md#amendment-2026-09-25-kiosk-with-the-reader-token),
[contracts/openapi-kiosk.yaml](contracts/openapi-kiosk.yaml), [contracts/front-auth.md § Kiosk mode](contracts/front-auth.md),
[quickstart.md § 8](quickstart.md#8-line-kiosk-with-the-reader-token-amendment-2026-09-25).

**Story**: all of it serves **US2** (Accès selon le rôle), scenarios 6-7, FR-005a, FR-005b, SC-006, and spec `005`
FR-004 / US2 scenario 7. **Tests**: included, written first (same rule as above).

**Traps to keep in mind**:
- `ReaderAuthentication.getName()` is the `ReaderEntity`'s `toString()`, not a username: `RecordService` must test
  `instanceof ReaderAuthentication` **before** calling `currentUsername()`.
- The reader's public id in URLs is its `name` (`Reader.uid` in the API), not its UUID.
- Kiosk requests carry no CSRF header: never add `.with(csrf())` to kiosk requests in tests (it would hide a missing
  CSRF exemption), and remember `CLAUDE.md`'s warning about `csrf()` swapping the shared CSRF repository.

## Phase 8: Setup (contract)

- [X] T044 Merge [contracts/openapi-kiosk.yaml](contracts/openapi-kiosk.yaml) into `src/main/resources/openapi/api.yaml`: on `GET /records/readers/{readerId}` (`operationId: listLatestRecordsForReader`) and `PATCH /records/{recordId}/conformity` (`updateRecordConformity`) add `security: [ {SessionCookie: []}, {ReaderApiToken: []} ]` and replace their `description` with the contract's; in `components.schemas.ConformityChange` add `authorType` (enum `USER`, `READER`, required) and `authorReaderUid` (string), remove `authorUsername` from `required` and update its description; update `components.securitySchemes.ReaderApiToken.description` as in the contract. Run `mvn generate-sources` and check that `com.rfidback.generated.model.ConformityChange` now has `setAuthorType(ConformityChange.AuthorTypeEnum)` and `setAuthorReaderUid(String)`
- [X] T045 In `src/main/java/com/rfidback/service/RecordService.java` `toConformityChange`, set `authorType` to `USER` for every change (all existing rows have a user author; T052 adds `READER`), and in `src/test/java/com/rfidback/controller/RecordApiTest.java` `history_afterTwoChanges_returnsThemOldestFirst` also assert `$.changes[0].authorType == "USER"`. `mvn clean test -Dtest='RecordApiTest,RecordServiceTest'` must stay green

**Checkpoint**: contract generated, build green, behaviour unchanged.

---

## Phase 9: Foundational (history author can be a reader)

**⚠️ Blocks Phase 10**: the kiosk `PATCH` writes a change without a user author.

- [X] T046 [P] Create `src/test/java/com/rfidback/configuration/ConformityAuthorSchemaUpgradeTest.java` (plain JUnit, no Spring context): open an in-memory H2 `DriverManagerDataSource` (`jdbc:h2:mem:schema-upgrade-<random>;DB_CLOSE_DELAY=-1`), `create table record_conformity_change (id uuid primary key, author_id uuid not null)`, run `new ConformityAuthorSchemaUpgrade(new JdbcTemplate(dataSource)).run(null)`, then assert `insert into record_conformity_change (id, author_id) values (random_uuid(), null)` succeeds; run the upgrade a second time and assert it does not throw (idempotent, research R14). Must fail to compile until T048
- [X] T047 [P] In `src/main/java/com/rfidback/entity/RecordConformityChangeEntity.java`: `author` becomes `@ManyToOne(fetch = LAZY)` + `@JoinColumn(name = "author_id")` (no `optional = false`, no `nullable = false`); add `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "author_reader_id") private ReaderEntity authorReader;`; add a `@PrePersist void checkSingleAuthor()` throwing `IllegalStateException("A conformity change has exactly one author: a user or a reader")` unless exactly one of `author` / `authorReader` is non-null. Update the class comment: author is the logged-in user or, at the line kiosk, the reader (spec 008 FR-005a, research R13). No change to `RecordConformityChangeRepository` or to `TagService` (FR-006 check `existsByRecord` is author-agnostic)
- [X] T048 Create `src/main/java/com/rfidback/configuration/ConformityAuthorSchemaUpgrade.java`: `@Component` implementing `ApplicationRunner`, constructor-injected `JdbcTemplate`; `run` executes `ALTER TABLE record_conformity_change ALTER COLUMN author_id DROP NOT NULL` and logs at INFO `"record_conformity_change.author_id is nullable (spec 008, kiosk conformity changes)"`. Class comment: `ddl-auto: update` never relaxes an existing NOT NULL (research R14); remove this class once a migration tool (Flyway/Liquibase) manages the schema. Let an SQL failure propagate (startup must fail loudly rather than answer 500 on the first kiosk change). T046 must pass; `mvn clean test` green (the `test` profile uses `create-drop`, where the statement is a harmless no-op)

**Checkpoint**: a conformity change can be stored with a reader as author; existing databases are upgraded at startup.

---

## Phase 10: User Story 2 — Kiosk access with the reader token (Priority: P1)

**Goal**: `reader.html` on a line kiosk lists that line's records and changes their conformity with the reader's token, with no login; the token opens nothing else; changes are credited to the reader.

**Independent Test**: [quickstart.md § 8](quickstart.md#8-line-kiosk-with-the-reader-token-amendment-2026-09-25) curl table and history check.

### Tests for the kiosk ⚠️ (write first, must fail)

- [X] T049 [P] [US2] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java` add a parameterized test `readerToken_onOtherRoutes_returns403(Route route)` over the same route list (extract the list into a `static List<Route> routes()` used by both `matrix()` and the new source), skipping `GET /api/records/readers/*`, `PATCH /api/records/*/conformity` (covered by T050) and `/actuator/health` (outside `/api/**`, stays public). Each request carries `x-api-token` of a reader saved in `@BeforeEach` through `ReaderRepository` (unique name `"Matrix kiosk " + UUID.randomUUID()`), a JSON body when the route has one, **no** `csrf()` and **no** `user(...)`; expect `403`. Add `readerToken_unknown_returns401` (`GET /api/pickers` with `x-api-token: unknown-token` → `401`)
- [X] T050 [P] [US2] Create `src/test/java/com/rfidback/security/KioskReaderTokenSecurityTest.java` (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Transactional`, like `ReaderScanSecurityTest`): two readers `Kiosk L1` / `Kiosk L2` and one record each (build `TagEntity` + `RecordEntity` through their repositories, as `RecordApiTest.saveRecord` does). No `csrf()` anywhere. Cases: `list_ownReader_returns200WithoutSession` (`GET /api/records/readers/Kiosk L1` → `200`, `Set-Cookie` absent); `list_otherReader_returns403`; `list_unknownReaderName_returns403`; `patch_ownRecord_returns204AndCreditsReader` (then the saved `RecordConformityChangeEntity` has `authorReader` = L1 and `author` null, and `GET /api/records/{id}/conformity-history` as `user("admin").roles("ADMINISTRATEUR")` returns `authorType == "READER"`, `authorReaderUid == "Kiosk L1"`, no `authorUsername`); `patch_otherReadersRecord_returns403AndChangesNothing` (record compliance unchanged, no change row); `patch_unknownRecord_returns404`; `disabledReader_returns401` (set `active=false`, `saveAndFlush`); `operatorSession_stillWorks_withoutToken` (`GET /api/records/readers/Kiosk L2` with `user("op").roles("OPERATEUR")` → `200`, proving the user chain is untouched)
- [X] T051 [P] [US2] In `src/test/java/com/rfidback/service/RecordServiceTest.java` add, with `SecurityContextHolder` set to `new ReaderAuthentication(readerL1)` (a `ReaderEntity` with id and name `"L1"`): `list_asReader_otherName_throws403` (`ResponseStatusException` with `HttpStatus.FORBIDDEN`, `readerRepository` never queried); `update_asReader_ownRecord_writesChangeWithReaderAuthor` (captured change: `authorReader` = readerL1, `author` null, `userRepository` never called); `update_asReader_otherReadersRecord_throws403_writesNothing`; `update_asReader_sameValue_writesNothing` (idempotence unchanged); `listConformityChanges_mapsReaderAuthor` (`authorType` `READER`, `authorReaderUid` `"L1"`, `authorUsername` null). Keep the `ReaderRepository` mock in a field so the first case can `verifyNoInteractions` it

### Implementation for the kiosk

- [X] T052 [US2] In `src/main/java/com/rfidback/service/RecordService.java` (research R12): add `private static ReaderEntity currentReader()` returning the principal when the authentication is a `ReaderAuthentication`, else `null`. `listLatestRecordsForReader`: when `currentReader()` is non-null and `!reader.getName().equals(readerUid)`, throw `new ResponseStatusException(HttpStatus.FORBIDDEN, "A reader token only gives access to its own reader")` before any repository call. `updateRecordConformity`: after `findWithLockById` (404 first) and **before** the idempotence check, if the caller is a reader and `!record.getReader().getId().equals(reader.getId())`, throw the same `403`; when writing the change, set `authorReader(reader)` for a reader and keep today's `author(userRepository.findByUsername(...))` path for users (never call `currentUsername()` for a reader). `toConformityChange`: `READER` + `authorReaderUid(change.getAuthorReader().getName())` when `authorReader` is set, else `USER` + `authorUsername`. T051 must pass
- [X] T053 [US2] In `src/main/java/com/rfidback/security/ReaderApiTokenAuthenticationFilter.java` remove the `protectedEndpoint` matcher and the early `filterChain.doFilter` for other paths: the filter now authenticates every request it sees, and the chains' `securityMatcher`s decide where it runs (research R11). Update the class comment accordingly. `ReaderScanSecurityTest` must stay green
- [X] T054 [US2] In `src/main/java/com/rfidback/configuration/SecurityConfig.java` (research R11): add `kioskSecurityFilterChain` `@Bean @Order(2)` with `securityMatcher(new AndRequestMatcher(path(null, "/api/**"), new RequestHeaderRequestMatcher("x-api-token")))`, `cors(withDefaults())`, `csrf` disabled, `STATELESS` sessions, `requestCache` disabled, the same `ReaderApiTokenAuthenticationFilter` added before `UsernamePasswordAuthenticationFilter`, rules `OPTIONS /**` permitAll, `GET /api/records/readers/*` and `PATCH /api/records/*/conformity` `authenticated()`, `anyRequest().denyAll()`, entry point `HttpStatusEntryPoint(UNAUTHORIZED)`, `formLogin`/`httpBasic`/`logout` disabled. Move `userSecurityFilterChain` to `@Order(3)`. Update the comment above the chains: readers authenticate with their token on `/api/tags/scan` and, for the line kiosk, on their own records; users hold a session. T049 and T050 must pass; run `mvn clean test -Dtest='*Security*,RecordApiTest,RecordServiceTest'`
- [X] T055 [P] [US2] In `front/auth.js` add kiosk mode per [contracts/front-auth.md § Kiosk mode](contracts/front-auth.md): at load, parse `location.hash` with `URLSearchParams`; if both `reader` and `token` are present store them in `sessionStorage` (`kioskReader`, `kioskToken`, each access in `try/catch`); if the hash had either key, remove it with `history.replaceState(null, '', location.pathname + location.search)`. Add `isKioskMode()`, `kioskReader()`, `showKioskUnavailable()` (full-page dark message "Kiosque désactivé, contactez un administrateur", no link to login). In `apiFetch`, when in kiosk mode: set header `x-api-token`, use `credentials: 'omit'`, skip `X-XSRF-TOKEN`, and on `401` call `showKioskUnavailable()` instead of `redirectToLogin()`. Nothing else changes for normal pages
- [X] T056 [US2] In `front/reader.html` `window.onload`: if `isKioskMode()`, call `selectReader({ uid: kioskReader() })` directly (no `requireRole`, no `loadReaders`); otherwise keep today's `requireRole('ADMINISTRATEUR', 'OPERATEUR')` + `loadReaders()`. In `fetchRecords`, stop polling (clear the intervals started in `startApp`) once `showKioskUnavailable()` has replaced the page, so a disabled kiosk does not keep calling the API every 500 ms

**Checkpoint**: quickstart § 8 passes by curl and in the browser.

---

## Phase 11: Polish (amendment)

- [X] T057 [P] In `deploy/INSTALL.md` add a section `## Line kiosk` after `## Readers`: what the kiosk is (`reader.html` with the line reader's token, no login), the launcher example of research R15 (read the token from the reader's config file, `chromium --kiosk --noerrdialogs "https://vegelink.apolog.fr/reader.html#reader=<URL-encoded reader name>&token=<token>"`), that the URL must use `#` never `?`, that rotating or disabling the reader stops the kiosk until its config file is updated and the kiosk restarted, and that the kiosk can only view and change its own line; also state that Caddy access logs are off, and that anyone enabling them must exclude the `x-api-token` request header (reader devices and kiosks send their token in it; spec SC-006). In `deploy/web/Caddyfile`, add a comment above the `{$SITE_ADDRESS}` site block saying the same (no `log` directive on purpose; if added, drop `x-api-token` from the logged request headers, e.g. with a `log` + `format filter` that deletes `request>headers>X-Api-Token`). In `## Rollback` add: rolling back below this version breaks the conformity-history view for records changed at a kiosk (author is a reader, research R14); restore the pre-deploy backup if that matters
- [X] T058 [P] Update `CLAUDE.md` § "Security: two kinds of callers" → three chains: reader devices on `POST /api/tags/scan`; the line kiosk (`@Order(2)`, any `/api/**` request carrying `x-api-token`, only `GET /api/records/readers/{own uid}` and `PATCH /api/records/{id}/conformity` on own records, checked in `RecordService`; conformity changes then credited to the reader); users `@Order(3)`. In § Persistence mention `ConformityAuthorSchemaUpgrade` as the one startup schema fix alongside `ddl-auto: update`
- [X] T059 [P] Mark the delivered items: in `.specify/specs/005-consultation-lectures-conformite/spec.md` replace the four "à livrer" markers added on 2026-09-25 with "Livré" plus the evidence (`RecordService`, `SecurityConfig` kiosk chain, `KioskReaderTokenSecurityTest`); in `.specify/specs/008-authentification-roles/spec.md` extend SC-001's sentence to add the kiosk reader-token profile (a fourth profile, already named in US2's Independent Test) and state it is covered by `AccessMatrixSecurityTest` and `KioskReaderTokenSecurityTest`
- [X] T060 Run `mvn clean install` (all green), then walk through [quickstart.md § 8](quickstart.md#8-line-kiosk-with-the-reader-token-amendment-2026-09-25) including the "schema upgrade on an existing database" check against a copy of `data/rfidbackdb.mv.db` created by the previous version

---

## Amendment: dependencies and parallel work

- **Phase 8 → Phase 9 → Phase 10 → Phase 11.** T045 needs T044's generated model; T052 needs T047 (`authorReader`) and T044 (`AuthorTypeEnum.READER`).
- **Inside Phase 9**: T046 and T047 in parallel, then T048.
- **Inside Phase 10**: tests T049, T050, T051 in parallel (three files), all failing first. Then T052 (service), T053 then T054 (the chain relies on the filter no longer filtering by path; same package boundary, do in order). T055 in parallel with T052-T054 (front only); T056 after T055.
- **Phase 11**: T057, T058, T059 in parallel; T060 last.

```text
# Parallel example, Phase 10 tests:
T049 AccessMatrixSecurityTest (reader token → 403 elsewhere)
T050 KioskReaderTokenSecurityTest (own line 200/204, other line 403, disabled 401)
T051 RecordServiceTest (own-reader checks, reader author)
# then, while the backend is being written:
T055 front/auth.js kiosk mode
```

**MVP of the amendment**: Phases 8-10 (T044-T056). Deploy together: Phase 9 alone is harmless, but Phase 10 without
Phase 9 answers `500` on the first kiosk change in production.
