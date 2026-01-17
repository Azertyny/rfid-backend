package com.rfidback.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.TagEntity;

public interface TagRepository extends JpaRepository<TagEntity, UUID> {

    Optional<TagEntity> findByUid(String uid);

    List<TagEntity> findAllByBucket(BucketEntity bucket);
}
