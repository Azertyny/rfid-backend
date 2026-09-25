package com.rfidback.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
}
