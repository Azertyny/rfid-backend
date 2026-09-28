# Data Model: Masquer l'enregistreur dans les choix de ligne

No change to the database, the entities or the API schemas.

## Reader (spec `002`, unchanged)

| Field    | Use in this feature                                                                   |
|----------|---------------------------------------------------------------------------------------|
| `mode`   | `PRODUCTION` → listed in the line chooser and the dashboard filter; any other value (today `ENREGISTREMENT`) → not listed (research R2). |
| `active` | Not part of the filter; only drives the « désactivé » mark, as before (research R3).  |
| `uid`    | Label of the entry in both lists, as before.                                          |

The mode is read from `GET /api/readers` when the page loads (research R6). A reader's records, and the
dashboard's "Tous les lecteurs" statistics, do not depend on its current mode (FR-003).
