package com.rfidback.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.TagEntity;

public interface TagRepository extends JpaRepository<TagEntity, UUID> {

    Optional<TagEntity> findByUid(String uid);

    List<TagEntity> findAllByBucket(BucketEntity bucket);

    List<TagEntity> findAllByBucketIn(List<BucketEntity> buckets);

    List<TagEntity> findAllByUidIn(Collection<String> uids);

    long countByBucket(BucketEntity bucket);

    /** Unlinks the tags counted by records of these tags, before those records are deleted (spec 004, research R11). */
    @Modifying
    @Query("update TagEntity t set t.countedRecord = null"
            + " where t.countedRecord.id in (select r.id from RecordEntity r where r.tag.id in :tagIds)")
    int clearCountedRecordOfTags(@Param("tagIds") Collection<UUID> tagIds);

    // Bulk delete for the one-off purge of off-list tags (spec 010 révision, OffListTagPurge).
    @Modifying
    @Query("delete from TagEntity t where t.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
