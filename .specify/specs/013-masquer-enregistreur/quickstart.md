# Quickstart: Masquer l'enregistreur dans les choix de ligne

Manual validation (no front test tooling in the repository, research R7). Expected lists: see
[contracts/ui-reader-lists.md](contracts/ui-reader-lists.md).

## Prerequisites

```zsh
mvn clean test   # backend unchanged: must stay green
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

Open `front/login.html`, log in as the Administrateur, and on `readers.html` create:

- `L1` in `PRODUCTION`, active;
- `L2` in `PRODUCTION`, then disable it;
- `E` in `PRODUCTION`, scan a reference tag with its token (`POST /api/tags/scan`) so it has a production record
  today, then switch it to `ENREGISTREMENT`.

On `users.html`, create an Opérateur.

## Scenarios

1. **Line chooser, Opérateur** (US1-1, US1-3): log in as the Opérateur → `reader.html` shows `L1` and `L2`
   (« désactivé »), not `E`.
2. **Line chooser, Administrateur** (US1-4): same page as the Administrateur → same list.
3. **Dashboard filter** (US2-1, US2-2): open `index.html` → the « Lecteur » filter lists « Tous les lecteurs »,
   `L1`, `L2 (désactivé)`; no `E`, no « (enregistrement) ».
4. **Totals kept** (US2-3, FR-003): period « Aujourd'hui », « Tous les lecteurs » → `E`'s record is counted; the
   CSV export contains it.
5. **Back to production** (US1-2, FR-004): switch `E` to `PRODUCTION`, reload both pages → `E` is listed again.
6. **No production line** (edge case): with every production reader switched to `ENREGISTREMENT`, `reader.html`
   shows « Aucune ligne de production configurée » and the dashboard filter only « Tous les lecteurs ».
7. **Kiosk unchanged**: open `reader.html#…` with a reader token as the launcher does → no chooser, the line opens
   directly, as before.
