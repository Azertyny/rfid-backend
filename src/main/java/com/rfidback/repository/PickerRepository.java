package com.rfidback.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.rfidback.entity.PickerEntity;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface PickerRepository extends JpaRepository<PickerEntity, UUID> {

    // Deleted pickers count on purpose: their name stays taken (spec 001, FR-002, FR-010).
    boolean existsByLastnameIgnoreCaseAndFirstnameIgnoreCase(String lastname, String firstname);

    boolean existsByLastnameIgnoreCaseAndFirstnameIgnoreCaseAndIdNot(String lastname, String firstname, UUID id);

    /** The pickers not deleted, a page at a time (spec 001, FR-010). */
    Page<PickerEntity> findAllByDeletedAtIsNull(Pageable pageable);

    /** Every picker not deleted, for the day's hour entry (spec 001, FR-010). */
    List<PickerEntity> findAllByDeletedAtIsNull();

    /**
     * The picker, row-locked until the transaction ends: orders a delete against a concurrent bucket assignment, so
     * the second one sees what the first committed (spec 001, research R12).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select p from PickerEntity p where p.id = :id")
    Optional<PickerEntity> findWithLockById(@Param("id") UUID id);
}
