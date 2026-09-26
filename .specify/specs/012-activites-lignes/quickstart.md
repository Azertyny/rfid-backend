# Quickstart: Activités des lignes de production

How to check spec `012` once implemented. Routes and bodies: [contracts/openapi-activities.md](contracts/openapi-activities.md);
tables and rules: [data-model.md](data-model.md).

## 1. Automated checks

```zsh
mvn clean install                                   # regenerates ActivityApiDelegate, runs every test
mvn clean test -Dtest='Activity*Test,LineActivity*Test'
mvn clean test -Dtest='TagScanApiTest,AccessMatrixSecurityTest,KioskReaderTokenSecurityTest'
```

Expected: all green; existing `TagScanApiTest` cases pass without changes to their requests or expected responses
(SC-003).

## 2. Local run

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

Open `front/login.html`, log in as `admin`. You need two readers in `PRODUCTION` mode (L1, L2) and one in
`ENREGISTREMENT` (`front/readers.html`), and an Opérateur user (`front/users.html`).

## 3. Administrateur: activities and associations (User Story 1)

On `front/activities.html`:

1. Create "Fraise" and "Framboise" → both listed, active, no line.
2. Create "framboise " → refused, name already used.
3. Tick "Fraise" for L1, both for L2 → the grid shows exactly that after a reload.
4. The `ENREGISTREMENT` reader has no row in the grid; a direct `PUT /api/readers/{its id}/activities` gets `400`.
5. Log in as the Opérateur: the page is not in the navbar and `POST /api/activities` gets `403`.
6. SC-006: time the set-up of a new season on a fresh database — 5 activities, associations for 3 lines — from
   opening the page to the last "Enregistrer": under 5 minutes.

## 4. Kiosk: choosing the activity (User Stories 2 and 3)

Open the kiosk as the launcher does: `front/reader.html#reader=L2&token=<L2 token>`.

1. The warning banner shows "Aucune activité" and `0` records without activity.
2. Send a scan for L2:

   ```zsh
   curl -s -X POST http://localhost:8080/api/tags/scan -H 'x-api-token: <L2 token>' \
        -H 'Content-Type: application/json' -d '{"uid":"<uid from rfid_tag_list.csv>","isCompliant":true}'
   ```

   The response has the same fields as before; the kiosk shows the record as "Sans activité"; the banner count is 1.
3. Tap "Changer" then "Framboise" (2 gestures): the banner disappears, the header shows "Framboise".
4. Scan another tag: its box shows "Framboise"; the earlier one still "Sans activité".
5. With the L2 token, `PUT /api/lines/L1/current-activity` → `403`; with an activity not associated with L2 → `400`.

## 5. Clearing by the Administrateur (FR-007)

1. With "Framboise" current on L2, untick it for L2 → a modal names L2; confirm → the kiosk shows the banner again
   within 5 s.
2. Make "Fraise" current on L1, then disable "Fraise" → the modal names L1; confirm → L1 has no activity; "Fraise"
   stays on past records and in the grid as disabled.
3. Delete "Fraise" → refused (`409`), it was used. Create "Test", delete it → `204`.

## 6. Midnight reset (FR-008a)

Covered by `ActivityDailyResetTest` with a fixed clock. Manual check on a dev database:

1. Choose an activity on L1, stop the application.
2. In the H2 console (`app.security.allow-h2-console=true`), set `reader.current_activity_set_at` of L1 to yesterday.
3. Start the application: `line_activity_change` has a new `SYSTEM` row for L1 with `changed_at` = today 00:00 station
   time; the kiosk shows the banner; a scan now carries no activity.

## 7. Traceability (SC-005)

```sql
select r.name, p.name as previous, n.name as new, c.changed_at, c.author_type, u.username, ar.name as author_reader
from line_activity_change c
join reader r on r.id = c.reader_id
left join activity p on p.id = c.previous_activity_id
left join activity n on n.id = c.new_activity_id
left join app_user u on u.id = c.author_user_id
left join reader ar on ar.id = c.author_reader_id
order by c.changed_at;
```

Every change made in sections 4–6 appears once, with its author.
