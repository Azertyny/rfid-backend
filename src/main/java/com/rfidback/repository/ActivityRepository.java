package com.rfidback.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rfidback.entity.ActivityEntity;

public interface ActivityRepository extends JpaRepository<ActivityEntity, UUID> {

    boolean existsByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);

    /** All activities with their lines in one query, for the Administrateur's list (spec 012, FR-004). */
    @EntityGraph(attributePaths = "lines")
    List<ActivityEntity> findAllByOrderByNameAsc();

    /** The activities associated with a line, active or not. */
    @Query("select a from ActivityEntity a join a.lines r where r.id = :readerId order by a.name")
    List<ActivityEntity> findAllByLine(@Param("readerId") UUID readerId);
}
