package com.rfidback.repository;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RegistrationSessionEntity;

public interface RegistrationSessionRepository extends JpaRepository<RegistrationSessionEntity, UUID> {

    // Loads the owner too: start and recordRead run outside a transaction and read it.
    @EntityGraph(attributePaths = { "reader", "startedBy" })
    Optional<RegistrationSessionEntity> findByReader(ReaderEntity reader);

    // An update query rather than a save: it must not depend on entities loaded before a failed insert.
    @Transactional
    @Modifying
    @Query("update RegistrationSessionEntity s set s.lastActivityAt = :now where s.id = :id")
    int touch(@Param("id") UUID id, @Param("now") OffsetDateTime now);
}
