# Data Model: Contrôle des tags par rapport à la liste de référence

Revision of 2026-09-26: tags not in the reference list never enter the database, and those already stored are deleted
once at deployment ([research](research.md) R4–R8). The reference list itself is unchanged since the first delivery
(R1–R3).

## Reference tag list (in memory, unchanged)

| Aspect | Value |
|---|---|
| Source | `src/main/resources/tags/rfid_tag_list.csv`, set by `app.tags.reference-list` |
| Format | one UID per line, 24 hexadecimal characters, no header; `\n` or `\r\n`; optional UTF-8 BOM |
| Content at delivery | 5,008 unique UIDs |
| Held as | immutable set of the last 12 characters of each UID, upper-cased, loaded once at startup (`ReferenceTagList`) |
| Changes | only by editing the file and deploying a new version (clarification Q1) |

**Validation at startup** (R2): the application does not start when the resource does not exist or cannot be read;
no UID is found; a non-blank line, once trimmed, is not 24 characters `[0-9A-Fa-f]`; two lines end with the same 12
characters (the error names both line numbers). Blank lines are skipped.

**Membership** (R3): `isOffList(uid)` is false when the last 12 characters of `uid.trim().toUpperCase(Locale.ROOT)`
are in the set. A `null` or blank UID, or one shorter than 12 characters, is off-list. So `E2806915000040287477C993`
(as the readers send it) and `E2806915200040287477C993` (as in the file) are both in the list.

## New entity: `DataUpgradeEntity` (table `data_upgrade`)

Records one-off data changes already applied, so that each runs only once (R8).

| Column | Type | Constraint | Meaning |
|---|---|---|---|
| `name` | varchar(100) | primary key | identifier of the change; `010-off-list-tag-purge` for this feature |
| `applied_at` | timestamp with time zone | not null | when it ran (`Clock` bean) |

Created by `ddl-auto: update`. Rows are only ever inserted, by the runner that applied the change, in the same
transaction as the change itself. Repository: `DataUpgradeRepository` (`existsById`, `save`).

## Existing entities: invariant after the revision

| Entity (table) | Invariant | Enforced by |
|---|---|---|
| `TagEntity` (`tag`) | every `uid` is in the list | scan (R4) and bucket registration (R6) refuse off-list UIDs; purge (R7) removed the older ones |
| `RecordEntity` (`record`) | every record's tag is in the list | follows from `tag`; records of off-list tags purged |
| `RecordConformityChangeEntity` (`record_conformity_change`) | belongs to a record of an in-list tag | purged with their records |
| `RegistrationReadEntity` (`registration_read`) | every `uid` is in the list | reads filtered on the way in (R5); older ones purged |
| `BucketEntity` (`bucket`) | unchanged | a bucket that lost its off-list tags is kept, possibly with no tag |

No column is added to or removed from these tables; no derived "off-list" property is exposed any more (R9).

**Exception to the invariant (accepted)**: a tag already stored and later removed from the list by a new version stays,
with its records (clarification révision Q4). Only its new scans and registrations are refused.

## Entry rules

| Entry point | Off-list UID | Rest of the request |
|---|---|---|
| `POST /tags/scan`, `PRODUCTION` reader | `200`, `isCompliant: true`, message "Tag not in reference list, ignored"; nothing written | n/a (one UID) |
| `POST /tags/scan`, `ENREGISTREMENT` reader | `200`, `isCompliant: true`, message "Registration read ignored: tag not in reference list"; session untouched | n/a |
| `POST /tags/registration-reads` | not added to the session; counted in `receivedCount`, never in `addedCount` | in-list UIDs added as today |
| `POST /tags/buckets/{n}`, `POST /tags/registration-sessions/{id}/save` | `400` naming the off-list UIDs; nothing written; session stays open | refused with them |

Order of checks for a bucket registration: input checks (non-blank, ≤ 100) → off-list (`400`) → in another bucket
without `moveConfirmed` (`409`) → write.

## One-off purge (R7, R8)

Runs at startup, in one transaction, only when `data_upgrade` has no row `010-off-list-tag-purge`:

1. `offListTagIds` = ids of tags whose `uid` is off-list.
2. Delete `record_conformity_change` whose record's tag is in `offListTagIds`.
3. Delete `record` whose tag is in `offListTagIds`.
4. Delete `tag` in `offListTagIds` (their bucket link goes with them).
5. Delete `registration_read` whose `uid` is off-list.
6. Insert `data_upgrade('010-off-list-tag-purge', now)`; log the four counts.

Steps 2–4 in chunks of 1,000 ids. Any failure rolls everything back and stops startup.

## Configuration

| Property | Default | Profile override |
|---|---|---|
| `app.tags.reference-list` | `classpath:tags/rfid_tag_list.csv` (`application.yml`) | none: tests use the shipped list (R10) |

No new property: the purge is governed by its marker, not by configuration (R8).
