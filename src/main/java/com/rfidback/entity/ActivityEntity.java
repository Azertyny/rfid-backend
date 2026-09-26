package com.rfidback.entity;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A product type a production line can run (spec 012). The Administrateur associates it with lines; the Opérateur
 * picks one of them as the line's current activity, which each record then carries.
 */
@Entity
@Table(name = "activity")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ActivityEntity {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    // Uniqueness ignoring case and surrounding spaces (FR-001), portable to H2 and PostgreSQL (research R4).
    @Column(name = "name_key", nullable = false, unique = true, length = NAME_MAX_LENGTH)
    private String nameKey;

    @Builder.Default
    @ColumnDefault("true")
    @Column(nullable = false)
    private boolean active = true;

    // The readers (lines in PRODUCTION mode) that can run this activity (research R5).
    @Builder.Default
    @ManyToMany
    @JoinTable(name = "reader_activity", joinColumns = @JoinColumn(name = "activity_id"),
            inverseJoinColumns = @JoinColumn(name = "reader_id"))
    private Set<ReaderEntity> lines = new HashSet<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime creationDate;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updateDate;

    @PrePersist
    @PreUpdate
    void computeNameKey() {
        nameKey = nameKey(name);
    }

    public static String nameKey(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
