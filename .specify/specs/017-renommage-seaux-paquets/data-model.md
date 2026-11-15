# Data Model: Renommer les « seaux » en « paquets »

No entity, table, column, constraint or API schema changes (FR-006, FR-007, research R1). No data upgrade runs at
startup.

| Domain term (displayed) | Before | After       | Entity / table       | API schema / path                    |
|-------------------------|--------|-------------|----------------------|--------------------------------------|
| Container of tags       | seau   | **paquet**  | `Bucket` / `bucket`  | `Bucket…`, `/api/buckets`, unchanged |
| Counted unit of a scan  | caisse | caisse      | `Record` / `record`  | unchanged                            |

Only the French descriptions of four `api.yaml` fields change (research R6): text in generated Javadoc, no type,
name or validation change.
