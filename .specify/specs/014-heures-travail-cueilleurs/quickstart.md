# Quickstart: Heures de travail des cueilleurs et caisses par activité

Validation guide for spec 014. Contracts: [contracts/openapi-work-hours.md](contracts/openapi-work-hours.md); data:
[data-model.md](data-model.md).

## 1. Automated checks

```zsh
mvn clean install
mvn clean test -Dtest='WorkHoursServiceTest,WorkHoursApiTest,RecordStatsServiceTest,RecordStatsApiTest'
mvn clean test -Dtest='AccessMatrixSecurityTest,KioskReaderTokenSecurityTest,PickerServiceTest'
```

Expected: all green. These cover FR-001 to FR-014 as listed in research R10, including the invariants of the data
model (row sums equal the Total, activity counts add up to each picker's total).

## 2. Local run

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

Open `front/login.html`, log in as `admin`. You need two pickers with buckets, a production line with two activities
(Fraise, Framboise; spec 012) and an Opérateur account.

## 3. Morning entry (User Story 1, SC-001)

1. Open **Heures** (`work-hours.html`): today is selected, every picker is listed with 0 caisse and no hours.
2. Enter 7,5 for picker A, use "remplir les vides" with 8, save. Reload: A = 7,5 h, every other picker 8 h, the
   author is `admin`.
3. Change A to 8, save: only 8 h remains (no sum). Clear B's field, save: B is back to empty.
4. Pick tomorrow: the page shows the server's refusal (future day), nothing saved. Type 7,3 or 25: refused, nothing
   saved (all or nothing).
5. Time the entry for 30 pickers with the helper plus a few corrections: under 5 minutes.

## 4. Dashboard (User Story 2)

1. On the kiosk, choose Fraise and scan 2 buckets of A, switch to Framboise and scan 1 of A, clear the activity and
   scan 1 of B (spec 012).
2. Dashboard, Aujourd'hui, Tous les lecteurs: columns Heures | Fraise | Framboise | Sans activité | Total caisses …;
   A = 8 h, 2, 1, 0, 3; B shows 0/0/1 and 1 caisse with its hours; Total row = sums; a notice says the day is in
   progress.
3. Remove B's hours on the Heures page, refresh: B shows "non saisi"; a picker with hours and no scan shows its hours
   and 0.
4. Select the line: the Heures column disappears with the hint to choose "Tous les lecteurs"; activity columns stay.
5. Export CSV: same columns and Total row, opens in Excel FR (accents, decimal comma).
6. Choose "7 derniers jours" after entering hours on two days: Heures = sum of both days.

## 5. Missing hours (User Story 3, SC-004)

On a day with scans for 3 pickers and hours for 1, the Heures page flags the other 2 "Heures manquantes".

## 6. Access (FR-006)

As the Opérateur: no "Heures" link, `GET /api/work-hours` → 403; the dashboard still shows the hours. With a reader
token (`x-api-token`): `GET /api/work-hours` → 403.

## 7. Performance (SC-003)

Reuse the spec 007 quickstart section 5 data set on PostgreSQL (200 000 records over 31 days), add activities to the
records and one hours row per picker and day, then time `GET /api/records/stats?period=CUSTOM&from=…&to=…` with and
without `readerId`: under 1 s.
