package com.rfidback.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.ReaderEntity;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface ReaderRepository extends JpaRepository<ReaderEntity, UUID> {

    /**
     * The reader with its current activity, row-locked until the transaction ends: choices, dissociations and the
     * midnight reset of one line are applied one after the other (spec 012, research R9).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from ReaderEntity r left join fetch r.currentActivity where r.id = :id")
    Optional<ReaderEntity> findWithLockById(@Param("id") UUID id);

    /** A fresh read of the line's current activity, without lock: the scan must never wait for it (research R2). */
    @EntityGraph(attributePaths = "currentActivity")
    Optional<ReaderEntity> findWithCurrentActivityById(UUID id);

    @EntityGraph(attributePaths = "currentActivity")
    Optional<ReaderEntity> findWithCurrentActivityByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"))
    @Query("select r from ReaderEntity r left join fetch r.currentActivity where r.name = :name")
    Optional<ReaderEntity> findWithLockByName(@Param("name") String name);

    // The two queries below return ids, not readers: the caller then locks each reader, and a reader already loaded
    // in the persistence context would come back from the lock query as it was, not as it is once locked.

    /** Lines whose activity was chosen before {@code instant}, for the midnight reset (research R3). */
    @Query("select r.id from ReaderEntity r where r.currentActivitySetAt < :instant")
    List<UUID> findIdsByCurrentActivitySetAtBefore(@Param("instant") OffsetDateTime instant);

    @Query("select r.id from ReaderEntity r where r.currentActivity = :activity")
    List<UUID> findIdsByCurrentActivity(@Param("activity") ActivityEntity activity);

    Optional<ReaderEntity> findByApitoken(String apitoken);

    Optional<ReaderEntity> findByName(String name);

    boolean existsByNameIgnoreCase(String name);
}
