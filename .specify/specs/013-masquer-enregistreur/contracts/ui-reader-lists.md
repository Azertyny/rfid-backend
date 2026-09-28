# UI contract: reader lists

The REST API is unchanged: `GET /api/readers`, `GET /api/records/stats` and every other route keep their request,
response and access rules (FR-005). This contract fixes what the two pages show.

## Line chooser — `front/reader.html`, logged-in user (Opérateur or Administrateur)

Not shown in kiosk mode (token in the URL fragment), unchanged.

| Reader returned by `GET /readers` | Shown in the grid                    |
|-----------------------------------|--------------------------------------|
| `mode: PRODUCTION`, `active: true`  | yes                                |
| `mode: PRODUCTION`, `active: false` | yes, marked « désactivé »          |
| `mode: ENREGISTREMENT` (any `active`) | no                               |

No reader left after this filter → the grid shows « Aucune ligne de production configurée ».

## Dashboard reader filter — `front/index.html`, `#readerSelect`

Options, in order: « Tous les lecteurs » (value empty, default), then the listed readers sorted by `uid` (`fr`
collation, as today).

| Reader returned by `GET /readers` | Option                              |
|-----------------------------------|-------------------------------------|
| `mode: PRODUCTION`, `active: true`  | `<uid>`                           |
| `mode: PRODUCTION`, `active: false` | `<uid> (désactivé)`               |
| `mode: ENREGISTREMENT` (any `active`) | none; no « (enregistrement) » suffix anywhere |

« Tous les lecteurs » sends no `readerId` to `GET /records/stats`: figures and CSV export include every production
record of the period, whatever the current mode of the reader that made it (FR-003).
