# Data Model: Contrôle des tags par rapport à la liste de référence

No table, column or index is added. The feature adds one in-memory structure and derived fields in API answers
([research](research.md) R1, R4).

## Reference tag list (new, in memory)

| Aspect | Value |
|---|---|
| Source | `src/main/resources/tags/rfid_tag_list.csv`, set by `app.tags.reference-list` |
| Format | one UID per line, 24 hexadecimal characters, no header; `\n` or `\r\n`; optional UTF-8 BOM |
| Content at delivery | 5,008 unique UIDs (moved from `doc/rfid_tag_list.csv`) |
| Held as | immutable set of upper-cased UIDs, loaded once at startup (`ReferenceTagList`) |
| Changes | only by editing the file and deploying a new version (clarification Q1) |

**Validation at startup** (R2) — the application does not start when:

- the resource does not exist or cannot be read;
- no UID is found;
- a non-blank line, once trimmed, is not 24 characters `[0-9A-Fa-f]`.

Blank lines are skipped, duplicates are ignored.

**Membership** (R3): `contains(uid)` is true when `uid.trim().toUpperCase(Locale.ROOT)` is in the set. A `null` or
blank UID is not in the list.

## Existing entities (unchanged in the database)

| Entity | Derived property | Rule |
|---|---|---|
| `TagEntity` (`tag`) | off-list | `!referenceTagList.contains(uid)` |
| `RegistrationReadEntity` (`registration_read`) | off-list | same, on its `uid` |
| `RecordEntity` (`record`) | tag off-list | same, on `record.tag.uid` |

The derived property is computed each time an answer is built, so it always follows the list shipped with the running
version (spec assumption).

## Registration rule (FR-004, R5)

Inputs: bucket number, UIDs (unique, trimmed, non-blank, at most 100), `moveConfirmed`, `offListConfirmed`.

1. `inOtherBuckets` = known tags among the UIDs linked to a bucket with another number.
2. `offList` = UIDs not in the reference list.
3. If (`inOtherBuckets` non-empty and not `moveConfirmed`) or (`offList` non-empty and not `offListConfirmed`):
   answer `409` with both lists (each list as computed, whatever the flags), write nothing; a registration session
   stays open.
4. Otherwise register as today (spec `003`): off-list tags are saved like any other.

## Off-list tag entry (new API shape, FR-009)

| Field | Source |
|---|---|
| `uid` | `tag.uid` |
| `bucketNumber` | `tag.bucket.number`, or null |
| `recordCount` | number of Records of the tag |
| `lastRecordAt` | latest `record.creation_date` of the tag, or null |
| `createdAt` | `tag.creation_date` |

Sorted by `lastRecordAt` descending (never-read tags last), then `uid`.

## Configuration

| Property | Default | Profile override |
|---|---|---|
| `app.tags.reference-list` | `classpath:tags/rfid_tag_list.csv` (`application.yml`) | none: tests use the shipped list (research R7) |
