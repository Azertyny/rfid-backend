package com.rfidback.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.RegistrationReadEntity;
import com.rfidback.entity.RegistrationSessionEntity;

public interface RegistrationReadRepository extends JpaRepository<RegistrationReadEntity, UUID> {

    boolean existsBySessionAndUid(RegistrationSessionEntity session, String uid);

    List<RegistrationReadEntity> findAllBySessionOrderByFirstReadAtAsc(RegistrationSessionEntity session);

    @Transactional
    void deleteBySession(RegistrationSessionEntity session);
}
