package com.rfidback.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.TagEntity;

public interface TagRepository extends JpaRepository<TagEntity, Long> {

}
