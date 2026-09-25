# Research: Tableau de bord (007)

The spec has no open `NEEDS CLARIFICATION` items after the 2026-09-24 and 2026-09-25 sessions (the header note about
`doc/` is informational). This file records the design decisions the plan depends on, and why the two generated helper
documents (`doc/sp_cification_bi_pour_claude_code.md`, `doc/tableau_de_bord_bi_analyses.html`) are only partly reused.

What already exists (checked on 2026-09-25):

- `front/index.html` has no token anymore, loads through `auth.js`, calls `requireRole('ADMINISTRATEUR', 'OPERATEUR')`,
  loads readers and pickers, and no longer polls `GET /api/tags` (comment at `front/index.html:178`).
- `/api/records/**` is already open to Administrateur and Opérateur (`SecurityConfig.java:128`), so a new
  `GET /api/records/stats` needs no new security rule, only a line in `AccessMatrixSecurityTest`.
- `record` has `picker_id` (nullable, the picker of the bucket at scan time, `TagService.java:82`), `reader_id`,
  `conformity` (current value, corrected by spec `005`) and `creation_date` (`OffsetDateTime`, set by
  `@CreationTimestamp`). Indexes: `(reader_id, tag_id, creation_date)` and `(reader_id, creation_date)`.
- A `Clock` bean exists (`ClockConfig`), in the JVM default zone.

## R1. What is kept from the generated helper documents

- **Kept**: the fixed questions (productivity per picker, load per reader, hourly throughput, conformity rate), CSV
  export with UTF-8 BOM and `;` separator, Bootstrap 5.3 + Font Awesome + Chart.js from the CDN, plain JS.
- **Dropped**:
  - the drag-and-drop pivot builder, saved views in `localStorage` and heatmap (FR-006);
  - crate type and weight: they do not exist in the model (`BucketEntity` has neither), and the prototype made them up
    by hashing the tag uid;
  - demo data (`generateMockBiDataset`) and attribution by `index % count` for pickers and readers — they show false
    figures as if they were real (spec Edge Cases);
  - the hardcoded token, the fixed `https://api.apolog.fr` URL and `x-api-token` on user routes (FR-003; the page uses
    `apiFetch` and `config.js`);
  - aggregation in the browser over `GET /tags?size=3000` (FR-002);
  - `innerHTML` with API values (XSS), `alert()`/`confirm()`, the UTC date (`toISOString`) mixed with the local hour
    (`getHours`);
  - the word "Opérateurs" for pickers: Opérateur is a user role (spec `008`); the page says "Cueilleurs".

## R2. One endpoint, three aggregates

- **Decision**: `GET /api/records/stats` returns in one response `summary`, `pickers` and `hours`
  ([contracts/openapi-dashboard.md](contracts/openapi-dashboard.md)). `RecordStatsService` runs three read-only
  queries in one `@Transactional(readOnly = true)` method, all with the same filter
  (`creationDate >= start and creationDate < end [and reader = :reader]`):
  1. summary: `count(r)`, `sum(case when r.compliant = false then 1 else 0 end)`;
  2. per picker: the same two values `group by` picker id, first name, last name, with a `left join r.picker` so
     records without a picker form one group with a null id;
  3. per hour: see R4.
- **Rationale**: one call, one consistent snapshot for the page; each aggregate is a plain `GROUP BY`, so volume does
  not matter (no truncation, SC-001). Counts only — the conformity rate is derived (`(total − nonCompliant) / total`),
  so the API cannot return a rate that disagrees with its counts.
- **Alternatives considered**: three endpoints (three calls and three snapshots that can disagree while scans come in);
  returning a rate (redundant, rounding rules in two places).

## R3. Period: a named period resolved by the server

- **Decision**: query parameter `period` = `TODAY` (default) | `YESTERDAY` | `LAST_7_DAYS` | `CUSTOM`. `CUSTOM` needs
  `from` and `to` (`YYYY-MM-DD`, inclusive). The server resolves the dates with `LocalDate.now(clock.withZone(zone))`:
  `LAST_7_DAYS` = today and the 6 days before. The range becomes
  `[from.atStartOfDay(zone), to.plusDays(1).atStartOfDay(zone))` as `OffsetDateTime`. The response echoes the resolved
  `from`, `to` and `timeZone`, and the page shows them.
- **Validation (400)**: `CUSTOM` without `from` or `to`; `from` after `to`; more than 31 days (`to − from + 1 > 31`);
  `from`/`to` given with a period other than `CUSTOM`; unknown `period` or badly formatted date. Future dates are
  allowed (zeros).
- **Rationale**: "today" is the station's today, not the browser's (FR-007). Letting the browser compute dates would
  bring back the time-zone bug of the prototype. `atStartOfDay(zone)` handles DST days (23 h / 25 h days).
- **Alternatives considered**: `from`/`to` only, computed in the browser (wrong near midnight for a browser in another
  zone); an `Instant` range (moves the day-boundary rule to the client).

## R4. Hourly totals in the station zone, portable between H2 and PostgreSQL

- **Decision**: group in SQL by UTC hour index — HQL
  `floor(extract(epoch from r.creationDate) / 3600)` — returning `(hourIndex, count, nonCompliant)`, at most
  31 × 24 + 1 groups. In Java, each index is turned into the local hour of day
  `Instant.ofEpochSecond(index * 3600).atZone(zone).getHour()` and added to a 24-slot array (hour of day 0-23, summed
  over the days of the range). The response always has 24 entries, hours without scans at 0.
- **Rationale**: `extract(epoch …)` is an absolute instant, so the result does not depend on the database session zone,
  the JVM zone or the column type — the traps of `extract(hour …)` on a `timestamp with time zone`. Hibernate 6 renders
  `extract(epoch)` for both H2 and PostgreSQL. Mapping after grouping keeps DST exact: a UTC hour always falls in one
  local hour for a zone whose offset is a whole number of hours (Europe/Paris, all of mainland Europe).
- **Limit (documented, checked at startup)**: a zone with a non-whole-hour offset (e.g. `Asia/Kolkata`) would split
  local hours. `StationProperties` refuses such a zone at startup: it checks the current offset and the offsets
  around the next 2 transitions. Historical transitions are ignored on purpose — Europe/Paris used local mean time
  `+00:09:21` until 1911, so checking `getTransitions()` would reject the default zone.
- **Verification during implementation (done 2026-09-25)**: Hibernate 6 accepts `floor(extract(epoch …) / 3600)` on
  H2; `RecordStatsApiTest` passes with it (winter/summer 07:15 Paris, 00:30 Paris), so the fallback was not needed. The
  query result is read through `Number` projections, since its Java type depends on the database.
- **Alternatives considered**: `extract(hour from …)` in SQL (depends on session zone; wrong across DST); dialect-specific
  native SQL `AT TIME ZONE` (two SQL versions to maintain and test); loading all records (the prototype's approach).

## R5. Station time zone property

- **Decision**: `app.station.time-zone: ${APP_STATION_TIME_ZONE:Europe/Paris}` in `application.yml`, bound to a
  `ZoneId` through a small `@ConfigurationProperties("app.station")` record `StationProperties` (validated at startup:
  invalid zone id or non-whole-hour offset → the application does not start). The `Clock` bean does not change; the
  service uses `clock.withZone(zone)` for "today".
- **Rationale**: FR-007 (configurable, default Paris, independent of server and browser). Failing at startup is better
  than wrong figures.
- **Alternatives considered**: changing `ClockConfig` to the station zone (would silently change the behavior of
  registration expiry and duplicate-scan checks, which only use durations but read `now` in the default zone).

## R6. Reader filter

- **Decision**: optional query parameter `readerId` (UUID, like `/readers/{readerId}`). Unknown id → `404`, like
  `listLatestRecordsForReader`. The page's selector lists **all** readers returned by `GET /api/readers`, sorted by uid,
  with "(désactivé)" / "(enregistrement)" suffixes — a reader disabled or switched to `ENREGISTREMENT` today still has
  production records for past periods. "Tous les lecteurs" (no `readerId`) is the default.
- **Rationale**: the current page hides inactive and `ENREGISTREMENT` readers (`front/index.html:196`), which is right
  for a live view but hides history now that periods exist.
- **Alternatives considered**: reader uid as the filter (uids are case-insensitive unique but editable text; the UUID
  is stable).

## R7. Picker rows: attribution, names, order

- **Decision**: a record counts for `record.picker` (the picker at scan time, FR-009), never for the bucket's current
  picker. Each row carries `pickerId` (null for the "Non attribué" row), `firstname`, `lastname` (null for that row),
  `total`, `nonCompliant`. Order: `lastname`, `firstname` ascending, the null row last. Only pickers with at least one
  record in the range appear. The page no longer calls `GET /api/pickers`.
- **Rationale**: exact attribution is the point of the dashboard; names in the response avoid a second paged fetch;
  alphabetical order is stable for comparison and CSV (the page may offer sorting by column).
- **Alternatives considered**: returning ids only (the page would reload every picker, `size` ≤ 100 per page).

## R8. Index for the unfiltered range query

- **Decision**: add `@Index(name = "idx_record_creation_date", columnList = "creation_date")` on `RecordEntity`. The
  reader-filtered queries already use `idx_record_reader_date`.
- **Rationale**: without a reader filter, the range condition has no usable index (both existing ones lead with
  `reader_id`). `ddl-auto: update` creates missing indexes on existing tables at startup.

## R9. Front page (`front/index.html`)

- **Decision**: rewrite the dashboard part of the page, keeping its navigation bar, `auth.js`/`config.js` and
  `requireRole('ADMINISTRATEUR', 'OPERATEUR')`:
  - filter bar: period select (Aujourd'hui / Hier / 7 derniers jours / Personnalisée + two `<input type="date">`,
    checked in the browser for immediate feedback, the server stays the authority), reader select, "Actualiser",
    "Exporter CSV";
  - summary tiles: lectures, conformes, non conformes, taux de conformité, plus the resolved period and zone
    ("du 19/09 au 25/09/2026, heure de Europe/Paris");
  - picker table: Cueilleur | Lectures | Non conformes | Taux de conformité, "Non attribué" row styled apart, a footer
    total row equal to the summary;
  - hourly chart: Chart.js stacked bars (conformes / non conformes) over the hours from the first to the last hour with
    scans (07h → 18h typically), labels `07h`;
  - states: loading, empty ("Aucune lecture sur la période" — never demo data), error (message + "Réessayer"); a `400`
    shows the server's message next to the custom dates;
  - auto-refresh every 60 s only while the resolved range contains today and the tab is visible
    (`document.visibilityState`); otherwise manual refresh;
  - all API values inserted with `textContent` / `createElement`, never `innerHTML`;
  - Chart.js pinned to a version (`chart.js@4.4.4`) instead of the unversioned URL at `front/index.html:11`.
- **Rationale**: FR-005 structure; the security and correctness issues listed in R1; the auto-refresh rule avoids
  pointless polling on closed periods.
- **Alternatives considered**: a new `dashboard.html` page (the nav already points to `index.html` as "Production").

## R10. CSV export

- **Decision**: built in the browser from the last `pickers` response (no second call), file
  `tableau-de-bord_<from>_<to>[_<reader uid>].csv`, UTF-8 with BOM, `;` separator, CRLF, every field in double quotes
  (quotes doubled). Columns: `Cueilleur;Lectures;Conformes;Non conformes;Taux de conformité (%)`, rate with one decimal
  and a comma (`97,5`), empty when the row has 0 reads; last line `Total`. A field that starts with `=`, `+`, `-`, `@`,
  tab or CR gets a leading `'` (CSV formula injection: picker names are free text).
- **Rationale**: FR-005(e), Excel FR; formula injection is a real risk the prototype ignored.
- **Alternatives considered**: a server-side CSV endpoint (a second contract for the same data); `.xlsx` (needs a
  library).

## R11. Tests

- **Decision**:
  - `RecordStatsServiceTest` (Mockito, fixed `Clock`): period resolution (TODAY/YESTERDAY/LAST_7_DAYS/CUSTOM in
    Europe/Paris, including a clock at 23:30 UTC = 01:30 Paris the next day), validation errors, hour-index mapping
    (DST days in March and October), "Non attribué" ordering.
  - `RecordStatsApiTest` (`@SpringBootTest` + MockMvc, test profile, real H2): seeds records then sets their
    `creation_date` with `JdbcTemplate` (`@CreationTimestamp` overwrites values given on insert); checks exact totals,
    that picker rows and hours add up to the summary, midnight and 07:15 Paris cases, reader filter, `404`, `400`s,
    corrected conformity counted.
  - `AccessMatrixSecurityTest`: add `GET /api/records/stats` as `LOGGED_IN` (Administrateur and Opérateur).
  - The front is checked by hand ([quickstart.md](quickstart.md)); no JS test tooling is added
    (the generated doc's Vitest/Jest suggestion would need a `package.json` and a build step the project does not have,
    and the arithmetic now lives in the backend).
- **Rationale**: SC-001 is proven where the numbers are computed; the SQL must run on a real database, not mocks.
