package com.rfidback.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A scan delay changed by an Administrateur (spec 015, FR-008, FR-014). No row means the installation default
 * ({@code app.scan.reread-window}, {@code app.scan.crate-window}); a row is written only when a value changes.
 */
@Entity
@Table(name = "scan_setting")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanSettingEntity {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private ScanSettingName name;

    @Column(nullable = false)
    private int seconds;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by", nullable = false)
    private UserEntity updatedBy;
}
