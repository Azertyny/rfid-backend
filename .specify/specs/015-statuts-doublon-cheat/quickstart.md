# Quickstart: Statuts de lecture — relecture ignorée, doublon / perdu et cheat

Validation guide for spec 015. Contracts: [contracts/openapi-scan-status.md](contracts/openapi-scan-status.md); data:
[data-model.md](data-model.md); rule order: [research.md](research.md) R3.

## 1. Automated checks

```zsh
mvn clean install
mvn clean test -Dtest='TagServiceTest,TagScanApiTest,TagRegistrationApiTest'
mvn clean test -Dtest='ScanSettingsServiceTest,ScanSettingsApiTest,RecordServiceTest,RecordApiTest'
mvn clean test -Dtest='RecordStatsServiceTest,RecordStatsApiTest,AccessMatrixSecurityTest,KioskReaderTokenSecurityTest'
```

Expected: all green. They cover FR-001 to FR-014 as listed in research R11 and the invariants of the data model.

## 2. Local run

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

You need: a production reader `Ligne 1` and a second one `Ligne 2` with their tokens, an Opérateur account, a picker
A with bucket 1 holding three reference tags (T1, T2, T3) and a picker B with bucket 2 holding one (T4). Scans below
are `POST /api/tags/scan` with the reader's `x-api-token` and `{"uid": "...", "isCompliant": true}`.

To test the delays without waiting, set them low on the settings page (step 3) rather than ageing rows.

## 3. Settings (User Story 5, SC-006)

1. Log in as `admin`, open **Réglages** (`settings.html`): 30 minutes and 2 secondes, no author.
2. Save 1 minute and 5 secondes: the page shows both values with `admin` and the time. Reload: same.
3. Save again without changing anything: authors and dates are unchanged.
4. Type -1, 1441, 61 or 2,5: refused with a message, values in force unchanged.
5. Log in as the Opérateur: no "Réglages" link; `GET /api/scan-settings` answers `403`. With a reader token: `403`.

## 4. Ignored re-read (User Story 1, SC-002)

With the delay at 1 minute:

1. `Ligne 1` scans T1: one record, Conforme, picker A.
2. 15 s later `Ligne 1` scans T1 again, then `Ligne 2` scans it: `200`, `message: "Recent read ignored"`, still one
   record, unchanged.
3. Keep scanning T1 every 30 s for 3 minutes: still one record (the delay slides).
4. Register T1 into bucket 1 again and scan it: counted normally for A (a second, ordinary record).

## 5. Doublon / perdu (User Story 2)

1. Wait more than 1 minute after T1's last read, scan it on `Ligne 2`: a new record, status Doublon / perdu, no
   picker, `isCompliant: false` in the response; the earlier records are unchanged.
2. Set the delay to 0 and scan T1 twice more than 10 s apart: each scan gives a Doublon / perdu record.
3. A later day: leave T1 out of any bucket overnight (or rely on `TagScanApiTest`, which ages the counted record by
   a day) and scan it: Doublon / perdu again, not an ordinary record.
4. Scan a reference tag never registered nor counted: an ordinary record without picker, with the reader's verdict.

## 6. Cheat (User Story 3)

With the gap at 5 secondes:

1. `Ligne 1` scans T2 then T3 within 5 s: one record, status Cheat, picker A; T2 and T3 are out of bucket 1.
2. `Ligne 1` scans T4 (bucket 2) right after a tag of bucket 1: two ordinary records, no Cheat.
3. Register T2 and T3 again, scan T2 on `Ligne 1` and T3 on `Ligne 2` within 5 s: two ordinary records.
4. Set the gap to 0, register T2 and T3 again, scan both within 2 s: two ordinary records (detection off).

## 7. Line screen and correction (User Story 4)

1. Open `reader.html` for `Ligne 1` as the Opérateur: the four statuses are distinguishable; a Conforme or Non
   conforme card toggles as before; a Doublon / perdu or Cheat card offers no action.
2. Open the kiosk URL of `Ligne 1` (reader token): same as the Opérateur.
3. `PATCH /api/records/{id}/conformity` on the Cheat record as the Opérateur, then with the reader token: `403`,
   status unchanged.
4. Open `reader.html` as `admin`: the Cheat card offers "Conforme" / "Non conforme". Choose "Non conforme": the card
   is an ordinary non-compliant one, which the Opérateur can now toggle.
5. `GET /api/records/{id}/conformity-history` as `admin`: one entry, `previousStatus: CHEAT`,
   `newStatus: NON_CONFORME`, author `admin`.

## 8. Dashboard (User Story 4, SC-004)

1. Dashboard, Aujourd'hui, Tous les lecteurs: the summary shows the numbers of Doublon / perdu and Cheat; the picker
   table has a Cheat column; picker A's Total caisses still includes the Cheat crate.
2. The total does not include the Doublon / perdu records and "Non attribué" does not list them; Non conformes
   includes the Cheat records only on top of the ordinary non-compliant ones; the conformity rate matches
   (spec US4, scenario 10).
3. Export the CSV: the Cheat column and the Total row are there.
4. Choose `Ligne 2`: the counts are that reader's only.

## 9. Anomalies list (User Story 4, FR-015, SC-004)

1. As `admin`, click a dashboard anomaly figure: `anomalies.html` opens on the same period and reader; its row count
   equals Doublon / perdu + Cheat of the dashboard; newest first; Cheat rows show their picker.
2. Choose "Hier" (after a day with anomalies): yesterday's rows are listed.
3. Pass a Cheat to "Conforme": the row leaves the list; back on the dashboard the Cheat count is one lower and the
   picker's total is unchanged. Pass a Doublon / perdu to "Non conforme": the dashboard total is one higher, on
   "Non attribué".
4. A period without anomaly: an empty-state message, no error.
5. As the Opérateur: no link on the dashboard figures, `GET /api/records/anomalies` answers `403`; reader token:
   `403`.

## 10. Performance (SC-005)

Run spec 004's quickstart measurement (3 readers in parallel, 900 scans per pass) on three paths: counting,
ignored re-reads of one tag, pairs of tags of one bucket. Expected: p95 under 200 ms on each; write the figures
into spec.md SC-005.
