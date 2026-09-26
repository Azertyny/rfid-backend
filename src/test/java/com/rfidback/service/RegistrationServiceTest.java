package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.RegistrationReadEntity;
import com.rfidback.entity.RegistrationSessionEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.ReaderBusyException;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.exception.RegistrationSessionNotFoundException;
import com.rfidback.exception.RegistrationNotConfirmedException;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.RegistrationReadsResponse;
import com.rfidback.generated.model.RegistrationSession;
import com.rfidback.generated.model.SaveRegistrationSession;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.generated.model.TagInOtherBucket;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RegistrationReadRepository;
import com.rfidback.repository.RegistrationSessionRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;

class RegistrationServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-24T10:00:00Z");
    private static final Duration TIMEOUT = Duration.ofMinutes(5);

    private RegistrationSessionRepository sessionRepository;
    private RegistrationReadRepository readRepository;
    private ReaderRepository readerRepository;
    private UserRepository userRepository;
    private TagRepository tagRepository;
    private TagService tagService;
    private ReferenceTagList referenceTagList;
    private RegistrationService registrationService;

    private UserEntity alice;
    private UserEntity bob;
    private ReaderEntity reader;

    @BeforeEach
    void setUp() {
        sessionRepository = Mockito.mock(RegistrationSessionRepository.class);
        readRepository = Mockito.mock(RegistrationReadRepository.class);
        readerRepository = Mockito.mock(ReaderRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        tagRepository = Mockito.mock(TagRepository.class);
        tagService = Mockito.mock(TagService.class);
        referenceTagList = Mockito.mock(ReferenceTagList.class);
        Clock clock = Clock.fixed(Instant.parse("2026-09-24T10:00:00Z"), ZoneOffset.UTC);
        registrationService = new RegistrationService(sessionRepository, readRepository, readerRepository,
                userRepository, tagRepository, tagService, referenceTagList, clock, TIMEOUT,
                transactionManager());

        alice = user("alice");
        bob = user("bob");
        reader = ReaderEntity.builder().id(UUID.randomUUID()).name("Poste A").apitoken("token")
                .active(true).mode(ReaderMode.ENREGISTREMENT).build();
        when(readerRepository.findById(reader.getId())).thenReturn(Optional.of(reader));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(alice));
        when(sessionRepository.saveAndFlush(any(RegistrationSessionEntity.class))).thenAnswer(invocation -> {
            RegistrationSessionEntity entity = invocation.getArgument(0);
            entity.setId(UUID.randomUUID());
            return entity;
        });
        loginAs("alice");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // --- start ---

    @Test
    void start_createsSession() {
        RegistrationSession session = registrationService.start(reader.getId());

        assertEquals(reader.getId(), session.getReaderId());
        assertEquals("Poste A", session.getReaderUid());
        assertEquals("alice", session.getStartedBy());
        assertEquals(NOW, session.getStartedAt());
        assertThat(session.getReads()).isEmpty();
        ArgumentCaptor<RegistrationSessionEntity> captor = ArgumentCaptor.forClass(RegistrationSessionEntity.class);
        verify(sessionRepository).saveAndFlush(captor.capture());
        assertEquals(NOW, captor.getValue().getLastActivityAt());
        assertEquals(alice, captor.getValue().getStartedBy());
    }

    @Test
    void start_readerInProduction_throws400() {
        reader.setMode(ReaderMode.PRODUCTION);

        assertStatus(HttpStatus.BAD_REQUEST, () -> registrationService.start(reader.getId()));
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void start_readerDisabled_throws400() {
        reader.setActive(false);

        assertStatus(HttpStatus.BAD_REQUEST, () -> registrationService.start(reader.getId()));
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void start_unknownReader_throwsReaderNotFound() {
        UUID unknown = UUID.randomUUID();
        when(readerRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(ReaderNotFoundException.class, () -> registrationService.start(unknown));
    }

    @Test
    void start_readerBusyWithLiveSession_throwsReaderBusy() {
        RegistrationSessionEntity live = session(bob, NOW.minusMinutes(1));
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.of(live));

        ReaderBusyException exception = assertThrows(ReaderBusyException.class,
                () -> registrationService.start(reader.getId()));

        assertEquals("bob", exception.getStartedBy());
        assertEquals(live.getStartedAt(), exception.getStartedAt());
        verify(sessionRepository, never()).saveAndFlush(any());
    }

    @Test
    void start_readerWithExpiredSession_deletesItAndCreatesNew() {
        RegistrationSessionEntity expired = session(bob, NOW.minusMinutes(6));
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.of(expired));

        RegistrationSession session = registrationService.start(reader.getId());

        assertEquals("alice", session.getStartedBy());
        verify(readRepository).deleteBySession(expired);
        verify(sessionRepository).delete(expired);
        verify(sessionRepository).saveAndFlush(any(RegistrationSessionEntity.class));
    }

    @Test
    void start_concurrentInsert_throwsReaderBusyWithWinnerOwner() {
        RegistrationSessionEntity winner = session(bob, NOW);
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.empty(), Optional.of(winner));
        when(sessionRepository.saveAndFlush(any(RegistrationSessionEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique reader_id"));

        ReaderBusyException exception = assertThrows(ReaderBusyException.class,
                () -> registrationService.start(reader.getId()));

        assertEquals("bob", exception.getStartedBy());
    }

    @Test
    void start_concurrentInsert_winnerAlreadyGone_retriesOnce() {
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(RegistrationSessionEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique reader_id"))
                .thenAnswer(invocation -> {
                    RegistrationSessionEntity entity = invocation.getArgument(0);
                    entity.setId(UUID.randomUUID());
                    return entity;
                });

        RegistrationSession session = registrationService.start(reader.getId());

        assertEquals("alice", session.getStartedBy());
        verify(sessionRepository, times(2)).saveAndFlush(any(RegistrationSessionEntity.class));
    }

    @Test
    void start_concurrentInsertTwice_noWinnerFound_throwsReaderBusyWithoutOwner() {
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.empty());
        when(sessionRepository.saveAndFlush(any(RegistrationSessionEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique reader_id"));

        ReaderBusyException exception = assertThrows(ReaderBusyException.class,
                () -> registrationService.start(reader.getId()));

        assertEquals(null, exception.getStartedBy());
        assertEquals(null, exception.getStartedAt());
        verify(sessionRepository, times(2)).saveAndFlush(any(RegistrationSessionEntity.class));
    }

    // --- recordRead ---

    @Test
    void recordRead_noSession_returnsIgnored() {
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.empty());

        ScanTagResponse response = registrationService.recordRead(reader, "T1");

        assertIgnored(response);
        verify(readRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordRead_storesUidOnceAndTouchesSession() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.of(open));
        when(readRepository.existsBySessionAndUid(open, "T1")).thenReturn(false, true);

        ScanTagResponse first = registrationService.recordRead(reader, " T1 ");
        ScanTagResponse second = registrationService.recordRead(reader, "T1");

        assertEquals("T1", first.getUid());
        assertEquals(true, first.getIsCompliant());
        assertEquals(NOW, first.getProcessedAt());
        assertEquals("Registration read", first.getMessage());
        assertEquals("Registration read", second.getMessage());
        ArgumentCaptor<RegistrationReadEntity> captor = ArgumentCaptor.forClass(RegistrationReadEntity.class);
        verify(readRepository, times(1)).saveAndFlush(captor.capture());
        assertEquals("T1", captor.getValue().getUid());
        assertEquals(NOW, captor.getValue().getFirstReadAt());
        verify(sessionRepository, times(2)).touch(open.getId(), NOW);
    }

    @Test
    void recordRead_blankUid_ignored() {
        ScanTagResponse response = registrationService.recordRead(reader, "   ");

        assertIgnored(response);
        verify(sessionRepository, never()).findByReader(any());
    }

    @Test
    void recordRead_expiredSession_deletesItAndReturnsIgnored() {
        RegistrationSessionEntity expired = session(alice, NOW.minusMinutes(6));
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.of(expired));

        ScanTagResponse response = registrationService.recordRead(reader, "T1");

        assertIgnored(response);
        verify(readRepository).deleteBySession(expired);
        verify(sessionRepository).delete(expired);
        verify(readRepository, never()).saveAndFlush(any());
    }

    @Test
    void recordRead_concurrentDuplicateInsert_isIgnored() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.of(open));
        when(readRepository.existsBySessionAndUid(open, "T1")).thenReturn(false);
        when(readRepository.saveAndFlush(any(RegistrationReadEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique session_id, uid"));

        ScanTagResponse response = registrationService.recordRead(reader, "T1");

        assertEquals("Registration read", response.getMessage());
        verify(sessionRepository).touch(open.getId(), NOW);
    }

    // --- recordReads (spec 011) ---

    @Test
    void recordReads_fromProductionReader_returns403WithoutTouchingData() {
        ReaderEntity production = ReaderEntity.builder().id(UUID.randomUUID()).name("Ligne 1").apitoken("prod")
                .active(true).mode(ReaderMode.PRODUCTION).build();

        assertStatus(HttpStatus.FORBIDDEN, () -> registrationService.recordReads(production, List.of("T1")));

        Mockito.verifyNoInteractions(sessionRepository, readRepository);
    }

    @Test
    void recordReads_nullList_returns400WithoutTouchingData() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> registrationService.recordReads(reader, null));

        Mockito.verifyNoInteractions(sessionRepository, readRepository);
    }

    @Test
    void recordReads_lockTimeoutOnce_isRetriedAndKeepsTheReads() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findLockedByReader(reader))
                .thenThrow(new CannotAcquireLockException("lock timeout"))
                .thenReturn(Optional.of(open));
        when(readRepository.findUidsBySessionAndUidIn(eq(open), anyCollection())).thenReturn(List.of());

        RegistrationReadsResponse response = registrationService.recordReads(reader, List.of("T1", "T2"));

        assertEquals(true, response.getSessionOpen());
        assertEquals(2, response.getAddedCount());
        assertEquals(NOW, open.getLastActivityAt());
        verify(readRepository).saveAllAndFlush(anyList());
    }

    @Test
    void recordReads_lockTimeoutTwice_returns409() {
        when(sessionRepository.findLockedByReader(reader))
                .thenThrow(new CannotAcquireLockException("lock timeout"));

        assertStatus(HttpStatus.CONFLICT, () -> registrationService.recordReads(reader, List.of("T1")));

        verify(sessionRepository, times(2)).findLockedByReader(reader);
        verify(readRepository, never()).saveAllAndFlush(anyList());
    }

    @Test
    void recordReads_constraintRaceOnce_isRetried() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findLockedByReader(reader)).thenReturn(Optional.of(open));
        when(readRepository.findUidsBySessionAndUidIn(eq(open), anyCollection()))
                .thenReturn(List.of(), List.of("T1"));
        when(readRepository.saveAllAndFlush(anyList()))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RegistrationReadsResponse response = registrationService.recordReads(reader, List.of("T1", "T2"));

        assertEquals(2, response.getReceivedCount());
        assertEquals(1, response.getAddedCount());
    }

    // --- get ---

    @Test
    void get_byOtherUser_throws403() {
        RegistrationSessionEntity bobs = session(bob, NOW.minusMinutes(1));
        when(sessionRepository.findById(bobs.getId())).thenReturn(Optional.of(bobs));

        assertStatus(HttpStatus.FORBIDDEN, () -> registrationService.get(bobs.getId()));
    }

    @Test
    void get_expired_throws404AndDeletes() {
        // Only checks that delete is called; RegistrationApiTest checks that the deletion is really committed.
        RegistrationSessionEntity expired = session(alice, NOW.minusMinutes(6));
        when(sessionRepository.findById(expired.getId())).thenReturn(Optional.of(expired));

        assertThrows(RegistrationSessionNotFoundException.class, () -> registrationService.get(expired.getId()));
        verify(readRepository).deleteBySession(expired);
        verify(sessionRepository).delete(expired);
    }

    @Test
    void get_unknown_throws404() {
        UUID unknown = UUID.randomUUID();
        when(sessionRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThrows(RegistrationSessionNotFoundException.class, () -> registrationService.get(unknown));
    }

    @Test
    void get_updatesLastActivityOnlyWhenOlderThan30s() {
        RegistrationSessionEntity recent = session(alice, NOW.minusSeconds(10));
        RegistrationSessionEntity older = session(alice, NOW.minusSeconds(40));
        when(sessionRepository.findById(recent.getId())).thenReturn(Optional.of(recent));
        when(sessionRepository.findById(older.getId())).thenReturn(Optional.of(older));

        registrationService.get(recent.getId());
        registrationService.get(older.getId());

        assertEquals(NOW.minusSeconds(10), recent.getLastActivityAt());
        assertEquals(NOW, older.getLastActivityAt());
    }

    @Test
    void get_flagsCurrentBucketOfEachRead() {
        RegistrationSessionEntity open = session(alice, NOW.minusSeconds(10));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open))
                .thenReturn(List.of(read(open, "T1"), read(open, "T2")));
        BucketEntity bucket = BucketEntity.builder().number(7).build();
        when(tagRepository.findAllByUidIn(anyCollection()))
                .thenReturn(List.of(TagEntity.builder().uid("T1").bucket(bucket).build()));

        RegistrationSession session = registrationService.get(open.getId());

        assertThat(session.getReads()).extracting(r -> r.getUid()).containsExactly("T1", "T2");
        assertEquals(7, session.getReads().get(0).getBucketNumber());
        assertEquals(null, session.getReads().get(1).getBucketNumber());
    }

    @Test
    void get_flagsOffListReads() {
        RegistrationSessionEntity open = session(alice, NOW.minusSeconds(10));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open))
                .thenReturn(List.of(read(open, "IN"), read(open, "OFF")));
        when(referenceTagList.isOffList("OFF")).thenReturn(true);

        RegistrationSession session = registrationService.get(open.getId());

        assertThat(session.getReads()).extracting(r -> r.getOffList()).containsExactly(false, true);
    }

    // --- cancel, save, closeForReader ---

    @Test
    void cancel_deletesSession() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));

        registrationService.cancel(open.getId());

        verify(readRepository).deleteBySession(open);
        verify(sessionRepository).delete(open);
    }

    @Test
    void save_registersReadsThroughTagServiceAndDeletesSession() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open))
                .thenReturn(List.of(read(open, "T1"), read(open, "T2")));
        RegisterTagsResponse expected = new RegisterTagsResponse(12, 2, 2);
        when(tagService.registerTagsForBucket(12, List.of("T1", "T2"), true, false)).thenReturn(expected);

        RegisterTagsResponse response = registrationService.save(open.getId(),
                new SaveRegistrationSession(12).moveConfirmed(true));

        assertEquals(expected, response);
        verify(sessionRepository).delete(open);
    }

    @Test
    void save_noReads_throws400() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open)).thenReturn(List.of());

        assertStatus(HttpStatus.BAD_REQUEST,
                () -> registrationService.save(open.getId(), new SaveRegistrationSession(12)));
        verify(tagService, never()).registerTagsForBucket(anyInt(), anyList(), anyBoolean(), anyBoolean());
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void save_conflict_keepsSession() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open)).thenReturn(List.of(read(open, "T1")));
        when(tagService.registerTagsForBucket(12, List.of("T1"), false, false))
                .thenThrow(new RegistrationNotConfirmedException(List.of(new TagInOtherBucket("T1", 7)), List.of()));

        assertThrows(RegistrationNotConfirmedException.class,
                () -> registrationService.save(open.getId(), new SaveRegistrationSession(12)));
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void save_passesOffListConfirmationToTagService() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open)).thenReturn(List.of(read(open, "OFF")));
        when(tagService.registerTagsForBucket(12, List.of("OFF"), false, true))
                .thenReturn(new RegisterTagsResponse(12, 1, 1));

        registrationService.save(open.getId(), new SaveRegistrationSession(12).offListConfirmed(true));

        verify(tagService).registerTagsForBucket(12, List.of("OFF"), false, true);
        verify(sessionRepository).delete(open);
    }

    @Test
    void save_offListNotConfirmed_keepsSession() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open)).thenReturn(List.of(read(open, "OFF")));
        when(tagService.registerTagsForBucket(12, List.of("OFF"), false, false))
                .thenThrow(new RegistrationNotConfirmedException(List.of(), List.of("OFF")));

        assertThrows(RegistrationNotConfirmedException.class,
                () -> registrationService.save(open.getId(), new SaveRegistrationSession(12)));
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void save_101Reads_throws400AndKeepsSession() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open)).thenReturn(reads(open, 101));

        assertStatus(HttpStatus.BAD_REQUEST,
                () -> registrationService.save(open.getId(), new SaveRegistrationSession(12)));
        verify(tagService, never()).registerTagsForBucket(anyInt(), anyList(), anyBoolean(), anyBoolean());
        verify(sessionRepository, never()).delete(any());
    }

    @Test
    void save_100Reads_registers() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findById(open.getId())).thenReturn(Optional.of(open));
        when(readRepository.findAllBySessionOrderByFirstReadAtAsc(open)).thenReturn(reads(open, 100));
        when(tagService.registerTagsForBucket(eq(12), anyList(), eq(false), eq(false)))
                .thenReturn(new RegisterTagsResponse(12, 100, 100));

        RegisterTagsResponse response = registrationService.save(open.getId(), new SaveRegistrationSession(12));

        assertEquals(100, response.getRegisteredCount());
        verify(sessionRepository).delete(open);
    }

    @Test
    void closeForReader_deletesOpenSession() {
        RegistrationSessionEntity open = session(alice, NOW.minusMinutes(1));
        when(sessionRepository.findByReader(reader)).thenReturn(Optional.of(open));

        registrationService.closeForReader(reader);

        verify(readRepository).deleteBySession(open);
        verify(sessionRepository).delete(open);
    }

    // --- helpers ---

    private RegistrationSessionEntity session(UserEntity owner, OffsetDateTime lastActivityAt) {
        return RegistrationSessionEntity.builder().id(UUID.randomUUID()).reader(reader).startedBy(owner)
                .startedAt(lastActivityAt.minusMinutes(1)).lastActivityAt(lastActivityAt).build();
    }

    private static RegistrationReadEntity read(RegistrationSessionEntity session, String uid) {
        return RegistrationReadEntity.builder().id(UUID.randomUUID()).session(session).uid(uid)
                .firstReadAt(NOW.minusSeconds(5)).build();
    }

    private static List<RegistrationReadEntity> reads(RegistrationSessionEntity session, int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> read(session, "T" + i)).toList();
    }

    private static UserEntity user(String username) {
        return UserEntity.builder().id(UUID.randomUUID()).username(username).passwordHash("hash")
                .role(Role.ADMINISTRATEUR).enabled(true).build();
    }

    private static void loginAs(String username) {
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(username, null, List.of()));
    }

    private static void assertIgnored(ScanTagResponse response) {
        assertEquals(true, response.getIsCompliant());
        assertEquals(NOW, response.getProcessedAt());
        assertEquals("Registration read ignored: no session started", response.getMessage());
    }

    /** Runs recordReads' transaction callbacks directly; locking and rollback are covered by RegistrationReadsApiTest. */
    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager transactionManager = Mockito.mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return transactionManager;
    }

    private static void assertStatus(HttpStatus expected, org.junit.jupiter.api.function.Executable call) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, call);
        assertEquals(expected, exception.getStatusCode());
    }
}
