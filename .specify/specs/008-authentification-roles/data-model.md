# Data Model: Authentification et rôles

**Spec**: [spec.md](spec.md) | **Research**: [research.md](research.md)

## New entity: User (`UserEntity`, table `app_user`)

| Field | Type | Constraints | Notes |
|---|---|---|---|
| `id` | UUID | PK, generated | Same strategy as other entities (`GenerationType.UUID`) |
| `username` | string(50) | not null, unique | Stored lower-cased and trimmed, so uniqueness is case-insensitive |
| `passwordHash` | string(100) | not null | Delegating encoder format, e.g. `{bcrypt}$2a$...`; never returned by the API |
| `role` | enum `Role` | not null | `ADMINISTRATEUR` or `OPERATEUR`; stored as string (`@Enumerated(STRING)`) |
| `enabled` | boolean | not null, default true | A disabled user cannot log in and loses open sessions |
| `creationDate` | timestamp | not null, not updatable | `@CreationTimestamp`, like other entities |
| `updateDate` | timestamp | not null | `@UpdateTimestamp`, like `ReaderEntity` |

Table name `app_user` because `user` is reserved in PostgreSQL (prod database, `application-prod.yml`).

### Validation rules

- `username`: 3 to 50 characters, letters, digits, `.`, `_`, `-` only.
- Password (create and reset): at least 8 characters. Never logged, never echoed back.
- Duplicate `username` (after normalisation) → `409`.
- Any change that would leave zero enabled `ADMINISTRATEUR` users → `409` (disable, role change).

### State transitions

```text
         create (Administrateur)
              │
              ▼
          [enabled] ──disable──▶ [disabled]
              ▲                      │
              └──────enable──────────┘
```

- No delete transition: users are only disabled, because they are referenced as authors (spec `005` override history).
- `disable` and role change expire the user's open sessions (research R6).
- Password reset does not change state; it also expires open sessions.

## Enum: Role

| Value | Spring authority | Meaning |
|---|---|---|
| `ADMINISTRATEUR` | `ROLE_ADMINISTRATEUR` | All routes (spec matrix) |
| `OPERATEUR` | `ROLE_OPERATEUR` | Read pickers, read readers (no tokens), records, dashboard |

## Unchanged: Reader (`ReaderEntity`)

Stays a separate machine credential (`apitoken`), unrelated to `User`. Only change is in the API contract: `apitoken` becomes optional in the `Reader` schema and is only filled for Administrateurs (research R8).

## Relationships

- `User` has no relationship to the existing domain entities in this feature.
- Future (spec `005`, out of scope here): the compliance change history references `User` as author (many changes → one user).
