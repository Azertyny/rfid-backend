# Quickstart: validate authentication and roles

Proves the feature end-to-end. Contracts: [openapi-auth.yaml](contracts/openapi-auth.yaml), [front-auth.md](contracts/front-auth.md). Access matrix: [spec.md](spec.md).

## Prerequisites

- Java 21, Maven (or `./mvnw`)
- `curl`; a reader already registered (for step 5), or create one in step 3

## 1. Automated checks

```zsh
mvn clean install        # regenerates OpenAPI sources, compiles, runs all tests
mvn test -Dtest='*Security*'   # access-matrix and auth tests only
```

Expected: all tests green. The access-matrix test covers every route of the spec matrix for anonymous, Opérateur and Administrateur (SC-001).

## 2. Start with a bootstrap admin

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD='change-me-now' mvn spring-boot:run
```

Expected in the logs: the bootstrap admin is created on first start. Restart the app: nothing is created again (US3-2).

## 3. Log in as Administrateur (curl)

```zsh
J=$(mktemp)
curl -s -c $J -b $J http://localhost:8080/api/auth/me -o /dev/null -w '%{http_code}\n'   # 401, and sets XSRF-TOKEN
curl -s -c $J -b $J -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"change-me-now"}' http://localhost:8080/api/auth/login
XSRF=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -c $J -b $J http://localhost:8080/api/auth/me          # {"username":"admin","role":"ADMINISTRATEUR"}
curl -s -c $J -b $J http://localhost:8080/api/readers          # readers WITH apitoken
```

State-changing calls need the CSRF header, e.g. create an Opérateur:

```zsh
curl -s -c $J -b $J -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' \
  -d '{"username":"op1","password":"operateur-1","role":"OPERATEUR"}' http://localhost:8080/api/users
```

Same call without `X-XSRF-TOKEN` → `403`.

## 4. Check the Opérateur limits

Log in as `op1` with a fresh cookie jar, then:

| Call | Expected |
|---|---|
| `GET /api/readers` | `200`, no `apitoken` field |
| `GET /api/pickers` | `200` |
| `POST /api/pickers` (with CSRF header) | `403` |
| `GET /api/buckets` | `403` |
| `GET /api/users` | `403` |

## 5. Reader devices are unchanged

```zsh
curl -s -H "x-api-token: <reader token>" -H 'Content-Type: application/json' \
  -d '{"uid":"E2000017221101891400A23G","isCompliant":true}' http://localhost:8080/api/tags/scan
```

Expected: `200`, no `Set-Cookie`, no CSRF header needed (SC-004).

## 6. Disable a user

As admin, `PATCH /api/users/{op1 id}` with `{"enabled": false}`. Expected: `op1`'s existing session now gets `401`, and a new login fails with `401`. Disabling the last enabled Administrateur → `409`.

## 7. Front (browser, through nginx)

```zsh
cd deploy && docker compose --env-file .env up -d   # .env must define APP_BOOTSTRAP_ADMIN_*
```

- Open `http://localhost/` → redirected to `login.html`.
- Log in as Opérateur → lands on `reader.html`; selecting a reader works without a token; toggling compliance works.
- Open `pickers.html` as Opérateur → "Accès refusé".
- Log in as Administrateur → `users.html` lists `admin` and `op1`.
- `grep -rn "x-api-token\|API_TOKEN" front/` → no result (SC-003).
