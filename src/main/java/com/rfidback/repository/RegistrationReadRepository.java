package com.rfidback.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.RegistrationReadEntity;
import com.rfidback.entity.RegistrationSessionEntity;

public interface RegistrationReadRepository extends JpaRepository<RegistrationReadEntity, UUID> {

    boolean existsBySessionAndUid(RegistrationSessionEntity session, String uid);

    List<RegistrationReadEntity> findAllBySessionOrderByFirstReadAtAsc(RegistrationSessionEntity session);

    @Query("select r.uid from RegistrationReadEntity r where r.session = :session and r.uid in :uids")
    List<String> findUidsBySessionAndUidIn(@Param("session") RegistrationSessionEntity session,
            @Param("uids") Collection<String> uids);

    @Transactional
    void deleteBySession(RegistrationSessionEntity session);

    @Query("select distinct r.uid from RegistrationReadEntity r")
    List<String> findDistinctUids();

    // Bulk delete for the one-off purge of off-list tags (spec 010 révision, OffListTagPurge).
    @Modifying
    @Query("delete from RegistrationReadEntity r where r.uid in :uids")
    int deleteByUidIn(@Param("uids") Collection<String> uids);
}
