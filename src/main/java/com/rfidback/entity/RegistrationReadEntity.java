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
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A tag seen by a reader in ENREGISTREMENT mode during a session. Never turns into a Record. */
@Entity
@Table(name = "registration_read", uniqueConstraints = @UniqueConstraint(columnNames = { "session_id", "uid" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RegistrationReadEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private RegistrationSessionEntity session;

    @Column(nullable = false, length = 50)
    private String uid;

    @Column(nullable = false)
    private OffsetDateTime firstReadAt;
}
