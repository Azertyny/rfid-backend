# Quickstart: Envoi groupé des tags par le lecteur d'enregistrement

How to check the feature end to end. Contract: [contracts/openapi-registration-reads.md](contracts/openapi-registration-reads.md);
rules: [data-model.md](data-model.md), [research.md](research.md).

## Automated

```zsh
mvn clean test -Dtest=RegistrationReadsApiTest   # batch, limits, no session, resend, save, concurrency (SC-002, SC-004, SC-006)
mvn clean test -Dtest='RegistrationServiceTest,ReaderScanSecurityTest'   # production 403, token rules (SC-003)
mvn clean test -Dtest='TagScanApiTest,RegistrationApiTest'               # tag-by-tag unchanged (SC-005)
mvn clean install                                # full build, CI order
```

## Manual, dev profile

Prerequisites: an Administrateur (`APP_BOOTSTRAP_ADMIN_*`), one reader in `ENREGISTREMENT` mode (`$REG_TOKEN`) and
one in `PRODUCTION` mode (`$PROD_TOKEN`), tokens from `readers.html`.

```zsh
APP_BOOTSTRAP_ADMIN_USERNAME=admin APP_BOOTSTRAP_ADMIN_PASSWORD=change-me mvn spring-boot:run
```

```zsh
send() { curl -s -w ' %{http_code}\n' -X POST localhost:8080/api/tags/registration-reads \
           -H "x-api-token: $1" -H 'Content-Type: application/json' -d "$2"; }
BATCH='{"uids":["E2806915000040287477C993"," E2806915000050287477D48C ","E2806915000040287477C993",""]}'
```

1. **No session** — `send $REG_TOKEN "$BATCH"` → `200`, `sessionOpen: false`, `addedCount: 0`.
2. **One call fills the page (User Story 1)** — log in, open `tags.html`, pick the registration reader, click
   "Démarrer", then `send $REG_TOKEN "$BATCH"` → `200`, `receivedCount: 2`, `addedCount: 2`. Both tags appear
   together on the page, in that order.
3. **Resend** — same command again → `addedCount: 0`; the page is unchanged.
4. **Save** — enter a bucket number and save: same confirmations and result as with tags sent one by one.
5. **Production reader (User Story 2)** — `send $PROD_TOKEN "$BATCH"` → `403` with the "reserved for readers in
   ENREGISTREMENT mode" detail; no new line on that reader's kiosk (`reader.html`).
6. **No token** — `send '' "$BATCH"` → `401`.
7. **Limits** — `send $REG_TOKEN '{"uids":[""," "]}'` → `400`; 101 distinct UIDs → `400`, the page gains nothing.
8. **Tag by tag still works (User Story 3)** — with a session open, `POST /api/tags/scan` with `$REG_TOKEN` still adds
   the tag to the page.
