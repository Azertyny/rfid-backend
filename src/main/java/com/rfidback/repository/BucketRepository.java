package com.rfidback.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;

public interface BucketRepository extends JpaRepository<BucketEntity, UUID> {

    Optional<BucketEntity> findByNumber(Integer number);

    Optional<BucketEntity> findByPicker(PickerEntity picker);

    List<BucketEntity> findAllByPickerIn(List<PickerEntity> pickers);
}
