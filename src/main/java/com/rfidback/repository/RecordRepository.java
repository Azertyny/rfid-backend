package com.rfidback.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;

import jakarta.persistence.LockModeType;

public interface RecordRepository extends JpaRepository<RecordEntity, UUID> {

    /** The 10 newest records of a reader, with their tag loaded in the same query (spec 005, SC-003). */
    @EntityGraph(attributePaths = "tag")
    List<RecordEntity> findTop10ByReader_NameOrderByCreationDateDesc(String readerUid);

    /**
     * The latest record of this tag by this reader created after {@code cutoff} (spec 004, FR-008). Row-locked, so an
     * Opérateur's change and a duplicate scan of the same record never interleave (spec 005, FR-006).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RecordEntity> findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(ReaderEntity reader,
            TagEntity tag, OffsetDateTime cutoff);

    /**
     * The record, row-locked until the transaction ends, so concurrent compliance changes are applied one after the
     * other (spec 005).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecordEntity r where r.id = :id")
    Optional<RecordEntity> findWithLockById(@Param("id") UUID id);

    // Dashboard statistics (spec 007). Each query exists without and with a reader: an optional reader written as
    // "(:reader is null or ...)" can fail on PostgreSQL with a null parameter (spec 007, T008).

    String IN_RANGE = " r.creationDate >= :start and r.creationDate < :end ";
    String COUNTS = " count(r) as total, sum(case when r.compliant = false then 1 else 0 end) as nonCompliant ";
    String BY_PICKER = "select p.id as pickerId, p.firstname as firstname, p.lastname as lastname," + COUNTS
            + "from RecordEntity r left join r.picker p where" + IN_RANGE;
    String PICKER_GROUP = " group by p.id, p.firstname, p.lastname";
    // Grouped by UTC hour since the epoch, which does not depend on any session or JVM time zone; the service maps
    // each one to its hour in the station zone (spec 007, research R4).
    String UTC_HOUR = "floor(extract(epoch from r.creationDate) / 3600)";
    String BY_UTC_HOUR = "select " + UTC_HOUR + " as hourIndex," + COUNTS + "from RecordEntity r where" + IN_RANGE;

    /** Record and non-compliant counts created in {@code [start, end)} (spec 007, FR-002). */
    @Query("select" + COUNTS + "from RecordEntity r where" + IN_RANGE)
    CountsView countSummary(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Query("select" + COUNTS + "from RecordEntity r where" + IN_RANGE + "and r.reader = :reader")
    CountsView countSummaryForReader(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end,
            @Param("reader") ReaderEntity reader);

    /** Counts per picker recorded at scan time; records without a picker form one row with null ids (FR-009). */
    @Query(BY_PICKER + PICKER_GROUP)
    List<PickerCountsView> countByPicker(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Query(BY_PICKER + "and r.reader = :reader" + PICKER_GROUP)
    List<PickerCountsView> countByPickerForReader(@Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end, @Param("reader") ReaderEntity reader);

    /** Counts per UTC hour, as the number of hours since 1970-01-01T00:00Z (spec 007, research R4). */
    @Query(BY_UTC_HOUR + "group by " + UTC_HOUR)
    List<HourCountsView> countByUtcHour(@Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);

    @Query(BY_UTC_HOUR + "and r.reader = :reader group by " + UTC_HOUR)
    List<HourCountsView> countByUtcHourForReader(@Param("start") OffsetDateTime start,
            @Param("end") OffsetDateTime end, @Param("reader") ReaderEntity reader);

    // Bulk delete for the one-off purge of off-list tags (spec 010 révision, OffListTagPurge).
    @Modifying
    @Query("delete from RecordEntity r where r.tag.id in :tagIds")
    int deleteByTagIdIn(@Param("tagIds") Collection<UUID> tagIds);

    // Numbers, not Long: count, sum and floor come back as Long, Integer, Double or BigDecimal depending on the
    // database. A sum over no row is null.
    interface CountsView {
        Number getTotal();

        Number getNonCompliant();
    }

    interface PickerCountsView extends CountsView {
        UUID getPickerId();

        String getFirstname();

        String getLastname();
    }

    interface HourCountsView extends CountsView {
        Number getHourIndex();
    }
}
