package com.rfidback.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.RecordConformityChangeEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.RegistrationReadEntity;
import com.rfidback.entity.RegistrationSessionEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.DataUpgradeRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RegistrationReadRepository;
import com.rfidback.repository.RegistrationSessionRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;
import com.rfidback.support.ReferenceTagUids;

/**
 * The one-off purge of off-list data (spec 010 révision, FR-007). It deletes every off-list tag of the database, so it
 * runs on its own in-memory database: the shared one of the other test classes must not lose rows (research R10).
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:off-list-purge;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
class OffListTagPurgeTest {

    @Autowired
    private OffListTagPurge offListTagPurge;

    @Autowired
    private DataUpgradeRepository dataUpgradeRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private BucketRepository bucketRepository;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private RecordConformityChangeRepository recordConformityChangeRepository;

    @Autowired
    private RegistrationSessionRepository sessionRepository;

    @Autowired
    private RegistrationReadRepository readRepository;

    @Test
    void run_deletesOffListDataOnly_thenNeverAgain() {
        dataUpgradeRepository.deleteById(OffListTagPurge.MARKER);
        UserEntity operator = userRepository.save(UserEntity.builder().username("purge-" + UUID.randomUUID())
                .passwordHash("hash").role(Role.OPERATEUR).enabled(true).build());
        ReaderEntity line = readerRepository.save(ReaderEntity.builder().name("Purge line " + UUID.randomUUID())
                .build());
        BucketEntity bucket = bucketRepository.save(BucketEntity.builder().number(424_242).build());
        TagEntity inList = tagRepository.save(TagEntity.builder().uid(ReferenceTagUids.nextInList()).bucket(bucket)
                .build());
        TagEntity offList = tagRepository.save(TagEntity.builder().uid(ReferenceTagUids.offList()).bucket(bucket)
                .build());
        RecordEntity inListRecord = record(inList, line);
        RecordEntity offListRecord = record(offList, line);
        RecordConformityChangeEntity inListChange = change(inListRecord, operator);
        RecordConformityChangeEntity offListChange = change(offListRecord, operator);
        ReaderEntity station = readerRepository.save(ReaderEntity.builder().name("Purge station " + UUID.randomUUID())
                .mode(ReaderMode.ENREGISTREMENT).build());
        RegistrationSessionEntity session = sessionRepository.save(RegistrationSessionEntity.builder().reader(station)
                .startedBy(operator).startedAt(OffsetDateTime.now()).lastActivityAt(OffsetDateTime.now()).build());
        RegistrationReadEntity inListRead = read(session, ReferenceTagUids.nextInList());
        RegistrationReadEntity offListRead = read(session, ReferenceTagUids.offList());

        offListTagPurge.run(null);

        assertThat(tagRepository.findById(offList.getId())).isEmpty();
        assertThat(recordRepository.findById(offListRecord.getId())).isEmpty();
        assertThat(recordConformityChangeRepository.findById(offListChange.getId())).isEmpty();
        assertThat(readRepository.findById(offListRead.getId())).isEmpty();

        assertThat(tagRepository.findById(inList.getId())).isPresent();
        assertThat(tagRepository.findAllByBucket(bucket)).extracting(TagEntity::getId).containsExactly(inList.getId());
        assertThat(recordRepository.findById(inListRecord.getId())).isPresent();
        assertThat(recordConformityChangeRepository.findById(inListChange.getId())).isPresent();
        assertThat(readRepository.findById(inListRead.getId())).isPresent();
        assertThat(bucketRepository.findById(bucket.getId())).isPresent();
        assertThat(readerRepository.findById(line.getId())).isPresent();
        assertThat(sessionRepository.findById(session.getId())).isPresent();
        assertThat(dataUpgradeRepository.existsById(OffListTagPurge.MARKER)).isTrue();

        // Once only (clarification révision Q4): an off-list tag found later is left alone.
        TagEntity later = tagRepository.save(TagEntity.builder().uid(ReferenceTagUids.offList()).build());
        RecordEntity laterRecord = record(later, line);

        offListTagPurge.run(null);

        assertThat(tagRepository.findById(later.getId())).isPresent();
        assertThat(recordRepository.findById(laterRecord.getId())).isPresent();
    }

    @Test
    void startup_onANewDatabase_wroteTheMarker() {
        assertThat(dataUpgradeRepository.findById(OffListTagPurge.MARKER))
                .hasValueSatisfying(upgrade -> assertThat(upgrade.getAppliedAt()).isNotNull());
    }

    private RecordEntity record(TagEntity tag, ReaderEntity reader) {
        return recordRepository.save(RecordEntity.builder().tag(tag).reader(reader).compliant(true).build());
    }

    private RecordConformityChangeEntity change(RecordEntity record, UserEntity author) {
        return recordConformityChangeRepository.save(RecordConformityChangeEntity.builder().record(record)
                .previousCompliant(true).newCompliant(false).author(author).build());
    }

    private RegistrationReadEntity read(RegistrationSessionEntity session, String uid) {
        return readRepository.save(RegistrationReadEntity.builder().session(session).uid(uid)
                .firstReadAt(OffsetDateTime.now()).build());
    }
}
