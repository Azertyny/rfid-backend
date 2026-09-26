package com.rfidback.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.LineActivityChangeEntity;
import com.rfidback.entity.ReaderEntity;

public interface LineActivityChangeRepository extends JpaRepository<LineActivityChangeEntity, UUID> {

    /** Whether the history cites the activity, which then can no longer be deleted (spec 012, research R7). */
    @Query("select count(c) > 0 from LineActivityChangeEntity c "
            + "where c.previousActivity = :activity or c.newActivity = :activity")
    boolean isReferenced(@Param("activity") ActivityEntity activity);

    List<LineActivityChangeEntity> findAllByReaderOrderByChangedAtAsc(ReaderEntity reader);
}
