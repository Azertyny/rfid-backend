package com.rfidback.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.PickerEntity;

public interface PickerRepository extends JpaRepository<PickerEntity, UUID> {

    boolean existsByLastnameIgnoreCaseAndFirstnameIgnoreCase(String lastname, String firstname);

    boolean existsByLastnameIgnoreCaseAndFirstnameIgnoreCaseAndIdNot(String lastname, String firstname, UUID id);
}
