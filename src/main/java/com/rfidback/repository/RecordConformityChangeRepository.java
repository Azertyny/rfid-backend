package com.rfidback.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.RecordConformityChangeEntity;
import com.rfidback.entity.RecordEntity;

public interface RecordConformityChangeRepository extends JpaRepository<RecordConformityChangeEntity, UUID> {

    /** Whether an Opérateur already changed this record: duplicate scans then leave it alone (spec 005, FR-006). */
    boolean existsByRecord(RecordEntity record);

    /** Compliance history of a record, oldest first (spec 005, FR-007). */
    @EntityGraph(attributePaths = "author")
    List<RecordConformityChangeEntity> findAllByRecordOrderByChangedAtAsc(RecordEntity record);
}
