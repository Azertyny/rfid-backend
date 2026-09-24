package com.rfidback.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;

public interface RecordRepository extends JpaRepository<RecordEntity, UUID> {

    List<RecordEntity> findTop10ByReader_NameOrderByCreationDateDesc(String readerUid);

    /** The latest record of this tag by this reader created after {@code cutoff} (spec 004, FR-008). */
    Optional<RecordEntity> findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(ReaderEntity reader,
            TagEntity tag, OffsetDateTime cutoff);
}
