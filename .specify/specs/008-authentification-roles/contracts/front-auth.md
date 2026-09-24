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
| `reader.html` | Opérateur, Administrateur | Stop requiring/sending `apitoken` (`reader.html:156-163,196-199,259-262`) |
| `pickers.html` | Administrateur | |
| `readers.html` | Administrateur | Shows `apitoken` (Administrateur only) |
| `users.html` (new) | Administrateur | List, create, change role, enable/disable, reset password |

Default page after login: Opérateur → `reader.html`; Administrateur → `index.html`.

## Rules

- No token, password or secret may appear in any file under `front/` (spec SC-003).
- Pages never decide access on their own: hiding a link is cosmetic, the API enforces the matrix.
- Reader devices are not affected: they keep calling `POST /api/tags/scan` with `x-api-token`, with no cookie and no CSRF header.
