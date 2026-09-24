package com.rfidback.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {

    Optional<UserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    long countByRoleAndEnabledTrue(Role role);

    List<UserEntity> findAllByOrderByUsernameAsc();
}
