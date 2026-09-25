# Front contract: authentication in `front/`

Behaviour every page in `front/` must follow once auth is in place. Backend side: [openapi-auth.yaml](openapi-auth.yaml) and the access matrix in [spec.md](../spec.md).

## Shared script `front/auth.js`

Loaded after `config.js` on every page except `login.html`.

| Function | Contract |
|---|---|
| `apiFetch(path, options)` | Calls `CONFIG.API_URL + path` with the same-origin cookie. On `POST/PUT/PATCH/DELETE`, adds header `X-XSRF-TOKEN` with the value of cookie `XSRF-TOKEN`. On `401` → redirects to `login.html?next=<current page>`. On `403` → shows "Accès refusé" and does not redirect. Returns the `Response` otherwise. Pass `{ silent: true }` for background polling: a 403 is then returned without an alert. |
| `requireRole(...roles)` | Calls `GET /api/auth/me`. Not logged in → redirect to login. Logged in with a role not in `roles` → shows "Accès refusé". Resolves with the current user (`username`, `role`). |
| `logout()` | `POST /api/auth/logout`, then redirects to `login.html`. |

## Pages and required roles

| Page | Roles | Notes |
|---|---|---|
| `login.html` (new) | anyone | `POST /api/auth/login`, then goes to `next` or a default page by role |
| `index.html` (dashboard) | Opérateur, Administrateur | Remove `API_TOKEN` and all `x-api-token` headers |
| `reader.html` | Opérateur, Administrateur, or kiosk mode (reader token) | Stop requiring/sending `apitoken` from `GET /readers` (`reader.html:156-163,196-199,259-262`). Kiosk mode: see below |
| `pickers.html` | Administrateur | |
| `readers.html` | Administrateur | Shows `apitoken` (Administrateur only) |
| `users.html` (new) | Administrateur | List, create, change role, enable/disable, reset password |

Default page after login: Opérateur → `reader.html`; Administrateur → `index.html`.

## Rules

- No token, password or secret may appear in any file under `front/` (spec SC-003). The kiosk token only reaches the browser at run time, through the URL fragment.
- Pages never decide access on their own: hiding a link is cosmetic, the API enforces the matrix.
- Reader devices are not affected: they keep calling `POST /api/tags/scan` with `x-api-token`, with no cookie and no CSRF header.

## Kiosk mode (amendment 2026-09-25, spec FR-005a/FR-005b, research R15)

The line kiosk opens `reader.html#reader=<reader uid>&token=<reader token>` (URL-encoded values).

| Piece | Contract |
|---|---|
| Start (`auth.js`, on load) | If the fragment has both `reader` and `token`, store them in `sessionStorage` (`kioskReader`, `kioskToken`) and remove the fragment with `history.replaceState`. Never `localStorage`. A fragment with only one of the two is ignored and removed. |
| `isKioskMode()` | `true` when `kioskToken` is in `sessionStorage`. |
| `apiFetch` in kiosk mode | Adds header `x-api-token: <kioskToken>`, sends no cookie (`credentials: 'omit'`) and no `X-XSRF-TOKEN`. On `401` → calls `showKioskUnavailable()` (full-screen "Kiosque désactivé, contactez un administrateur"), no redirect to `login.html`. On `403` → same behaviour as today (`silent` respected). |
| Other pages | `sessionStorage` is per tab, so kiosk mode never leaks to a normal browser tab. If the kiosk tab itself reached another page, its calls would get `403` (the reader token opens nothing else, research R11); the kiosk browser is full screen with no navigation, so no special handling. |
| `reader.html` | In kiosk mode, skips the reader-selection screen and `requireRole`, and starts directly on `kioskReader`. Outside kiosk mode, unchanged. |

Launcher (kiosk machine, outside `front/`, documented in `deploy/INSTALL.md`): reads the token from the reader's config
file and runs the browser in kiosk mode on that URL.
