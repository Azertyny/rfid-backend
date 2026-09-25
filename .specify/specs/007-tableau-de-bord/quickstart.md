# Quickstart: validate the dashboard (007)

## Prerequisites

- Java 21, Maven. Backend contract: [contracts/openapi-dashboard.md](contracts/openapi-dashboard.md).
- A reader in `PRODUCTION` mode, a picker with a bucket and a tag (pages `readers.html`, `pickers.html`,
  `buckets.html`, `tags.html`), plus one tag in no bucket (for the "Non attribué" row).

## 1. Automated checks

```zsh
mvn clean test -Dtest='StationPropertiesTest,RecordStatsServiceTest,RecordStatsApiTest,AccessMatrixSecurityTest'
mvn clean install
```

Expected: all green. `RecordStatsApiTest` covers exact totals, the sums of picker rows and hours equal to the summary,
00:30 and 07:15 Paris, a DST day, the reader filter, corrected conformity, `400` and `404`.

## 2. Run locally

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

Open `front/index.html` (with `front/config.js` pointing at `http://localhost:8080/api`) and log in.

Create scans with the reader's token (from `readers.html`, as Administrateur):

```zsh
TOKEN=<reader token>
for uid in <tag in picker bucket> <tag in picker bucket> <tag without bucket>; do
  curl -s -X POST localhost:8080/api/tags/scan -H "x-api-token: $TOKEN" -H 'Content-Type: application/json' \
       -d "{\"uid\":\"$uid\",\"isCompliant\":true}"; sleep 11   # > duplicate window (10s)
done
```

## 3. Manual scenarios

| # | Action | Expected |
|---|---|---|
| 1 | Open the page as Opérateur, period "Aujourd'hui" | Summary 3 lectures, 100 % conformes; picker row with 2, "Non attribué" row with 1, last; footer total 3; bar at the current Paris hour; period shown with "Europe/Paris" |
| 2 | In `reader.html`, mark one of the picker's scans non conforme; wait ≤ 60 s or click "Actualiser" | Summary 1 non conforme, taux 66,7 %; picker row 1 non conforme |
| 3 | Select "Hier" | Empty state "Aucune lecture sur la période", no chart bars, no demo data; no auto-refresh (Network tab) |
| 4 | "Personnalisée" with 01/09 → 30/09 (30 days) | Loads. With 01/08 → 30/09: the page's own message next to the dates, no figures shown, no `/records/stats` call in the Network tab (the server's `400` is covered by `RecordStatsApiTest`) |
| 5 | Select the reader, then another reader with no scans | Same figures, then empty state |
| 6 | "Exporter CSV", open in Excel FR | Accents correct, columns split on `;`, rate `66,7`, last line `Total` |
| 7 | Rename a picker to `=1+1` (pickers.html), reload, export | Name shown as text on the page; in Excel the cell shows `=1+1` as text, not `2` |
| 8 | Log out, call `GET /api/records/stats` | `401` |
| 9 | Network tab while on the page | Only `/api/auth/me`, `/api/readers`, `/api/records/stats`; no `/api/tags`, no `/api/pickers`, no `x-api-token` header |

## 4. Time zone spot check

```zsh
curl -s -b cookies.txt 'localhost:8080/api/records/stats?period=TODAY' | jq '{from,to,timeZone,summary}'
```

With the server started under `TZ=UTC` (`TZ=UTC mvn spring-boot:run …`), `from`/`to` are still the Paris date and the
hourly bars still sit at Paris hours.

## 5. Volume check on PostgreSQL (SC-002)

Against a PostgreSQL database (prod profile or a local container), with one existing reader and tag, seed 200 000
records over 31 days in 2031, spread across the pickers, ~3 % non conforming:

```sql
insert into record (id, reader_id, tag_id, picker_id, conformity, creation_date)
select gen_random_uuid(), :'reader_id'::uuid, :'tag_id'::uuid,   -- psql: \set reader_id '<uuid>'
       (select id from picker where g > 0 order by random() limit 1),  -- refers to g: drawn again for each row
       random() > 0.03,
       timestamptz '2031-01-01 05:00+00' + (floor(random() * 31) || ' days')::interval
                                         + (random() * interval '12 hours')
from generate_series(1, 200000) g;
```

```zsh
time curl -s -o /dev/null -b cookies.txt \
  'https://<host>/api/records/stats?period=CUSTOM&from=2031-01-01&to=2031-01-31'
```

Expected: under 1 s, `summary.total` = 200 000; same with `&readerId=<reader id>` (the query variant with a reader).
Delete the seeded rows afterwards (`delete from record where creation_date >= '2031-01-01' and creation_date < '2031-02-02'`).
