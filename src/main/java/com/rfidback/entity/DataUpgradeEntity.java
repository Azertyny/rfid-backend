package com.rfidback.entity;

import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A one-off data change already applied, so that its startup runner never applies it again (spec 010 révision,
 * research R8). Only ever inserted, in the same transaction as the change it records.
 */
@Entity
@Table(name = "data_upgrade")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataUpgradeEntity {

    @Id
    @Column(length = 100)
    private String name;

    @Column(nullable = false)
    private OffsetDateTime appliedAt;
}
