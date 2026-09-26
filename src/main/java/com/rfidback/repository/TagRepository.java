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

    // Bulk delete for the one-off purge of off-list tags (spec 010 révision, OffListTagPurge).
    @Modifying
    @Query("delete from TagEntity t where t.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
