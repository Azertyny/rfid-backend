package com.rfidback.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.BucketEntity;

public interface BucketRepository extends JpaRepository<BucketEntity, UUID> {

    Optional<BucketEntity> findByNumber(Integer number);
}
