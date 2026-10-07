package com.rfidback.entity;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
// The hours a picker worked on one day of the station time zone, entered by an Administrateur (spec 014). No row
// means "non saisi": 0 h cannot be stored. Whole minutes, so sums over a period are exact (research R1).
@Table(name = "picker_work_day",
        uniqueConstraints = @UniqueConstraint(name = "uk_picker_work_day_picker_date",
                columnNames = { "picker_id", "work_date" }),
        indexes = @Index(name = "idx_picker_work_day_date", columnList = "work_date"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PickerWorkDayEntity {

    public static final int MINUTES_STEP = 15;
    public static final int MAX_MINUTES = 1440;

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "picker_id", nullable = false)
    private PickerEntity picker;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    @Column(nullable = false)
    private int minutes;

    // Set by the service from the Clock bean, like the other dates of a change.
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "updated_by_id", nullable = false)
    private UserEntity updatedBy;

    public static boolean isValidMinutes(long minutes) {
        return minutes >= MINUTES_STEP && minutes <= MAX_MINUTES && minutes % MINUTES_STEP == 0;
    }

    @PrePersist
    @PreUpdate
    void checkMinutes() {
        if (!isValidMinutes(minutes)) {
            throw new IllegalStateException("Work minutes must be a multiple of %d between %d and %d, got %d"
                    .formatted(MINUTES_STEP, MINUTES_STEP, MAX_MINUTES, minutes));
        }
    }
}
