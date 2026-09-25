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

---

## Amendment 2026-09-25: kiosk with the reader token

Research [R13](research.md#r13-auteur-dune-modification--utilisateur-ou-lecteur),
[R14](research.md#r14-ddl-auto-update-ne-retire-pas-un-not-null).

### Changed entity: Conformity change (`RecordConformityChangeEntity`, table `record_conformity_change`, spec `005`)

| Field | Column | Before | After |
|---|---|---|---|
| `author` | `author_id` → `app_user` | required | **optional**: set when a logged-in user made the change |
| `authorReader` (new) | `author_reader_id` → `reader` | — | **optional**: set when a reader token (kiosk) made the change |

Other fields unchanged (`record`, `previousCompliant`, `newCompliant`, `changedAt`).

**Invariant**: exactly one of `author` / `authorReader` is set. Checked by the entity before insert (`@PrePersist`
throws `IllegalStateException`); no database `CHECK` constraint, because `ddl-auto: update` does not add one to an
existing table. Rows are still never updated or deleted.

**Schema upgrade**: Hibernate adds `author_reader_id` and its foreign key on its own. `author_id` loses its `NOT NULL`
through the startup runner of research R14 (`ALTER TABLE record_conformity_change ALTER COLUMN author_id DROP NOT NULL`,
idempotent; checked on H2 2.3.232).

### Reader (`ReaderEntity`): no schema change

Its `apitoken` now also authenticates the line's kiosk on two routes (spec FR-005a). A disabled reader (`active = false`)
is refused (`401`) there as on `POST /api/tags/scan`. A reader can be the author of conformity changes, so, like users,
readers must not be deleted once referenced (there is no reader delete route today, spec `002`).

### Relationships (updated)

- Conformity change → User: many to one, optional (was required).
- Conformity change → Reader: many to one, optional (new).
