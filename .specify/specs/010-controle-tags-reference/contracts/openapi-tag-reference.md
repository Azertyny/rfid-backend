# Contract changes: Contrôle des tags par rapport à la liste de référence (révision)

Changes to `src/main/resources/openapi/api.yaml`, made before any code (API-first), from the version delivered on
2026-09-26. Decisions: [research](../research.md) R4–R6, R9.

## `POST /tags/scan` — schemas unchanged, one behaviour added

`ScanTagRequest` / `ScanTagResponse` are unchanged: readers change nothing (FR-005, FR-006). Add to the description:

> The uid is checked against the reference tag list shipped with the application (its last 12 characters). A uid not
> in the list creates nothing, whatever the reader's mode: the answer is 200 with isCompliant true and the message
> "Tag not in reference list, ignored" (PRODUCTION) or "Registration read ignored: tag not in reference list"
> (ENREGISTREMENT).

## `POST /tags/registration-reads` — schemas unchanged

Description, after "all of them or none are kept": "Uids not in the reference list are never kept; they still count
in receivedCount." `RegistrationReadsResponse.addedCount` description: "Uids newly added to the session (already
present ones and uids not in the reference list are not counted). 0 without a session."

## `POST /tags/buckets/{bucketNumber}` and `POST /tags/registration-sessions/{sessionId}/save`

- Remove "Tags not in the reference list are registered only when offListConfirmed is true" from both descriptions;
  add "A uid not in the reference list refuses the whole request with 400, naming those uids; nothing is saved (on
  save, the session stays open)."
- `409` description goes back to: "Some tags are linked to another bucket; nothing is saved. Resend with moveConfirmed
  true to move them."
- `400` keeps `$ref: "#/components/responses/InvalidRequest"` (no schema declared; the body is the `ProblemDetail`
  that `ApiExceptionHandler` renders for every `ResponseStatusException`); its `detail` reads
  `Tags not in the reference list: <uid>, <uid>`.

## Removed

| Item | Kind |
|---|---|
| `GET /tags/off-list` (`listOffListTags`) | route |
| `OffListTag`, `OffListTagsList` | schemas |
| `RegisterTagsRequest.offListConfirmed` | property |
| `SaveRegistrationSession.offListConfirmed` | property |
| `TagsInOtherBuckets.offListTags` (and from `required`) | property; `tags` becomes required non-empty again, description back to "409 body: tags linked to another bucket" |
| `RegistrationRead.offList` (and from `required`) | property |
| `RecordSummary.tagOffList` (and from `required`) | property |

A client still sending `offListConfirmed` is not refused (unknown JSON properties are ignored).

## Response table for a bucket registration

| Off-list UIDs | In another bucket, `moveConfirmed` false | Answer |
|---|---|---|
| none | none | `200`, as before spec 010 |
| none | some | `409`, `tags` filled |
| some | any | `400`, `detail` names the off-list UIDs; no flag changes it |

## Security

No change to `SecurityConfig`. The removed route was covered by `/api/tags/**` → `ADMINISTRATEUR`; the other routes keep
their chains (reader token for `/tags/scan` and `/tags/registration-reads`, session for the rest).
