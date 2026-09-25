package com.rfidback.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
// One row per manual compliance change, never updated or deleted (spec 005, FR-003).
// The index serves the history read and the duplicate-scan check (spec 005, FR-006/FR-007).
@Table(name = "record_conformity_change", indexes = @Index(name = "idx_record_conformity_change_record_date",
        columnList = "record_id, changed_at"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordConformityChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "record_id", nullable = false)
    private RecordEntity record;

    @Column(name = "previous_conformity", nullable = false)
    private boolean previousCompliant;

    @Column(name = "new_conformity", nullable = false)
    private boolean newCompliant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "author_id", nullable = false)
    private UserEntity author;

    @CreationTimestamp
    @Column(name = "changed_at", nullable = false, updatable = false)
    private OffsetDateTime changedAt;
}
