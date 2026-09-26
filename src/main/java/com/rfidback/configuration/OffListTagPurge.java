package com.rfidback.configuration;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.DataUpgradeEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.repository.DataUpgradeRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RegistrationReadRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.service.ReferenceTagList;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Tags not in the reference list are no longer accepted (spec 010 révision); this deletes the ones stored before, with
// their records, the conformity history of those records, their bucket link and their registration reads. It runs
// once: the marker row in data_upgrade, written in the same transaction, stops it on every later startup, even with a
// changed list (research R7, R8). A failure rolls everything back and stops startup. The class can go once a
// migration tool (Flyway/Liquibase) manages the data; the marker stays.
@Slf4j
@Component
@RequiredArgsConstructor
public class OffListTagPurge implements ApplicationRunner {

    static final String MARKER = "010-off-list-tag-purge";
    // Keeps each IN list well below the bind-parameter limits of H2 and PostgreSQL.
    private static final int CHUNK = 1000;

    private final DataUpgradeRepository dataUpgradeRepository;
    private final TagRepository tagRepository;
    private final RecordRepository recordRepository;
    private final RecordConformityChangeRepository recordConformityChangeRepository;
    private final RegistrationReadRepository registrationReadRepository;
    private final ReferenceTagList referenceTagList;
    private final Clock clock;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (dataUpgradeRepository.existsById(MARKER)) {
            log.info("Off-list tag purge already applied (spec 010)");
            return;
        }

        List<UUID> tagIds = tagRepository.findAll().stream()
                .filter(tag -> referenceTagList.isOffList(tag.getUid()))
                .map(TagEntity::getId)
                .toList();
        int changes = 0;
        int records = 0;
        int tags = 0;
        for (List<UUID> chunk : chunks(tagIds)) {
            // Foreign-key order: a change points to its record, a record to its tag.
            changes += recordConformityChangeRepository.deleteByRecordTagIdIn(chunk);
            records += recordRepository.deleteByTagIdIn(chunk);
            tags += tagRepository.deleteByIdIn(chunk);
        }

        List<String> offListReadUids = registrationReadRepository.findDistinctUids().stream()
                .filter(referenceTagList::isOffList)
                .toList();
        int reads = 0;
        for (List<String> chunk : chunks(offListReadUids)) {
            reads += registrationReadRepository.deleteByUidIn(chunk);
        }

        dataUpgradeRepository.save(new DataUpgradeEntity(MARKER, OffsetDateTime.now(clock)));
        log.info("Off-list tag purge (spec 010): {} tags, {} records, {} conformity changes, {} registration reads"
                + " deleted", tags, records, changes, reads);
    }

    private static <T> List<List<T>> chunks(List<T> items) {
        return IntStream.range(0, (items.size() + CHUNK - 1) / CHUNK)
                .mapToObj(i -> items.subList(i * CHUNK, Math.min(items.size(), (i + 1) * CHUNK)))
                .toList();
    }
}
