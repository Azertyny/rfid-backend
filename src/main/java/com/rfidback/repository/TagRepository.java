package com.rfidback.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.TagEntity;

public interface TagRepository extends JpaRepository<TagEntity, UUID> {

    Optional<TagEntity> findByUid(String uid);

    List<TagEntity> findAllByBucket(BucketEntity bucket);

    List<TagEntity> findAllByBucketIn(List<BucketEntity> buckets);

    List<TagEntity> findAllByUidIn(Collection<String> uids);

    long countByBucket(BucketEntity bucket);

    /** Every known tag with its bucket in one query, for the off-list tags page (spec 010, FR-009). */
    @Query("select t from TagEntity t left join fetch t.bucket")
    List<TagEntity> findAllWithBucket();
}
