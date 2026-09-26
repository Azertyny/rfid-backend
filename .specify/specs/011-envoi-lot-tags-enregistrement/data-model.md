# Data Model: Envoi groupé des tags par le lecteur d'enregistrement

No new table, no new column, no schema change. The feature writes to the tables of spec `003`.

## Registration reads batch (request, not stored)

| Field | Type | Rule |
|---|---|---|
| `uids` | array of string | Required, 1 to 1,000 elements (R4). Each element is trimmed; blank ones are ignored (FR-009); repeats are counted once. |

Derived values:

- **distinct UIDs**: trimmed, non-blank, de-duplicated in order of first appearance.
- Refused with `400`, nothing written, session not touched (FR-008, R4): no distinct UID; more than 100 distinct UIDs;
  a distinct UID longer than 50 characters.

## Registration read (`registration_read`, spec `003`, unchanged)

`id`, `session_id` (FK, not null), `uid` (≤ 50), `first_read_at`; unique `(session_id, uid)`.

This feature inserts one row per distinct UID the session does not have yet, all in one transaction (FR-010), with
`first_read_at = now + i µs` (R6).

## Registration session (`registration_session`, spec `003`, unchanged)

`lastActivityAt` is set once per accepted call (FR-005). A session found expired by a call is deleted with its reads,
as `recordRead` does today (FR-006).

## Reader (spec `002`, unchanged)

`mode` decides access: `ENREGISTREMENT` → accepted; `PRODUCTION` → `403` (FR-003). `active = false` → `401` from
the token filter (FR-002).

## Outcomes of a call

| State of the reader's session | Result | `sessionOpen` | `addedCount` |
|---|---|---|---|
| none | nothing written | `false` | 0 |
| expired | session and its reads deleted | `false` | 0 |
| open | new distinct UIDs inserted, `lastActivityAt` = now | `true` | number inserted |

## Repository additions

- `RegistrationSessionRepository.findLockedByReader(reader)`: `@Lock(PESSIMISTIC_WRITE)`, lock wait bounded to 5 s
  (`jakarta.persistence.lock.timeout` hint), loads `reader` and `startedBy` like `findByReader` (R5).
- `RegistrationReadRepository.findUidsBySessionAndUidIn(session, uids)`: the UIDs among `uids` the session already has.
