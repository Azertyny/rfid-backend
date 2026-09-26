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
// The first index serves the duplicate-read lookup of a scan (spec 004, FR-008);
// the second serves the last 10 records of a reader (spec 005, SC-003) and the dashboard queries of one reader;
// the third serves the dashboard period queries without a reader (spec 007, research R8);
// the fourth serves the check before deleting an activity (spec 012, research R7).
@Table(name = "record", indexes = {
        @Index(name = "idx_record_reader_tag_date", columnList = "reader_id, tag_id, creation_date"),
        @Index(name = "idx_record_reader_date", columnList = "reader_id, creation_date"),
        @Index(name = "idx_record_creation_date", columnList = "creation_date"),
        @Index(name = "idx_record_activity", columnList = "activity_id") })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecordEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "picker_id")
    private PickerEntity picker;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tag_id", nullable = false)
    private TagEntity tag;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reader_id", nullable = false)
    private ReaderEntity reader;

    // The line's activity at scan time; never changed afterwards (spec 012, FR-014/FR-015).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activity_id")
    private ActivityEntity activity;

    @Column(name = "conformity", nullable = false)
    private boolean compliant;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private OffsetDateTime creationDate;

    @Column
    private String comment;
}
