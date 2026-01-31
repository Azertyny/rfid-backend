package com.rfidback.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.ReaderEntity;

public interface ReaderRepository extends JpaRepository<ReaderEntity, UUID> {

    Optional<ReaderEntity> findByApitoken(String apitoken);
}
