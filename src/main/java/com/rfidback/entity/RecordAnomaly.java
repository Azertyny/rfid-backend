package com.rfidback.entity;

/**
 * The anomaly the system found on a record (spec 015, FR-001), stored in {@code record.anomaly}; null for an ordinary
 * record. A record with an anomaly is always non-compliant. Only the scan sets one, only an Administrateur lifts it.
 * <p>
 * Schema: on H2 Hibernate creates the columns {@code record.anomaly} and
 * {@code record_conformity_change.previous_anomaly} with the native type {@code enum ('CHEAT','DOUBLON_PERDU')}
 * (checked 2026-11-15); on PostgreSQL Hibernate 6 is expected to create {@code varchar(20)} with a
 * {@code check (... in (...))} constraint, as for {@code reader.mode} (not checked: no PostgreSQL at hand). Either way
 * {@code ddl-auto: update} never widens the list, so a third value needs a startup upgrade like
 * {@code configuration/ConformityAuthorSchemaUpgrade}.
 */
public enum RecordAnomaly {
    /** A tag already counted, not registered into a bucket since, read again (FR-005). */
    DOUBLON_PERDU,
    /** Two tags of one bucket read together by one reader: the crate's record (FR-006, FR-007). */
    CHEAT
}
