package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordConformityChangeEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.RecordNotFoundException;
import com.rfidback.generated.model.ConformityChange;
import com.rfidback.generated.model.ConformityChangesList;
import com.rfidback.generated.model.RecordSummary;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.UserRepository;
import com.rfidback.security.ReaderAuthentication;

class RecordServiceTest {

    private RecordRepository recordRepository;
    private ReaderRepository readerRepository;
    private RecordConformityChangeRepository recordConformityChangeRepository;
    private UserRepository userRepository;
    private ReferenceTagList referenceTagList;
    private RecordService recordService;
    private UserEntity operator;

    @BeforeEach
    void setUp() {
        recordRepository = Mockito.mock(RecordRepository.class);
        recordConformityChangeRepository = Mockito.mock(RecordConformityChangeRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        readerRepository = Mockito.mock(ReaderRepository.class);
        referenceTagList = Mockito.mock(ReferenceTagList.class);
        recordService = new RecordService(recordRepository, readerRepository,
                recordConformityChangeRepository, userRepository, referenceTagList);

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("op1", null, "ROLE_OPERATEUR"));
        operator = UserEntity.builder().id(UUID.randomUUID()).username("op1").role(Role.OPERATEUR).enabled(true)
                .build();
        when(userRepository.findByUsername("op1")).thenReturn(Optional.of(operator));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- updateRecordConformity (spec 005, FR-003) ---

    @Test
    void updateConformity_differentValue_updatesRecordAndWritesOneChange() {
        RecordEntity record = lockedRecord(true);

        recordService.updateRecordConformity(record.getId(), false);

        assertThat(record.isCompliant()).isFalse();
        ArgumentCaptor<RecordConformityChangeEntity> change = ArgumentCaptor.forClass(RecordConformityChangeEntity.class);
        verify(recordConformityChangeRepository).save(change.capture());
        assertThat(change.getValue().getRecord()).isSameAs(record);
        assertThat(change.getValue().isPreviousCompliant()).isTrue();
        assertThat(change.getValue().isNewCompliant()).isFalse();
        assertThat(change.getValue().getAuthor()).isSameAs(operator);
    }

    @Test
    void updateConformity_sameValue_writesNothing() {
        RecordEntity record = lockedRecord(false);

        recordService.updateRecordConformity(record.getId(), false);

        assertThat(record.isCompliant()).isFalse();
        verify(recordConformityChangeRepository, never()).save(any());
        verify(recordRepository, never()).save(any());
        verify(userRepository, never()).findByUsername(any());
    }

    @Test
    void updateConformity_unknownRecord_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(recordRepository.findWithLockById(id)).thenReturn(Optional.empty());

        assertThrows(RecordNotFoundException.class, () -> recordService.updateRecordConformity(id, false));
        verify(recordConformityChangeRepository, never()).save(any());
    }

    // --- listConformityChanges (spec 005, FR-007) ---

    @Test
    void listConformityChanges_mapsChangesInRepositoryOrder() {
        RecordEntity record = RecordEntity.builder().id(UUID.randomUUID()).compliant(true).build();
        when(recordRepository.findById(record.getId())).thenReturn(Optional.of(record));
        UserEntity admin = UserEntity.builder().username("admin1").role(Role.ADMINISTRATEUR).build();
        OffsetDateTime first = OffsetDateTime.parse("2024-01-15T09:31:00Z");
        OffsetDateTime second = OffsetDateTime.parse("2024-01-15T09:32:00Z");
        when(recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(record)).thenReturn(List.of(
                change(record, true, false, operator, first),
                change(record, false, true, admin, second)));

        ConformityChangesList result = recordService.listConformityChanges(record.getId());

        assertThat(result.getChanges()).hasSize(2);
        ConformityChange firstChange = result.getChanges().get(0);
        assertThat(firstChange.getPreviousIsCompliant()).isTrue();
        assertThat(firstChange.getNewIsCompliant()).isFalse();
        assertThat(firstChange.getAuthorUsername()).isEqualTo("op1");
        assertThat(firstChange.getChangedAt()).isEqualTo(first);
        ConformityChange secondChange = result.getChanges().get(1);
        assertThat(secondChange.getPreviousIsCompliant()).isFalse();
        assertThat(secondChange.getNewIsCompliant()).isTrue();
        assertThat(secondChange.getAuthorUsername()).isEqualTo("admin1");
        assertThat(secondChange.getChangedAt()).isEqualTo(second);
    }

    @Test
    void listConformityChanges_unknownRecord_throwsNotFound() {
        UUID id = UUID.randomUUID();
        when(recordRepository.findById(id)).thenReturn(Optional.empty());

        assertThrows(RecordNotFoundException.class, () -> recordService.listConformityChanges(id));
    }

    // --- tag off-list flag (spec 010, FR-008) ---

    @Test
    void list_flagsRecordsOfOffListTags() {
        ReaderEntity lineOne = reader("L1");
        when(readerRepository.findByName("L1")).thenReturn(Optional.of(lineOne));
        when(recordRepository.findTop10ByReader_NameOrderByCreationDateDesc("L1")).thenReturn(List.of(
                recordOf("OFF", lineOne), recordOf("IN", lineOne)));
        when(referenceTagList.isOffList("OFF")).thenReturn(true);

        List<RecordSummary> records = recordService.listLatestRecordsForReader("L1").getRecords();

        assertThat(records).extracting(RecordSummary::getTagUid).containsExactly("OFF", "IN");
        assertThat(records).extracting(RecordSummary::getTagOffList).containsExactly(true, false);
    }

    // --- line kiosk with the reader token (spec 008 FR-005a, research R12-R13) ---

    @Test
    void list_asReader_otherName_throws403() {
        actAsReader(reader("L1"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> recordService.listLatestRecordsForReader("L2"));

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verifyNoInteractions(readerRepository);
    }

    @Test
    void update_asReader_ownRecord_writesChangeWithReaderAuthor() {
        ReaderEntity lineOne = reader("L1");
        actAsReader(lineOne);
        RecordEntity record = lockedRecord(true, lineOne);

        recordService.updateRecordConformity(record.getId(), false);

        assertThat(record.isCompliant()).isFalse();
        ArgumentCaptor<RecordConformityChangeEntity> change = ArgumentCaptor.forClass(RecordConformityChangeEntity.class);
        verify(recordConformityChangeRepository).save(change.capture());
        assertThat(change.getValue().getAuthorReader()).isSameAs(lineOne);
        assertThat(change.getValue().getAuthor()).isNull();
        verifyNoInteractions(userRepository);
    }

    @Test
    void update_asReader_otherReadersRecord_throws403_writesNothing() {
        actAsReader(reader("L1"));
        RecordEntity record = lockedRecord(true, reader("L2"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> recordService.updateRecordConformity(record.getId(), false));

        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(record.isCompliant()).isTrue();
        verify(recordConformityChangeRepository, never()).save(any());
    }

    @Test
    void update_asReader_sameValue_writesNothing() {
        ReaderEntity lineOne = reader("L1");
        actAsReader(lineOne);
        RecordEntity record = lockedRecord(true, lineOne);

        recordService.updateRecordConformity(record.getId(), true);

        verify(recordConformityChangeRepository, never()).save(any());
    }

    @Test
    void listConformityChanges_mapsReaderAuthor() {
        RecordEntity record = RecordEntity.builder().id(UUID.randomUUID()).compliant(false).build();
        when(recordRepository.findById(record.getId())).thenReturn(Optional.of(record));
        RecordConformityChangeEntity change = RecordConformityChangeEntity.builder().record(record)
                .previousCompliant(true).newCompliant(false).authorReader(reader("L1"))
                .changedAt(OffsetDateTime.parse("2024-01-15T09:31:00Z")).build();
        when(recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(record)).thenReturn(List.of(change));

        ConformityChange result = recordService.listConformityChanges(record.getId()).getChanges().get(0);

        assertThat(result.getAuthorType()).isEqualTo(ConformityChange.AuthorTypeEnum.READER);
        assertThat(result.getAuthorReaderUid()).isEqualTo("L1");
        assertThat(result.getAuthorUsername()).isNull();
    }

    private static RecordEntity recordOf(String tagUid, ReaderEntity reader) {
        return RecordEntity.builder().id(UUID.randomUUID()).tag(TagEntity.builder().uid(tagUid).build())
                .reader(reader).compliant(true).creationDate(OffsetDateTime.now()).build();
    }

    private static ReaderEntity reader(String name) {
        return ReaderEntity.builder().id(UUID.randomUUID()).name(name).build();
    }

    private static void actAsReader(ReaderEntity reader) {
        SecurityContextHolder.getContext().setAuthentication(new ReaderAuthentication(reader));
    }

    private RecordEntity lockedRecord(boolean compliant, ReaderEntity reader) {
        RecordEntity record = RecordEntity.builder().id(UUID.randomUUID()).compliant(compliant).reader(reader).build();
        when(recordRepository.findWithLockById(record.getId())).thenReturn(Optional.of(record));
        return record;
    }

    private static RecordConformityChangeEntity change(RecordEntity record, boolean previous, boolean next,
            UserEntity author, OffsetDateTime changedAt) {
        return RecordConformityChangeEntity.builder().record(record).previousCompliant(previous).newCompliant(next)
                .author(author).changedAt(changedAt).build();
    }

    private RecordEntity lockedRecord(boolean compliant) {
        RecordEntity record = RecordEntity.builder().id(UUID.randomUUID()).compliant(compliant).build();
        when(recordRepository.findWithLockById(record.getId())).thenReturn(Optional.of(record));
        return record;
    }
}
