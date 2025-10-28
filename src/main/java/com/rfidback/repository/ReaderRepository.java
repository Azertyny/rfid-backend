package com.rfidback.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.ReaderEntity;

public interface ReaderRepository extends JpaRepository<ReaderEntity, Integer> {

}
