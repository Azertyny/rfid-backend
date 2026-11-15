---

description: "Task list for feature 015 — statuts de lecture : relecture ignorée, doublon / perdu et cheat"
---

# Tasks: Statuts de lecture — relecture ignorée, doublon / perdu et cheat

**Input**: Design documents from `.specify/specs/015-statuts-doublon-cheat/`

**Prerequisites**: [plan.md](plan.md), [spec.md](spec.md), [research.md](research.md), [data-model.md](data-model.md), [contracts/openapi-scan-status.md](contracts/openapi-scan-status.md), [quickstart.md](quickstart.md)

**Tests**: included. The plan names the test classes to add or extend (research R11), and every earlier feature ships with its tests.

**Organization**: one phase per user story of the spec: US1 (P1) a counted tag read again within the delay is ignored, US2 (P1) a counted tag scanned again later is Doublon / perdu, US3 (P1) two tags of one bucket read together are a Cheat, US4 (P2) statuses are shown, counted and corrected, US5 (P2) the Administrateur sets the two delays.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to
- Paths are relative to the repository root

## Path Conventions

Single Spring Boot project: `src/main/java/com/rfidback/…`, `src/main/resources/…`, `src/test/java/com/rfidback/…`; static front in `front/`. Generated OpenAPI code lands in `target/generated-sources/openapi` and is never edited by hand: change `src/main/resources/openapi/api.yaml`, then run `mvn generate-sources`. Rule numbers below are those of research R3 (1 same pass, 2 Cheat, 3 ignored re-read, 4 Doublon / perdu, 5 count).

---

## Phase 1: Setup

**Purpose**: green starting point on the feature branch.

- [X] T001 On branch `015-statuts-doublon-cheat` (already created from `dev`, with the untracked `.specify/specs/015-statuts-doublon-cheat/` folder), run `mvn clean test` from the repository root and confirm all tests pass before any change. If it fails with `NoClassDefFoundError` on a class name without its package or "Unresolved compilation problem", it is the VS Code Java extension racing Maven (`CLAUDE.md`): rerun.

---

## Phase 2: Foundational

**Purpose**: contract, columns, settings table and the status mapping every story relies on. Blocks all stories.

- [X] T002 Edit `src/main/resources/openapi/api.yaml` exactly as in [contracts/openapi-scan-status.md](contracts/openapi-scan-status.md):
  - add a "Scan settings section (spec 015)" block with `/scan-settings` (`get` → `getScanSettings`, `put` → `updateScanSettings`, tag `ScanSettings`) after the `/work-hours` path, and declare the tag `ScanSettings` in the top-level `tags` list;
  - add `/records/anomalies` (`get` → `listRecordAnomalies`, tag `Record`) right after `/records/stats`, with the four parameters of `/records/stats` copied (`period`, `from`, `to`, `readerId`) plus `PageNumber` and `PageSize`;
  - add the schemas `RecordStatus`, `ScanSetting`, `ScanSettings`, `UpdateScanSettings`, `AnomalyRecord`, `AnomalyRecordsPage` (not `RecordAnomaly`, the entity enum's name);
  - extend `RecordSummary` (`status` required), `RecordCounts` (`cheats` required), `RecordStats` (`duplicates` required), `ConformityChange` (`previousStatus`, `newStatus` required) and the `ScanTagResponse.message` description; update the descriptions of `/records/stats` (doublons left out of the totals) and `PATCH /records/{recordId}/conformity` (Administrateur only on an anomaly record).
  Run `mvn generate-sources` and check that `ScanSettingsApiDelegate` exists and that `RecordApiDelegate` has `listRecordAnomalies`.
- [X] T003 [P] Create the enums `src/main/java/com/rfidback/entity/RecordAnomaly.java` (`DOUBLON_PERDU`, `CHEAT`) and `src/main/java/com/rfidback/entity/ScanSettingName.java` (`REREAD_WINDOW`, `CHEAT_WINDOW`), each with a one-line comment citing spec 015.
- [X] T004 Add the three nullable columns of [data-model.md](data-model.md), depends on T003: in `src/main/java/com/rfidback/entity/RecordEntity.java` `@Enumerated(EnumType.STRING) @Column(length = 20) RecordAnomaly anomaly` with a comment (set only by the system, always with `compliant = false`, spec 015 FR-001); in `src/main/java/com/rfidback/entity/TagEntity.java` `@Column(name = "last_read_at") OffsetDateTime lastReadAt` (last production read by any reader, ignored reads included; null before spec 015); in `src/main/java/com/rfidback/entity/RecordConformityChangeEntity.java` `@Enumerated(EnumType.STRING) @Column(name = "previous_anomaly", length = 20) RecordAnomaly previousAnomaly`.
- [X] T005 [P] Create `src/main/java/com/rfidback/entity/ScanSettingEntity.java` (table `scan_setting`, Lombok annotations as `PickerWorkDayEntity`): `@Id @Enumerated(EnumType.STRING) @Column(length = 30) ScanSettingName name`; `@Column(nullable = false) int seconds`; `@Column(name = "updated_at", nullable = false) OffsetDateTime updatedAt`; `@ManyToOne(fetch = LAZY, optional = false) @JoinColumn(name = "updated_by", nullable = false) UserEntity updatedBy`. Create `src/main/java/com/rfidback/repository/ScanSettingRepository.java` (`JpaRepository<ScanSettingEntity, ScanSettingName>`) with `@EntityGraph(attributePaths = "updatedBy") List<ScanSettingEntity> findAllWithAuthorBy()` for the settings page; the scan uses plain `findAll()`.
- [X] T006 In `src/main/resources/application.yml` under `app.scan`, add `reread-window: 30m` with a comment (default of the re-read delay an Administrateur can change, spec 015 FR-003/FR-008; `0s` disables it) and reword the `crate-window` comment (default of the Cheat gap, spec 015 FR-006). Create `src/main/java/com/rfidback/service/ScanSettingsService.java` (`@Service`), constructor-injecting `ScanSettingRepository`, `@Value("${app.scan.reread-window}") Duration` and `@Value("${app.scan.crate-window}") Duration`, with `record Windows(Duration reread, Duration cheat)` and `@Transactional(readOnly = true) Windows windows()`: one `findAll()`, each row's `seconds` overriding its default (research R6: read at every call, no cache). Depends on T005.
- [X] T007 In `src/main/java/com/rfidback/service/RecordService.java`, add `static RecordStatus statusOf(RecordEntity)` (anomaly → `DOUBLON_PERDU` / `CHEAT`, else `CONFORME` / `NON_CONFORME` from `isCompliant()`), set `status` in `toRecordSummary`, and set `previousStatus` / `newStatus` in `toConformityChange` (previous: `previousAnomaly` if set, else from `previousCompliant`; new: from `newCompliant`). Depends on T002, T004.
- [X] T008 Run `mvn clean test`. Expected: green (the new required stats fields `cheats` and `duplicates` are still null in responses until US4; fix any existing assertion that breaks only because of the new `status` field).

**Checkpoint**: the application boots, the three columns and `scan_setting` exist, record lists carry `status`; scan behaviour is unchanged.

---

## Phase 3: User Story 1 — Ne pas compter un tag relu peu après son comptage (Priority: P1) 🎯 MVP

**Goal**: a counted tag read again less than the re-read delay after its last read, by any reader, creates nothing and changes no record (FR-003, FR-004).

**Independent Test**: scan a tag of an assigned bucket, scan it again 5 minutes later on another reader → one record, compliant, with the picker; the second response is `200` with `message: "Recent read ignored"`.

### Tests for User Story 1

- [X] T009 [P] [US1] In `src/test/java/com/rfidback/service/TagServiceTest.java`, build `TagService` with a mocked `ScanSettingsService` returning `Windows(30 min, 2 s)` (replace the `crateWindow` constructor argument in the `tagService(...)` helper), and add: a counted tag read by another reader 5 min after its last read → no `save` of a record, response carries the counted record's values and `"Recent read ignored"`, `tag.lastReadAt` = now; the counted record's `lastReadAt` and `compliant` untouched even when the read says non-compliant; delay sliding (last read 29 min ago although counted 2 h ago → ignored); `tag.lastReadAt` null falls back to the record's `lastReadAt`, then `creationDate`; re-read window zero → rule off; tag registered again (no counted record) → counted normally; same reader within 10 s still goes through rule 1 (verdict lowering kept).
- [X] T010 [P] [US1] In `src/test/java/com/rfidback/controller/TagScanApiTest.java`, add `scan_countedTagReadAgainWithinTheDelay_byAnotherReader_isIgnored` (one record, unchanged, message) and `scan_ignoredReads_keepSlidingTheDelay` (age `tag.last_read_at` with a helper next to `age(...)`), and update `scan_sameTagByAnotherReaderTheSameDay_isKO` and `scan_rescanAfterTheWindow_sameReader_isKO`, which now describe an ignored read unless the tag's last read is aged beyond 30 min (their KO case moves to US2).

### Implementation for User Story 1

- [X] T011 [US1] In `src/main/java/com/rfidback/service/TagService.java`: inject `ScanSettingsService` and drop the `app.scan.crate-window` constructor parameter; read `Windows windows = scanSettingsService.windows()` once per `registerScan` after the reference-list check and pass `windows.cheat()` to `crateOf`; set `tag.setLastReadAt(now)` on every path that handles a tag in the reference list (rules 1 to 5); add rule 3 after the crate rule: `counted.isPresent()` and `windows.reread().isPositive()` and `lastRead(tag, counted)` after `now.minus(windows.reread())` → return `response(tag, counted, RECENT_READ_IGNORED)` without touching the record (new constant `"Recent read ignored"`; `lastRead` = `tag.getLastReadAt()`, else the record's `lastReadAt`, else its `creationDate`). Update the class comment on `registerScan` to list the five rules (research R3). Depends on T006.
- [X] T012 [US1] Run `mvn clean test -Dtest='TagServiceTest,TagScanApiTest,TagRegistrationApiTest'` and fix until green.

**Checkpoint**: quickstart section 4 passes once the delay can be lowered (until US5, through `app.scan.reread-window`).

---

## Phase 4: User Story 2 — Signaler un tag déjà compté qui est scanné à nouveau (Priority: P1)

**Goal**: a counted tag scanned again outside the delays, the same day or a later one, creates a Doublon / perdu record with no picker (FR-005).

**Independent Test**: scan a tag at 9:00 then at 11:00 → two records, the first unchanged, the second with `status: DOUBLON_PERDU`, no picker, `isCompliant: false` in the response.

### Tests for User Story 2

- [X] T013 [P] [US2] In `src/test/java/com/rfidback/service/TagServiceTest.java`, add: counted tag last read 2 h ago → new record with `anomaly = DOUBLON_PERDU`, `compliant = false`, no picker, no bucket, current activity, whatever the reader's verdict, message `"Tag already scanned today"` (unchanged, also on a later day), first record untouched, `tag.countedRecord` = the new record; counted **yesterday** and last read yesterday → also `DOUBLON_PERDU` (no `startOfToday()` call); a third scan later → another doublon; a tag never counted and without bucket → ordinary record with the reader's verdict (rule 5).
- [X] T014 [P] [US2] In `src/test/java/com/rfidback/controller/TagScanApiTest.java`, add `scan_countedTagAfterTheDelay_isDoublonPerdu`, `scan_countedTagOnALaterDay_isDoublonPerdu` (age the counted record and the tag's last read by 25 h) and `scan_tagRegisteredAgain_countsNormally`; in `src/test/java/com/rfidback/controller/TagRegistrationApiTest.java` keep `register_aCountedTagAgain_resetsIt_soItsNextScanCountsNormally` green.

### Implementation for User Story 2

- [X] T015 [US2] In `src/main/java/com/rfidback/service/TagService.java`: rule 4 becomes `counted.isPresent()` alone (remove the `lineActivityService.startOfToday()` test); rename `rescanToday` to `duplicate`, add `.anomaly(RecordAnomaly.DOUBLON_PERDU)` to the built record, keep the constant `RESCAN_TODAY` and its text (research R5: the message is not renamed, so nothing matching on it breaks), and update the method comments (spec 015 FR-005, research R5). Depends on T011.
- [X] T016 [US2] Run `mvn clean test -Dtest='TagServiceTest,TagScanApiTest,TagRegistrationApiTest'` and fix until green.

**Checkpoint**: quickstart section 5 passes.

---

## Phase 5: User Story 3 — Signaler deux tags lus en même temps (Priority: P1)

**Goal**: two tags of one bucket read by one reader within the Cheat gap give one record, status Cheat, kept on the picker (FR-006, FR-007).

**Independent Test**: one reader scans two tags of one bucket 1 s apart → one record, `status: CHEAT`, the bucket's picker; the second response has `isCompliant: false`.

### Tests for User Story 3

- [X] T017 [P] [US3] In `src/test/java/com/rfidback/service/TagServiceTest.java`, add: second tag of the crate → the crate record gets `anomaly = CHEAT` and `compliant = false` even when both reads say compliant, no new record, the second tag leaves the bucket with `countedRecord` = the crate record; a record with a manual change (`recordConformityChangeRepository.existsByRecord` true) keeps its status, only `lastReadAt` moves; a third tag within the gap → still one record; Cheat gap zero → two ordinary records; the Cheat record's second tag read 5 min later → ignored, 2 h later → doublon.
- [X] T018 [P] [US3] In `src/test/java/com/rfidback/controller/TagScanApiTest.java`, extend `scan_twoTagsOfOneCrate_countOnce_andBothLeaveTheBucket` (record `anomaly = CHEAT`, picker kept, second response `isCompliant: false`) and check `scan_tagsOfTwoBuckets_countTwice`, `scan_secondTagMoreThanTheCrateWindowLater_countsAnotherCrate` and `scan_secondTagByAnotherReader_countsAnotherCrate` still give records without anomaly.

### Implementation for User Story 3

- [X] T019 [US3] In `src/main/java/com/rfidback/service/TagService.java`, rule 2: replace the `absorb(crate, …, SECOND_TAG_OF_CRATE)` call by a `markCheat(crate, tag, now)` that sets `lastReadAt`, and, unless `recordConformityChangeRepository.existsByRecord(crate)`, sets `compliant = false` and `anomaly = CHEAT` then `saveAndFlush`; it returns `response(tag, crate, SECOND_TAG_OF_CRATE)`. `absorb` stays for rule 1 only; update the comments (spec 015 FR-007, research R5). Depends on T015.
- [X] T020 [US3] Run `mvn clean test -Dtest='TagServiceTest,TagScanApiTest'` and fix until green.

**Checkpoint**: quickstart section 6 passes; the three scan rules are complete (SC-001).

---

## Phase 6: User Story 4 — Voir et corriger les statuts (Priority: P2)

**Goal**: the line screen shows the four statuses; the dashboard counts Cheat and Doublon / perdu and keeps doublons out of every total; the Administrateur alone lifts an anomaly, from the line screen or from the list of the period's anomalies (FR-002, FR-009 to FR-011, FR-015).

**Independent Test**: after one doublon and one cheat on a line, the line screen shows both statuses; the dashboard counts 1 Doublon / perdu (outside the total) and 1 Cheat on the picker's row; the anomalies page lists both; as `admin`, passing the Cheat to Conforme removes it from the list; the same PATCH as Opérateur or with the reader token answers `403`.

### Tests for User Story 4

- [X] T021 [P] [US4] In `src/test/java/com/rfidback/service/RecordServiceTest.java` and `src/test/java/com/rfidback/controller/RecordApiTest.java`, add: Administrateur PATCH on a Cheat record with `isCompliant: false` → anomaly cleared, one history row with `previousAnomaly = CHEAT`, `previousStatus: CHEAT` / `newStatus: NON_CONFORME` in the history API; with `true` → Conforme; Opérateur and kiosk token on an anomaly record → `403`, nothing changed, no history row; after the lift an Opérateur toggle works again; records without anomaly behave as before (idempotent no-op included); `GET /api/records/readers/{uid}` returns `status` for the four cases; `GET /api/records/anomalies` → only anomaly records of the period, newest first, reader filter, paging metadata, a lifted record no longer listed, empty period → empty page, more than 31 days → `400`.
- [X] T022 [P] [US4] In `src/test/java/com/rfidback/service/RecordStatsServiceTest.java` and `src/test/java/com/rfidback/controller/RecordStatsApiTest.java`, add spec US4 scenario 10 (100 crates of which 10 non-compliant and 2 Cheat, plus 5 doublons → `summary.total = 100`, `nonCompliant = 12`, `cheats = 2`, `duplicates = 5`, no doublon in the null-picker row, in `activities` or in `hours`), a picker with 30 records of which 2 Cheat (`total = 30`, `cheats = 2`), the reader filter on both counts, and the invariants of [data-model.md](data-model.md) (picker rows and hours add up to the summary).
- [X] T023 [P] [US4] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java` add the row `GET /api/records/anomalies` (anonymous 401, Opérateur 403, Administrateur not 401/403); in `src/test/java/com/rfidback/security/KioskReaderTokenSecurityTest.java` add: a valid reader token on `GET /api/records/anomalies` → `403`.

### Implementation for User Story 4

- [X] T024 [US4] In `src/main/java/com/rfidback/repository/RecordRepository.java` (research R10): add a constant `NOT_DUPLICATE = " and (r.anomaly is null or r.anomaly <> com.rfidback.entity.RecordAnomaly.DOUBLON_PERDU) "` appended to `IN_RANGE` in the eight dashboard aggregates (summary, by picker, by picker and activity, by UTC hour, each with and without reader); add `sum(case when r.anomaly = com.rfidback.entity.RecordAnomaly.CHEAT then 1 else 0 end) as cheats` to `COUNTS` and `Number getCheats()` to `CountsView`; add `countDuplicates` / `countDuplicatesForReader` (records of the range with `anomaly = DOUBLON_PERDU`); add `findAnomalies` / `findAnomaliesForReader` (`r.anomaly is not null` in the range, `@EntityGraph(attributePaths = { "tag", "reader", "activity", "picker" })`, `Pageable`, returning `Page<RecordEntity>`, ordered by `creationDate desc`).
- [X] T025 [US4] In `src/main/java/com/rfidback/service/RecordStatsService.java`: set `cheats` in `toCounts` (and zero for the hours-only picker rows and empty hours); set `RecordStats.duplicates` from the new count; extract the period and reader resolution of `getStats` (`resolveDays`, range instants, reader lookup with its `404`) into a package-visible method returning the `[start, end)` instants and the reader, reused by T026. Depends on T024.
- [X] T026 [US4] In `src/main/java/com/rfidback/service/RecordService.java`: (a) `updateRecordConformity`: when `record.getAnomaly() != null`, refuse a kiosk reader and any user whose role is not `ADMINISTRATEUR` with `403` ("Only an Administrateur can change a Doublon / perdu or Cheat record"), otherwise always write a change with `previousAnomaly`, set `anomaly = null` and `compliant` as asked; the existing early return applies only when there is no anomaly (research R8); (b) **Done in `RecordStatsService`, next to the period resolution it shares, rather than `RecordService`:** `@Transactional(readOnly = true) AnomalyRecordsPage listAnomalies(StatsPeriod, LocalDate from, LocalDate to, UUID readerId, int page, int size)` using T025's resolution and T024's queries, mapping to `AnomalyRecord` (`readerUid` = reader name, picker names, `activityName`) and `PageMetadata` as `PickerService` does. In `src/main/java/com/rfidback/controller/RecordController.java` implement `listRecordAnomalies`. Depends on T024, T025.
- [X] T027 [P] [US4] In `src/main/java/com/rfidback/configuration/SecurityConfig.java`, user chain, add `.requestMatchers(path(HttpMethod.GET, "/api/records/anomalies")).hasRole(ADMINISTRATEUR)` next to the conformity-history rule, before the generic `/api/records/**` rule. The kiosk chain is unchanged.
- [X] T028 [P] [US4] In `front/reader.html`, `renderBoxes`: show the status from `record.status` with an icon and a short label each (Conforme, Non conforme, Doublon / perdu, Cheat) and a distinct card class for the two anomalies; a click on a Conforme / Non conforme card toggles as today; on an anomaly card, an Administrateur session (role from `requireRole`'s result; never in kiosk mode) gets two inline buttons "Conforme" / "Non conforme" calling the PATCH, anyone else gets no action. Replace the `alert("Erreur mise à jour")` of `toggleCompliance` by an in-page message (no browser dialog).
- [X] T029 [P] [US4] In `front/index.html`: add two summary figures "Doublon / perdu" (`stats.duplicates`) and "Cheat" (`stats.summary.cheats`), which for an Administrateur link to `anomalies.html` with the current period and reader in the query string; add a "Cheat" column after "Non conformes" in the picker table, its Total row and the CSV (`csvCounts`, header). The other figures need no change: the server already leaves doublons out.
- [X] T030 [US4] Create `front/anomalies.html` (Administrateur only: `requireRole('ADMINISTRATEUR')`, same navbar and period / reader controls as `index.html`, initial values from the query string): table of `GET /records/anomalies` (date in the station time zone, ligne, tag, statut, activité, cueilleur), newest first, "Page suivante / précédente" from `metadata`, an empty-state message, and per row two buttons "Conforme" / "Non conforme" calling `PATCH /records/{id}/conformity` through `apiFetch`, then reloading the page of results; errors shown in the page, no browser dialog. Depends on T026.
- [X] T031 [US4] Run `mvn clean test -Dtest='RecordServiceTest,RecordApiTest,RecordStatsServiceTest,RecordStatsApiTest,AccessMatrixSecurityTest,KioskReaderTokenSecurityTest,WorkHoursApiTest'` and fix until green.

**Checkpoint**: quickstart sections 7, 8 and 9 pass.

---

## Phase 7: User Story 5 — Régler les deux durées (Priority: P2)

**Goal**: the Administrateur reads and changes the two delays on a settings page; the next scan uses the new value (FR-008, FR-012 to FR-014).

**Independent Test**: as `admin`, `PUT /api/scan-settings` `{ rereadWindowMinutes: 10, cheatWindowSeconds: 2 }`, then rescan a tag counted 15 minutes earlier → Doublon / perdu, without restart; `GET` shows 10 with `updatedBy: "admin"` and 2 with no author.

### Tests for User Story 5

- [X] T032 [P] [US5] Create `src/test/java/com/rfidback/service/ScanSettingsServiceTest.java` (Mockito, fixed `Clock`): no row → defaults from the two properties, null author and date; saving a different value writes the row with the user and the clock's time; saving the value in force writes nothing and keeps author and date; minutes are stored as seconds × 60; `windows()` reflects a saved row on the next call.
- [X] T033 [P] [US5] Create `src/test/java/com/rfidback/controller/ScanSettingsApiTest.java` (`@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")`, real `admin` and `operateur` users as in `WorkHoursApiTest`): `GET` on a fresh database → 30 and 2; `PUT` with `csrf()` then `GET` round trip; `-1`, `1441`, `61`, `2.5`, a missing field → `400` and values unchanged; after `PUT` of a 10-minute delay, a scan of a tag last read 15 min ago gives a doublon (effect on the next scan, SC-006); stored records are not changed by a `PUT`. Clean `scan_setting` in `setUp` so other classes keep the defaults.
- [X] T034 [P] [US5] In `src/test/java/com/rfidback/security/AccessMatrixSecurityTest.java` add rows for `GET` and `PUT /api/scan-settings` (anonymous 401, Opérateur 403, Administrateur not 401/403); in `src/test/java/com/rfidback/security/KioskReaderTokenSecurityTest.java` add: a valid reader token on both → `403`.

### Implementation for User Story 5

- [X] T035 [US5] In `src/main/java/com/rfidback/service/ScanSettingsService.java`, add `Clock` and `UserRepository` and: `@Transactional(readOnly = true) ScanSettings getSettings()` (each `ScanSetting` with `value` in minutes or seconds, `updatedAt`, `updatedBy` username, null for a default); `@Transactional ScanSettings updateSettings(UpdateScanSettings)` writing only the values that differ from those in force, with the session's user (as `LineActivityService.currentUser()`) and `OffsetDateTime.now(clock)`, returning `getSettings()` (research R7). Create `src/main/java/com/rfidback/controller/ScanSettingsController.java` implementing `ScanSettingsApiDelegate`. Depends on T006.
- [X] T036 [P] [US5] In `src/main/java/com/rfidback/configuration/SecurityConfig.java`, user chain, add `.requestMatchers(path(null, "/api/scan-settings")).hasRole(ADMINISTRATEUR)` next to the work-hours rule.
- [X] T037 [US5] Create `front/settings.html` (Administrateur only, navbar as `work-hours.html`): two number fields "Relecture ignorée (minutes)" 0–1440 and "Deux tags en même temps (secondes)" 0–60 with a line of help each (0 = règle désactivée; for the second field, FR-013's two consequences: "À 0, deux tags d'un même seau lus ensemble comptent pour deux caisses." and "Trop élevé, deux caisses d'un même cueilleur qui se suivent n'en comptent qu'une, au statut Cheat."), the last author and date under each field, "Enregistrer" calling `PUT /scan-settings` through `apiFetch`; client-side check of integers and bounds before the call, the server's refusal shown in the page; no browser dialog. Depends on T035.
- [X] T038 [P] [US5] Add the navbar link "Réglages" (`settings.html`, `data-admin-only`) to `front/index.html`, `front/pickers.html`, `front/work-hours.html`, `front/readers.html`, `front/activities.html`, `front/tags.html`, `front/buckets.html`, `front/users.html` and `front/anomalies.html`, after "Utilisateurs".
- [X] T039 [US5] Run `mvn clean test -Dtest='ScanSettingsServiceTest,ScanSettingsApiTest,AccessMatrixSecurityTest,KioskReaderTokenSecurityTest,TagScanApiTest'` and fix until green.

**Checkpoint**: quickstart section 3 passes.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T040 Run `mvn clean install` from the repository root: all tests green, test classes in alphabetical order as on CI (`ScanSettingsApiTest` must leave `scan_setting` empty for the classes after it).
- [X] T041 [P] Check the DDL Hibernate generates for `record.anomaly`, `record_conformity_change.previous_anomaly` and `scan_setting.name` on H2 and on a local PostgreSQL (`prod`-like profile): whether a `check (… in (…))` constraint is created. Write the answer in the comment of `RecordAnomaly.java` and in [research.md](research.md) R1 (a future third value would then need a startup upgrade like `configuration/ConformityAuthorSchemaUpgrade`). **Done for H2 (native `enum` type); PostgreSQL not verified, no server at hand — noted in both places.**
- [X] T042 [P] Update `CLAUDE.md`: Record's status (boolean plus `record.anomaly`, four values), the five scan rules in place of the "same station day rescan" and "one crate" sentences, `tag.last_read_at`, table `scan_setting` with `GET/PUT /api/scan-settings` and `front/settings.html`, `GET /api/records/anomalies` and `front/anomalies.html`, the Administrateur-only lift, doublons out of the dashboard totals.
- [X] T043 [P] Add an "Amended by spec `015`" note to `.specify/specs/004-scan-tag-conformite/spec.md` (FR-006 binary conformity, FR-009 rescan, FR-010 second tag of a crate; its message `"Tag already scanned today"` is kept, now also on a later day), `.specify/specs/005-consultation-lectures-conformite/spec.md` (FR-003/FR-004: anomaly records are Administrateur only) and `.specify/specs/007-tableau-de-bord/spec.md` (totals without Doublon / perdu, new counts), each pointing to the FR of spec 015.
- [X] T044 Run [quickstart.md](quickstart.md) sections 2 to 9 in a browser against a local run and tick each step; set spec.md **Status** to Delivered with the date.
- [X] T045 Run [quickstart.md](quickstart.md) section 10 (3 readers in parallel, 900 scans per pass; paths: count, ignored re-read, pair of tags of one bucket) and write the p95 figures under SC-005 in [spec.md](spec.md).

---

## Dependencies & Execution Order

### Phase dependencies

- Phase 1 → Phase 2 → user stories → Phase 8.
- US1 → US2 → US3 in this order: the three change `TagService.registerScan` and its two test classes.
- US4 needs Phase 2 only for its code, but its tests create doublon and cheat records through scans, so it comes after US3.
- US5 needs Phase 2 only (T006) and can be built in parallel with US1–US4; T038 touches `front/anomalies.html`, so run it after T030.

### Within the stories

- T002 → T007; T003 → T004; T005 → T006.
- T011 → T015 → T019 (same file).
- T024 → T025 → T026 → T030; T035 → T037.
- Tests (T009–T010, T013–T014, T017–T018, T021–T023, T032–T034) are written first and fail until the implementation of their story lands.

### Parallel opportunities

- Phase 2: T003 and T005 together after T002; T006 and T007 together after T004 and T005.
- US1 to US3: the two test tasks of each story together.
- US4: T021, T022, T023 together; T027, T028, T029 alongside T024–T026.
- US5: T032, T033, T034 together; T036 and T038 alongside T035 and T037.
- Polish: T041, T042, T043 together.

## Parallel Example: User Story 4

```text
Task: "T021 [US4] lift and anomalies list in RecordServiceTest.java and RecordApiTest.java"
Task: "T022 [US4] counts and totals without doublons in RecordStatsServiceTest.java and RecordStatsApiTest.java"
Task: "T023 [US4] access rows in AccessMatrixSecurityTest.java and KioskReaderTokenSecurityTest.java"
Task: "T028 [US4] four statuses in front/reader.html"
Task: "T029 [US4] figures, Cheat column and CSV in front/index.html"
```

## Parallel Example: User Story 5

```text
Task: "T032 [US5] ScanSettingsServiceTest in src/test/java/com/rfidback/service/ScanSettingsServiceTest.java"
Task: "T033 [US5] ScanSettingsApiTest in src/test/java/com/rfidback/controller/ScanSettingsApiTest.java"
Task: "T034 [US5] access rows in AccessMatrixSecurityTest.java and KioskReaderTokenSecurityTest.java"
```

## Implementation Strategy

### MVP first

1. Phases 1 and 2.
2. Phase 3 (US1): re-reads within 30 minutes stop creating records. Stop and validate (quickstart section 4, with the delay set through `app.scan.reread-window`).
3. Phases 4 and 5 (US2, US3): the three scan rules are complete (SC-001). The statuses are stored and returned by the API but the dashboard still counts doublons in its totals.

### Incremental delivery

- Do not deploy US1–US3 without US4: until T024, a Doublon / perdu record is counted in the dashboard totals, and nothing shows the statuses. US1 to US4 are the first deliverable (one PR to `dev`).
- US5 can ship in the same PR or a follow-up: until then the delays are the installation's properties (30 min, 2 s).
- From the deployment on, the dashboard's totals no longer include doublons; earlier periods keep their old rescans (spec, Edge Cases).

## Validation notes (2026-11-15)

- T044: quickstart sections 3 to 9 run against a local app (in-memory H2, port 18080), 31/31 API checks passed
  (`target/check015/walkthrough.py`, not kept in the repository). In Chrome, logged in as an Administrateur: the
  dashboard shows the Doublon / perdu and Cheat figures and the Cheat column; the line screen shows the four statuses
  and the lift buttons on anomaly cards, a lifted Doublon / perdu turns into an ordinary card; `anomalies.html`
  lists the day's anomalies and a correction removes the row; `settings.html` shows both values with their author.
  No console error. The Opérateur and kiosk views were not opened in the browser: their `403` are covered by
  `RecordApiTest`, `KioskReaderTokenSecurityTest` and `AccessMatrixSecurityTest`.
- T045: p95 10.3 ms (pairs of tags of one bucket, count then Cheat) and 7.0 ms (ignored re-reads), 900 scans each,
  3 readers in parallel; written under SC-005.
- Seen during T044, left as it is: the line kiosk's "lectures sans activité depuis minuit" banner still counts
  Doublon / perdu records (analysis finding I2).
