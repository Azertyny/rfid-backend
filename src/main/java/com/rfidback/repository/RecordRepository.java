package com.rfidback.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.RecordEntity;

public interface RecordRepository extends JpaRepository<RecordEntity, UUID> {

    java.util.List<RecordEntity> findTop10ByReader_IdOrderByCreationDateDesc(java.util.UUID readerId);
}
