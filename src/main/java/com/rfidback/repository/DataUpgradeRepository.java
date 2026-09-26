package com.rfidback.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.DataUpgradeEntity;

public interface DataUpgradeRepository extends JpaRepository<DataUpgradeEntity, String> {
}
