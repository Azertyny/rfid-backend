package com.rfidback.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A tag-registration session: the reads of one ENREGISTREMENT reader since an Administrateur clicked Start. */
@Entity
@Table(name = "registration_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationSessionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // Unique: at most one session per reader, also when two Administrateurs click Start at once.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reader_id", nullable = false, unique = true)
    private ReaderEntity reader;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "started_by_id", nullable = false)
    private UserEntity startedBy;

    // Set by the service from its Clock rather than by Hibernate, so expiry can be tested.
    @Column(nullable = false)
    private OffsetDateTime startedAt;

    @Column(nullable = false)
    private OffsetDateTime lastActivityAt;

    // No reads collection on purpose: reads are queried and deleted through RegistrationReadRepository, so a
    // collection cached earlier in the same persistence context can never hide new reads or block a delete.
}
