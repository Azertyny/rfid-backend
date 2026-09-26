---

description: "Task list for feature 012 — activités des lignes de production"
---

# Tasks: Activités des lignes de production

**Input**: Design documents from `.specify/specs/012-activites-lignes/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-activities.md](contracts/openapi-activities.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan names the test classes to add or extend (research R14), and every earlier feature ships with its tests.

**Organization**: one phase per user story of the spec: US1 (P1) the Administrateur defines activities and associates them with lines, US2 (P1) the Opérateur chooses the line's current activity (with the midnight reset and the kiosk banner), US3 (P2) each record keeps the activity current at scan time. The spec's former User Story 4 (dashboard filter) is out of scope (Clarifications 2026-09-26).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to
- Paths are relative to the repository root

## Path Conventions

Single Spring Boot project: `src/main/java/com/rfidback/…`, `src/main/resources/…`, `src/test/java/com/rfidback/…`; static front in `front/`. Generated OpenAPI code lands in `target/generated-sources/openapi` and is never edited by hand: change `src/main/resources/openapi/api.yaml`, then run `mvn generate-sources`. Schemas with **required** properties get a required-args constructor from the generator. Integration tests use `@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, `user("…").roles("…")` for sessions, `csrf()` for user writes and the `x-api-token` header for the kiosk, as in `KioskReaderTokenSecurityTest`. Never rely on the `XSRF-TOKEN` cookie (see `CLAUDE.md`, Deployment).

---

## Phase 1: Setup

**Purpose**: green starting point.

- [X] T001 Run `mvn clean test` from the repository root on branch `012-activites-lignes` and confirm all tests pass before any change. If it fails with `NoClassDefFoundError` on a bare class name or "Unresolved compilation problem", that's the VS Code Java extension racing Maven (see `CLAUDE.md`): rerun, don't fix code.

---

## Phase 2: Foundational

**Purpose**: contract, schema, security routes, and the shared "current activity" core used by every story. Blocks all stories.

- [X] T002 Edit `src/main/resources/openapi/api.yaml` exactly as in [contracts/openapi-activities.md](contracts/openapi-activities.md):
  - add an "Activity section (spec 012)" comment block with the paths `/activities`, `/activities/{activityId}` and `/lines/{readerUid}/current-activity` after the Reader section (before `# Picker section`), and the path `/readers/{readerId}/activities` right after `/readers/{readerId}/token`;
  - add the schemas `CreateActivity`, `UpdateActivity`, `ActivityRef`, `Activity`, `ActivitiesList`, `SetReaderActivities`, `LinesLosingActivity`, `SetLineActivity`, `LineActivity` after `ReadersList`;
  - add `activityId` and `activityName` (nullable, not required) to `RecordSummary`;
  - in `components.securitySchemes.ReaderApiToken.description`, add `GET/PUT /lines/{its uid}/current-activity` to the kiosk routes; in `/tags/scan`'s description add "The record carries the line's current activity, if any."
  Run `mvn generate-sources` and check that `ActivityApiDelegate` exists with `listActivities`, `createActivity`, `updateActivity`, `deleteActivity`, `getLineActivity`, `setLineActivity`, and that `ReaderApiDelegate` has `setReaderActivities`.
- [X] T003 [P] Create `src/main/java/com/rfidback/entity/ActivityEntity.java` (table `activity`, Lombok `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder` like `ReaderEntity`): `UUID id` (`GenerationType.UUID`); `String name` (`nullable = false, length = 100`); `String nameKey` (column `name_key`, `nullable = false, unique = true, length = 100`) computed in a `@PrePersist @PreUpdate` method as `name.trim().toLowerCase(Locale.ROOT)` (research R4) — also expose `public static String nameKey(String name)` for the service's existence checks; `boolean active` with `@Builder.Default`, `@ColumnDefault("true")`; `@ManyToMany @JoinTable(name = "reader_activity", joinColumns = @JoinColumn(name = "activity_id"), inverseJoinColumns = @JoinColumn(name = "reader_id")) @Builder.Default Set<ReaderEntity> lines = new HashSet<>()`; `creationDate` / `updateDate` with `@CreationTimestamp` / `@UpdateTimestamp`. Class comment: an activity is a product type a line can run (spec 012).
- [X] T004 [P] Create `src/main/java/com/rfidback/entity/ActivityChangeAuthorType.java`: enum `USER, READER, SYSTEM`.
- [X] T005 Create `src/main/java/com/rfidback/entity/LineActivityChangeEntity.java` (table `line_activity_change`, index `idx_line_activity_change_reader_date` on `reader_id, changed_at`), modelled on `RecordConformityChangeEntity`: `reader` (`@ManyToOne(fetch = LAZY, optional = false)`, `reader_id`), `previousActivity` (`previous_activity_id`, nullable), `newActivity` (`new_activity_id`, nullable), `OffsetDateTime changedAt` (`changed_at`, not null, **set by the code**, no `@CreationTimestamp` — research R3), `ActivityChangeAuthorType authorType` (`@Enumerated(STRING)`, length 10, not null), `authorUser` (`UserEntity`, `author_user_id`, nullable), `authorReader` (`ReaderEntity`, `author_reader_id`, nullable). `@PrePersist` throws `IllegalStateException` unless exactly the author field matching `authorType` is set (none for `SYSTEM`) and previous ≠ new (compare ids, null-safe). Comment: append-only log of a line's current-activity changes (spec 012, FR-012). Depends on T003, T004.
- [X] T006 [P] In `src/main/java/com/rfidback/entity/ReaderEntity.java`, add `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "current_activity_id") private ActivityEntity currentActivity;` and `@Column(name = "current_activity_set_at") private OffsetDateTime currentActivitySetAt;`, both nullable, with a comment: the line's current activity and when it was chosen; a choice from before today (station time) counts as none (spec 012, research R1/R3).
- [X] T007 [P] In `src/main/java/com/rfidback/entity/RecordEntity.java`, add `@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "activity_id") private ActivityEntity activity;` (nullable; comment: the line's activity at scan time, never changed afterwards, spec 012 FR-014/FR-015) and add `@Index(name = "idx_record_activity", columnList = "activity_id")` to the `@Table` indexes.
- [X] T008 [P] Create `src/main/java/com/rfidback/repository/ActivityRepository.java` (`JpaRepository<ActivityEntity, UUID>`): `boolean existsByNameKey(String nameKey)`; `boolean existsByNameKeyAndIdNot(String nameKey, UUID id)`; `@EntityGraph(attributePaths = "lines") List<ActivityEntity> findAllByOrderByNameAsc()`; `@Query("select a from ActivityEntity a join a.lines r where r.id = :readerId") List<ActivityEntity> findAllByLine(@Param("readerId") UUID readerId)`.
- [X] T009 [P] Create `src/main/java/com/rfidback/repository/LineActivityChangeRepository.java` (`JpaRepository<LineActivityChangeEntity, UUID>`): `@Query("select count(c) > 0 from LineActivityChangeEntity c where c.previousActivity = :activity or c.newActivity = :activity") boolean isReferenced(@Param("activity") ActivityEntity activity)`; `List<LineActivityChangeEntity> findAllByReaderOrderByChangedAtAsc(ReaderEntity reader)` (used by tests and SC-005).
- [X] T010 [P] In `src/main/java/com/rfidback/repository/ReaderRepository.java`, add: `@Lock(LockModeType.PESSIMISTIC_WRITE) @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000")) @Query("select r from ReaderEntity r left join fetch r.currentActivity where r.id = :id") Optional<ReaderEntity> findWithLockById(@Param("id") UUID id)` (research R9); `@EntityGraph(attributePaths = "currentActivity") Optional<ReaderEntity> findWithCurrentActivityById(UUID id)` (scan read, research R2); `@EntityGraph(attributePaths = "currentActivity") Optional<ReaderEntity> findWithCurrentActivityByName(String name)`; `List<ReaderEntity> findAllByCurrentActivitySetAtBefore(OffsetDateTime instant)` (daily job); `List<ReaderEntity> findAllByCurrentActivity(ActivityEntity activity)`.
- [X] T011 [P] In `src/main/java/com/rfidback/repository/RecordRepository.java`: change the entity graph of `findTop10ByReader_NameOrderByCreationDateDesc` to `attributePaths = { "tag", "activity" }` and update its comment ("with their tag and activity"); add `boolean existsByActivity(ActivityEntity activity)` and `long countByReaderAndCreationDateGreaterThanEqualAndActivityIsNull(ReaderEntity reader, OffsetDateTime since)` (banner count, research R10).
- [X] T012 [P] Create the exceptions in `src/main/java/com/rfidback/exception/`, modelled on `ReaderAlreadyExistsException` / `ReaderNotFoundException` and `RegistrationNotConfirmedException`: `ActivityAlreadyExistsException` (→ `409`), `ActivityNotFoundException` (→ `404`), `LinesLosingActivityException` carrying `List<String> readerUids`. Check how the existing ones get their status (annotation or `ApiExceptionHandler`) and do the same. In `src/main/java/com/rfidback/controller/ApiExceptionHandler.java`, add a handler turning `LinesLosingActivityException` into `409` with a `LinesLosingActivity` body (`message`, `readerUids`), like `handleRegistrationNotConfirmed`.
- [X] T013 In `src/main/java/com/rfidback/configuration/SecurityConfig.java` (research R9, contract access matrix):
  - kiosk chain (`@Order(2)`): before `.anyRequest().denyAll()`, add `.requestMatchers(path(HttpMethod.GET, "/api/lines/*/current-activity")).authenticated()` and the same for `HttpMethod.PUT`; update the chain's comment ("only opens the routes of reader.html");
  - user chain (`@Order(3)`), in the access matrix before `/api/records/**`: `path(HttpMethod.GET, "/api/activities")` → `hasAnyRole(ADMINISTRATEUR, OPERATEUR)`; `path(null, "/api/activities/**")` → `hasRole(ADMINISTRATEUR)` (also matches `POST /api/activities`); `path(null, "/api/lines/*/current-activity")` → `hasAnyRole(ADMINISTRATEUR, OPERATEUR)`. `PUT /api/readers/*/activities` is already Administrateur-only through `/api/readers/**`.
- [X] T014 Create `src/main/java/com/rfidback/service/LineActivityService.java` (`@Service @RequiredArgsConstructor`), the shared core (research R3, R8, R9), injecting `ReaderRepository`, `ActivityRepository`, `RecordRepository`, `LineActivityChangeRepository`, `UserRepository`, `StationProperties`, `Clock`:
  - `ActivityEntity effectiveActivity(ReaderEntity reader)`: returns `reader.getCurrentActivity()` if `currentActivitySetAt` is not null and `!currentActivitySetAt.isBefore(startOfToday())`, else `null`;
  - `OffsetDateTime startOfToday()`: `LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toOffsetDateTime()` with `zone = stationProperties.timeZone()`;
  - `void change(ReaderEntity lockedReader, ActivityEntity newActivity, OffsetDateTime at, ActivityChangeAuthorType type, UserEntity user, ReaderEntity authorReader)`: previous = `effectiveActivity(lockedReader)` — except that a stale non-null `currentActivity` is first logged as its own `SYSTEM` reset at the due midnight (call `resetIfStale`), so the log never skips a reset; if previous and new are the same activity (ids, null-safe) do nothing; else set `currentActivity` / `currentActivitySetAt` (null when clearing), save one `LineActivityChangeEntity`;
  - `boolean resetIfStale(ReaderEntity lockedReader)`: if `currentActivity != null` and `currentActivitySetAt` is before today's start, clear both and log a `SYSTEM` change with `changedAt` = start of the day after `currentActivitySetAt`'s date in the station zone; returns whether it did;
  - `void clear(ReaderEntity lockedReader, UserEntity author)`: `change(lockedReader, null, now, USER, author, null)`;
  - `LineActivity describe(ReaderEntity reader)`: `readerUid`, `currentActivity` = `effectiveActivity` as `ActivityRef` or null, `setAt` (null when none), `availableActivities` = `activityRepository.findAllByLine(reader.getId())` filtered on `active`, sorted by name, `recordsWithoutActivityToday` = `recordRepository.countByReaderAndCreationDateGreaterThanEqualAndActivityIsNull(reader, startOfToday())` (research R10);
  - `@Transactional int resetStaleActivities()`: for each reader of `readerRepository.findAllByCurrentActivitySetAtBefore(startOfToday())`, lock it with `findWithLockById` and call `resetIfStale`; returns how many lines were reset. It lives here, not in `ActivityDailyReset`, so every caller goes through the Spring proxy and gets the transaction the pessimistic lock needs (analysis C1);
  - `UserEntity currentUser()` and `ReaderEntity currentKioskReader()` helpers reading `SecurityContextHolder` like `RecordService.currentReader()` / `currentUsername()`.
  Class comment: owns the "effective current activity" rule shared by the scan, the kiosk and the daily reset (spec 012, FR-008a). Depends on T005–T011.
- [X] T015 Run `mvn clean test`: everything compiles against the new schema and the existing tests still pass (the new columns are nullable). Fix only what this phase broke.

**Checkpoint**: contract generated, schema in place, security routes declared, shared core ready.

---

## Phase 3: User Story 1 - L'Administrateur définit les activités et les associe aux lignes (Priority: P1) 🎯 MVP

**Goal**: Administrateurs create, rename, disable/re-enable and delete activities, and set each production line's activities, with confirmation before a line loses its current activity.

**Independent Test**: create two activities, associate one with L1 and both with L2; `GET /api/activities` shows exactly those `readerIds`; an Opérateur can read the list but not change it.

### Tests for User Story 1

- [X] T016 [P] [US1] Create `src/test/java/com/rfidback/service/ActivityServiceTest.java` (Mockito, like `ReaderServiceTest`): name trimmed; blank or > 100 → `400`; duplicate `nameKey` on create and rename → `ActivityAlreadyExistsException`; `DataIntegrityViolationException` on save → `ActivityAlreadyExistsException`; disabling an activity current on lines without `confirmed` → `LinesLosingActivityException` naming them and nothing saved, with `confirmed` → `LineActivityService.clear` called per line; delete refused when `recordRepository.existsByActivity` or `lineActivityChangeRepository.isReferenced`, allowed otherwise (associations cleared); `setReaderActivities` on an `ENREGISTREMENT` reader → `400`, unknown activity id → `400`, removing the current activity without `confirmed` → `LinesLosingActivityException`.
- [X] T017 [P] [US1] Create `src/test/java/com/rfidback/controller/ActivityApiTest.java` (integration, Administrateur session with `csrf()`), covering User Story 1 scenarios 1–5 and the edge cases: create "Framboise" → `201`, active, `readerIds` empty; create "framboise " → `409`; `PUT /api/readers/{L1}/activities` with "Fraise" → `200` and `GET /api/activities` shows "Fraise" on L1 only; rename → new name also in `GET /api/records/readers/{L1}` for a record carrying it (build the record through the repository with `activity` set); disable an activity current on L1 → `409` with `readerUids: ["L1"]`, then with `confirmed: true` → `200`, L1 has no current activity and a `USER` change is logged; untick the current activity of L2 → same `409` / confirmed flow; `DELETE` of a used activity → `409`, of an unused one → `204`; Opérateur: `GET /api/activities` → `200`, `POST` → `403`. Name readers uniquely (`"L1 " + UUID.randomUUID()`) since the test database is shared.

### Implementation for User Story 1

- [X] T018 [US1] Create `src/main/java/com/rfidback/service/ActivityService.java` (`@Service @RequiredArgsConstructor`) with `ActivityRepository`, `ReaderRepository`, `RecordRepository`, `LineActivityChangeRepository`, `LineActivityService`:
  - `ActivitiesList listActivities()` from `findAllByOrderByNameAsc()`, mapping to `Activity` (`readerIds` from `lines`);
  - `Activity createActivity(CreateActivity)`: trim, `400` if blank or > 100, `existsByNameKey` → `ActivityAlreadyExistsException`, `saveAndFlush` catching `DataIntegrityViolationException` like `ReaderService.createReader`;
  - `@Transactional Activity updateActivity(UUID, UpdateActivity)`: `400` if neither `name` nor `active`; rename with the same rules (`existsByNameKeyAndIdNot`); on `active: false`, lock each reader from `findAllByCurrentActivity` whose `effectiveActivity` is this one; if any and not `confirmed` → `LinesLosingActivityException` (their uids); else `LineActivityService.clear(reader, currentUser())` each, then set `active`;
  - `@Transactional void deleteActivity(UUID)`: `404` if unknown; `409` (`ResponseStatusException(CONFLICT, "Activity already used: disable it instead")`) if `recordRepository.existsByActivity` or `lineActivityChangeRepository.isReferenced`; else clear `lines` and delete. No reader can still point to it: every choice writes a change-log row, which `isReferenced` refuses (analysis I2);
  - `@Transactional LineActivity setReaderActivities(UUID readerId, SetReaderActivities)`: reader via `readerRepository.findWithLockById` (`404`); `ENREGISTREMENT` → `400`; all ids must exist (`400` naming the missing ones); if the reader's effective current activity is not in the new set and not `confirmed` → `LinesLosingActivityException(List.of(uid))`; if confirmed, `LineActivityService.clear`; then update each activity's `lines` (add to the new ones, remove from the others that had it); return `LineActivityService.describe(reader)` (T014).
  Depends on T014.
- [X] T019 [US1] Create `src/main/java/com/rfidback/controller/ActivityController.java` (`@Component`/`@Controller` as the other controllers, implements `ActivityApiDelegate`): `listActivities`, `createActivity` (`201`), `updateActivity`, `deleteActivity` (`204`) delegate to `ActivityService`; `getLineActivity` / `setLineActivity` throw `ResponseStatusException(NOT_IMPLEMENTED)` until T025. In `src/main/java/com/rfidback/controller/ReaderController.java`, implement `setReaderActivities` delegating to `ActivityService.setReaderActivities`.
- [X] T020 [P] [US1] Create `front/activities.html` (Administrateur page, same skeleton, navbar, Bootstrap, `config.js` / `auth.js` includes and `apiFetch` usage as `front/readers.html`), with the "Activités" navbar button as `btn-primary`:
  - activities table: name, state badge (Active / Désactivée), actions Renommer (inline input), Désactiver/Réactiver, Supprimer; a create form (name input + button); errors shown in the page's alert area (`409` name → "Ce nom existe déjà"; delete `409` → "Activité déjà utilisée : désactivez-la");
  - "Activités par ligne" grid: one row per reader with `mode === 'PRODUCTION'` from `GET /readers`, one checkbox per activity (disabled activities shown greyed with "(désactivée)"), a "Enregistrer" button per row sending `PUT /readers/{id}/activities`;
  - on a `409` whose JSON has `readerUids`, open a Bootstrap modal "Les lignes suivantes n'auront plus d'activité en cours : …" with Annuler / Confirmer; Confirmer resends the same request with `confirmed: true`. No `alert()` / `confirm()`.
  Depends on T002 only for the contract; test it after T019.
- [X] T021 [P] [US1] Add a navbar button `<a href="activities.html" data-admin-only class="btn btn-sm btn-outline-light border-0">` with a Font Awesome icon (e.g. `fa-seedling`) and the text "Activités", placed right after the "Lecteurs" (`readers.html`) link, in `front/index.html`, `front/pickers.html`, `front/readers.html`, `front/tags.html`, `front/buckets.html`, `front/users.html` (match each page's existing markup for its links).
- [X] T022 [US1] Run `mvn clean test -Dtest='ActivityServiceTest,ActivityApiTest'` until green.

**Checkpoint**: activities and associations are manageable end to end; lines can't choose yet.

---

## Phase 4: User Story 2 - L'Opérateur choisit l'activité en cours sur sa ligne (Priority: P1)

**Goal**: the kiosk (own line only) and logged-in Opérateurs/Administrateurs see and change a line's current activity; it resets at midnight (station time); the kiosk warns while none is chosen.

**Independent Test**: at the kiosk of L1 (two activities associated), choose the second → `GET /api/lines/L1/current-activity` returns it; another line's uid with L1's token → `403`; after a simulated midnight the line has none and a `SYSTEM` change is logged.

### Tests for User Story 2

- [X] T023 [P] [US2] Create `src/test/java/com/rfidback/service/LineActivityServiceTest.java` (Mockito, `Clock.fixed`, `StationProperties(ZoneId.of("Europe/Paris"))`): `effectiveActivity` true at 23:59 Paris the day of the choice, false at 00:00 the next day (use an instant in winter and one in summer); `change` to the same activity writes nothing; `change` over a stale activity first logs a `SYSTEM` reset whose `changedAt` is the due midnight, then the user change; `resetIfStale` does nothing for a choice made today.
- [X] T024 [P] [US2] Create `src/test/java/com/rfidback/controller/LineActivityApiTest.java` (integration), covering User Story 2 scenarios 1–6, FR-009a and FR-013: kiosk of L1 (`x-api-token`, no CSRF) `PUT /api/lines/{L1}/current-activity` with an associated activity → `200`, body `currentActivity` set, one `READER` change logged; `activityId: null` → none; not associated, disabled or unknown activity → `400` and unchanged; L1's token on `/api/lines/{L2}/…` → `403`; Opérateur session with `csrf()` → `200` on any line, `USER` change logged; `ENREGISTREMENT` reader → `400`; unknown uid → `404`; `availableActivities` lists associated and active ones only, sorted by name; `recordsWithoutActivityToday` counts the line's records created today with no activity (save records through `RecordRepository`) and ignores other lines; switching the reader to `ENREGISTREMENT` via `PATCH /api/readers/{id}` clears it and logs a `USER` change; a reader whose `currentActivitySetAt` was set to yesterday through the repository reads as `currentActivity: null`.
- [X] T025 [P] [US2] Create `src/test/java/com/rfidback/service/ActivityDailyResetTest.java` (integration, `@SpringBootTest @ActiveProfiles("test")`, autowiring `ActivityDailyReset`): a reader whose current activity was set yesterday (repository) → `resetAtMidnight()` clears it and logs one `SYSTEM` change with `changedAt` = today 00:00 Europe/Paris; a reader whose choice was made today is untouched; running it twice logs nothing more; **and the same stale case through `catchUpOnStartup()`** succeeds without `TransactionRequiredException` (analysis C2). Keep the test itself non-transactional (no `@Transactional` on the class or methods) so it does not hide a missing transaction.
- [X] T026 [P] [US2] Extend `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java`: add to `routes()` `GET /api/activities` (`LOGGED_IN`), `POST /api/activities` with `{"name":"Matrix activity " + UUID}` (`ADMIN_ONLY`), `PATCH /api/activities/{ID}` `{"active":true}` (`ADMIN_ONLY`), `DELETE /api/activities/{ID}` (`ADMIN_ONLY`), `PUT /api/readers/{ID}/activities` `{"activityIds":[]}` (`ADMIN_ONLY`), `GET /api/lines/unknown-reader/current-activity` and `PUT` with `{"activityId":null}` (`LOGGED_IN`); make sure the reader-token test (`routesClosedToReaderTokens`) includes the Administrateur-only ones and excludes the two `/api/lines` routes. Extend `src/test/java/com/rfidback/security/KioskReaderTokenSecurityTest.java` with: own line `GET`/`PUT` → `200`; other line → `403`; disabled reader's token → `401`.

### Implementation for User Story 2

- [X] T027 [US2] In `src/main/java/com/rfidback/service/LineActivityService.java`, add the route logic:
  - `@Transactional(readOnly = true) LineActivity getLineActivity(String readerUid)`: kiosk reader (if any) must have this name, else `403` ("A reader token only gives access to its own reader", as in `RecordService`); reader via `findWithCurrentActivityByName` (`404` via `ReaderNotFoundException`); `ENREGISTREMENT` → `400`; return `describe(reader)`;
  - `@Transactional LineActivity setLineActivity(String readerUid, SetLineActivity)`: same checks, then lock with `findWithLockById`; resolve the activity (`null` = none); it must exist, be active and have this reader in `lines`, else `400`; `change(…, now, READER|USER, …)` with the kiosk reader or `currentUser()`; return `describe(reader)` (T014).
- [X] T028 [US2] In `src/main/java/com/rfidback/controller/ActivityController.java`, implement `getLineActivity` and `setLineActivity` delegating to `LineActivityService` (replace the `NOT_IMPLEMENTED` stubs of T019).
- [X] T029 [US2] Create `src/main/java/com/rfidback/service/ActivityDailyReset.java` (`@Component @RequiredArgsConstructor @Slf4j`), with **no** `@Transactional` of its own: `@Scheduled(cron = "0 0 0 * * *", zone = "${app.station.time-zone}") public void resetAtMidnight()` and `@EventListener(ApplicationReadyEvent.class) public void catchUpOnStartup()` (catch-up after a stop across midnight, research R3) both call `lineActivityService.resetStaleActivities()` (T014) — a call into another bean, so the transaction applies; never call one of this class's methods from the other (self-invocation bypasses the proxy, analysis C1). Log at `info` how many lines were reset when it is more than 0. In `src/main/java/com/rfidback/configuration/ClockConfig.java`, add `@EnableScheduling` and extend the class comment ("and the midnight reset of line activities, spec 012").
- [X] T030 [US2] In `src/main/java/com/rfidback/service/ReaderService.java` `updateReader`: load the reader with `readerRepository.findWithLockById` instead of `loadReader` (lock first, analysis I3; `404` as before), and when the resulting mode is `ENREGISTREMENT` call `lineActivityService.clear(reader, lineActivityService.currentUser())` (FR-013; associations untouched); disabling keeps the current activity. Inject `LineActivityService` (check for a circular dependency: `LineActivityService` must not depend on `ReaderService`). Update the method's Javadoc. Extend `src/test/java/com/rfidback/service/ReaderServiceTest.java` accordingly (the new constructor argument, and one case asserting `clear` is called on the switch to `ENREGISTREMENT`).
- [X] T031 [US2] In `front/reader.html` (kiosk page; research R10, R13; SC-001, FR-009a):
  - header: replace the centre column content with the current activity ("Activité" label + name, or "Aucune") and a large "Changer" button, keeping the system dot/text below it;
  - chooser: a full-screen overlay listing `availableActivities` as big touch buttons plus "Aucune activité" and "Annuler"; a tap sends `PUT /lines/{uid}/current-activity` via `apiFetch` with `{ activityId }` and refreshes the header from the response (2 gestures: "Changer", then the activity);
  - banner: a fixed warning bar at the top of `#main-interface` (amber background, dark text, large font) shown while `currentActivity` is null: "Aucune activité en cours — N lecture(s) sans activité depuis minuit"; when `availableActivities` is empty, add "Aucune activité n'est associée à cette ligne : contactez l'Administrateur" and hide "Changer"; it must never cover the record boxes (shrink `#conveyor-belt` instead) and must not block taps;
  - polling: `fetchLineActivity()` on start, every 5000 ms (own `setInterval` in `intervals`), and after each choice; same `isKioskUnavailable()` handling as `fetchRecords`; errors only update the system status;
  - reader without activity (analysis U1): a logged-in user can open the page for a reader in `ENREGISTREMENT` mode, where `GET /lines/{uid}/current-activity` answers `400`. On a `400`, hide the activity zone, the "Changer" button and the banner, stop the activity polling (clear only its interval) and leave the records display as today; don't report it as a system error.
  Keep the existing styles and naming (French UI text, `CURRENT_READER.uid`).
- [X] T032 [US2] Run `mvn clean test -Dtest='LineActivityServiceTest,LineActivityApiTest,ActivityDailyResetTest,ReaderServiceTest,AccessMatrixSecurityTest,KioskReaderTokenSecurityTest'` until green.

**Checkpoint**: lines have a current activity that operators control; nothing is stamped on records yet.

---

## Phase 5: User Story 3 - Chaque lecture garde l'activité en cours au moment du scan (Priority: P2)

**Goal**: every production record carries the line's effective current activity at scan time, never changed afterwards; kiosk and record lists show it.

**Independent Test**: a scan during "Fraise", then one after switching to "Framboise", carry each their activity; `GET /api/records/readers/{uid}` shows both names.

### Tests for User Story 3

- [X] T033 [P] [US3] Extend `src/test/java/com/rfidback/controller/TagScanApiTest.java` (existing cases untouched, SC-003): with "Fraise" current on the reader, a scan creates a record with `activity` = Fraise (check through `RecordRepository`); with none, the record has none and the response is identical to today's; with `currentActivitySetAt` set to yesterday, the record has none; a duplicate scan within the window after a change of activity leaves the first record's activity unchanged; after switching activity, a new record carries the new one and the old record keeps the old one; after disabling or dissociating the activity (confirmed), earlier records keep it.
- [X] T034 [P] [US3] Extend `src/test/java/com/rfidback/service/TagServiceTest.java`: mock `LineActivityService` (new constructor argument) and assert `registerScan` sets `activity` from `effectiveActivity` of the reader loaded by `readerRepository.findWithCurrentActivityById`; the duplicate path does not call it to change anything.
- [X] T035 [P] [US3] Extend `src/test/java/com/rfidback/controller/RecordApiTest.java`: `GET /api/records/readers/{uid}` returns `activityId` / `activityName` for a record with an activity and `null` for one without.

### Implementation for User Story 3

- [X] T036 [US3] In `src/main/java/com/rfidback/service/TagService.java` `registerScan`: inject `ReaderRepository` and `LineActivityService` (constructor, keeping the `@Value` parameter last); after the duplicate check and before building the `RecordEntity`, load `readerRepository.findWithCurrentActivityById(reader.getId())` (the principal is detached, research R2) and set `.activity(lineActivityService.effectiveActivity(freshReader))` on the builder. Do not touch `ignoreDuplicate` or the off-list path. Update the Javadoc ("the record carries the line's current activity at scan time, spec 012").
- [X] T037 [US3] In `src/main/java/com/rfidback/service/RecordService.java` `toRecordSummary`: set `activityId` and `activityName` from `record.getActivity()` when not null.
- [X] T038 [US3] In `front/reader.html` `renderBoxes`: under the timestamp of each box, show the record's `activityName` or "Sans activité" (small, muted when absent). Keep the hash-based re-render.
- [X] T039 [US3] Run `mvn clean test -Dtest='TagScanApiTest,TagServiceTest,RecordApiTest,RecordServiceTest'` until green.

**Checkpoint**: all three stories work; the feature is complete.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [X] T040 [P] Update `CLAUDE.md`: in "Project" add **Activity** (activité) — product type a production line runs, chosen per line at its kiosk, stamped on each record (spec 012); in "Security", the line kiosk chain also opens `GET/PUT /api/lines/{its uid}/current-activity`; in "Persistence", one sentence: a line's current activity resets at midnight station time (`service/ActivityDailyReset`, plus a rule on read in `LineActivityService`), which is why `APP_STATION_TIME_ZONE` also drives it.
- [X] T041 [P] In `.specify/specs/005-consultation-lectures-conformite/spec.md` and `.specify/specs/008-authentification-roles/spec.md`, append one line where the kiosk routes are listed (FR-004 in 005, FR-005a in 008): "**Étendu par la spec `012`** : le jeton du kiosque ouvre aussi `GET/PUT /api/lines/{uid}/current-activity` pour sa propre ligne."
- [X] T042 Run `mvn clean install` (full build, test classes in CI order); all pass.
- [X] T043 Walk through the manual checks of [quickstart.md](quickstart.md) on the `dev` profile (sections 2–7) and note any deviation in the spec's Clarifications before closing the feature. Then set the spec's **Status** to `Delivered (<date>)`.

---

## Dependencies & Execution Order

- **Phase 1 → Phase 2 → US1 → US2 → US3 → Polish.** US2 and US3 could start in parallel after Phase 2 on the backend, but US3's tests need a way to set a current activity (T027) — easiest in order.
- Phase 2: T002 first (generated types); T003 ∥ T004 ∥ T006 ∥ T007; T005 after T003–T004; T008–T012 after the entities they reference; T013 anytime after T002; T014 after T005–T011; T015 last.
- US1: T016 ∥ T017 (tests first); T018 → T019; T020 ∥ T021 alongside; T022 last.
- US2: T023 ∥ T024 ∥ T025 ∥ T026 (tests first); T027 → T028; T029 and T030 after T027; T031 after T028; T032 last.
- US3: T033 ∥ T034 ∥ T035; T036 → T037; T038 after T037 (uses the new fields); T039 last.
- Polish: T040 ∥ T041 any time after T036; T042 after all code tasks; T043 last.

## Parallel Example: Phase 2 and User Story 1

```text
After T002:  T003 ActivityEntity  ∥  T004 ActivityChangeAuthorType  ∥  T006 ReaderEntity  ∥  T007 RecordEntity  ∥  T013 SecurityConfig
Then:        T005 LineActivityChangeEntity  ∥  T008 ActivityRepository  ∥  T009 LineActivityChangeRepository  ∥  T010 ReaderRepository  ∥  T011 RecordRepository  ∥  T012 exceptions
US1:         T016 ActivityServiceTest  ∥  T017 ActivityApiTest  ∥  T020 activities.html  ∥  T021 navbar links
US2 tests:   T023  ∥  T024  ∥  T025  ∥  T026
US3 tests:   T033  ∥  T034  ∥  T035
Polish:      T040 CLAUDE.md  ∥  T041 specs 005/008
```

## Implementation Strategy

1. **MVP (US1)**: T001–T022. The Administrateur can prepare the season's activities and associations; nothing changes on the lines yet, so this is safe to deploy on its own.
2. **Lines choose (US2)**: T023–T032. Kiosks show and change the activity, with the midnight reset and the banner. Deployable, but records do not carry the activity yet — deploy together with US3 unless a dry run of the kiosk flow is wanted.
3. **Records carry it (US3) and polish**: T033–T043, then open the PR against `dev`.

---

## Phase 7: Convergence

- [X] T044 In `front/activities.html`, keep the unsaved ticks of the other lines when the page refreshes after an action (remember the checked boxes of rows not being saved and restore them in `renderGrid()`, or redraw only the saved row), so preparing a season never loses work per SC-006 (partial)
- [X] T045 Update the "Repository additions" table of `.specify/specs/012-activites-lignes/data-model.md` to match `src/main/java/com/rfidback/repository/ReaderRepository.java`: `findIdsByCurrentActivitySetAtBefore` and `findIdsByCurrentActivity` return ids, and `findWithLockByName` locks on the first load, so a lock never returns a reader already loaded and stale, per plan: data-model repository additions / research R9 (partial)
