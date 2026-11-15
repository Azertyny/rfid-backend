package com.rfidback.repository;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.ScanSettingEntity;
import com.rfidback.entity.ScanSettingName;

public interface ScanSettingRepository extends JpaRepository<ScanSettingEntity, ScanSettingName> {

    /** The settings changed by an Administrateur, with their author, for the settings page (spec 015, FR-014). */
    @EntityGraph(attributePaths = "updatedBy")
    List<ScanSettingEntity> findAllWithAuthorBy();
}
