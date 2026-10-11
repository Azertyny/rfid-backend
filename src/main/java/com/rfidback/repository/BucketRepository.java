package com.rfidback.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface BucketRepository extends JpaRepository<BucketEntity, UUID> {

    Optional<BucketEntity> findByNumber(Integer number);

    /**
     * The bucket, row-locked until the transaction ends: orders a delete against a concurrent scan, registration or
     * assignment, so the second one sees what the first committed (spec 006, research R12).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select b from BucketEntity b where b.id = :id")
    Optional<BucketEntity> findWithLockById(@Param("id") UUID id);

    /** {@link #findWithLockById} by number, for a tag registration (spec 006, research R12). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select b from BucketEntity b where b.number = :number")
    Optional<BucketEntity> findWithLockByNumber(@Param("number") Integer number);

    List<BucketEntity> findAllByPickerOrderByNumberAsc(PickerEntity picker);

    List<BucketEntity> findAllByPickerIn(List<PickerEntity> pickers);

    boolean existsByPicker(PickerEntity picker);
}
