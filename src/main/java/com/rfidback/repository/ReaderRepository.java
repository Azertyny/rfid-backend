package com.rfidback.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.ReaderEntity;

public interface ReaderRepository extends JpaRepository<ReaderEntity, Integer> {

    Optional<ReaderEntity> findByApitoken(String apitoken);
}
