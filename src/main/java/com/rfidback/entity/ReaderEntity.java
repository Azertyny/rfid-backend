package com.rfidback.entity;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "reader")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReaderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false, unique = true, length = 64)
    private String apitoken;

    // The default lets ddl-auto add this NOT NULL column over readers that already exist.
    @Builder.Default
    @ColumnDefault("true")
    @Column(nullable = false)
    private boolean active = true;

    // The inner quotes make the default a SQL string literal, so ddl-auto can add the column over existing readers.
    @Builder.Default
    @ColumnDefault("'PRODUCTION'")
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReaderMode mode = ReaderMode.PRODUCTION;

    // The line's current activity and when it was chosen. A choice made before today, station time, counts as none
    // (spec 012, research R1/R3): read it through LineActivityService.effectiveActivity.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "current_activity_id")
    private ActivityEntity currentActivity;

    @Column(name = "current_activity_set_at")
    private OffsetDateTime currentActivitySetAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime creationDate;

    @UpdateTimestamp
    @Column(nullable = false)
    private OffsetDateTime updateDate;

    @PrePersist
    public void prePersist() {
        if (apitoken == null || apitoken.isEmpty()) {
            apitoken = newApitoken();
        }
    }

    public static String newApitoken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
