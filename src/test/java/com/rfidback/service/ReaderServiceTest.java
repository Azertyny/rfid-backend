package com.rfidback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.generated.model.ReaderMode;
import com.rfidback.exception.ReaderAlreadyExistsException;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.exception.ReaderStillActiveException;
import com.rfidback.generated.model.CreateReader;
import com.rfidback.generated.model.Reader;
import com.rfidback.generated.model.UpdateReader;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.ReaderRepository;

class ReaderServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"), ZoneOffset.UTC);

    private ReaderRepository readerRepository;
    private LineActivityService lineActivityService;
    private RegistrationService registrationService;
    private ActivityRepository activityRepository;
    private ReaderService readerService;

    @BeforeEach
    void setUp() {
        readerRepository = Mockito.mock(ReaderRepository.class);
        lineActivityService = Mockito.mock(LineActivityService.class);
        registrationService = Mockito.mock(RegistrationService.class);
        activityRepository = Mockito.mock(ActivityRepository.class);
        readerService = new ReaderService(readerRepository, registrationService, lineActivityService,
                activityRepository, CLOCK);
        // Stand in for JPA: saving assigns an id, the timestamps and (through @PrePersist) the token.
        Answer<ReaderEntity> persist = invocation -> {
            ReaderEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
                entity.setCreationDate(OffsetDateTime.now());
            }
            entity.setUpdateDate(OffsetDateTime.now());
            entity.prePersist();
            return entity;
        };
        when(readerRepository.save(any(ReaderEntity.class))).thenAnswer(persist);
        when(readerRepository.saveAndFlush(any(ReaderEntity.class))).thenAnswer(persist);
    }

    @Test
    void getReaders_mapsIdAndActive() {
        ReaderEntity disabled = reader("Poste B", false);
        when(readerRepository.findAllByDeletedAtIsNull()).thenReturn(List.of(disabled));

        Reader reader = readerService.getReaders().getReaders().get(0);

        assertEquals(disabled.getId(), reader.getId());
        assertEquals("Poste B", reader.getUid());
        assertFalse(reader.getActive());
    }

    @Test
    void createReader_trimsUid() throws Exception {
        Reader created = readerService.createReader(new CreateReader().uid("  Poste A "));

        ArgumentCaptor<ReaderEntity> captor = ArgumentCaptor.forClass(ReaderEntity.class);
        verify(readerRepository).saveAndFlush(captor.capture());
        assertEquals("Poste A", captor.getValue().getName());
        assertEquals("Poste A", created.getUid());
    }

    @Test
    void createReader_blankUid_throwsBadRequest() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> readerService.createReader(new CreateReader().uid("   ")));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(readerRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReader_duplicateIgnoringCase_throwsConflict() {
        when(readerRepository.existsByNameIgnoreCase("Poste A")).thenReturn(true);

        assertThrows(ReaderAlreadyExistsException.class,
                () -> readerService.createReader(new CreateReader().uid(" Poste A ")));
        verify(readerRepository, never()).saveAndFlush(any());
    }

    @Test
    void createReader_constraintViolationOnSave_throwsConflict() {
        when(readerRepository.saveAndFlush(any(ReaderEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThrows(ReaderAlreadyExistsException.class,
                () -> readerService.createReader(new CreateReader().uid("Poste A")));
    }

    @Test
    void updateReader_emptyBody_throwsBadRequest() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> readerService.updateReader(UUID.randomUUID(), new UpdateReader()));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(readerRepository, never()).findWithLockById(any());
    }

    @Test
    void updateReader_unknownId_throwsNotFound() {
        UUID readerId = UUID.randomUUID();
        when(readerRepository.findWithLockById(readerId)).thenReturn(Optional.empty());

        assertThrows(ReaderNotFoundException.class,
                () -> readerService.updateReader(readerId, new UpdateReader().active(false)));
    }

    @Test
    void updateReader_setsActive() {
        ReaderEntity active = reader("Poste A", true);
        when(readerRepository.findWithLockById(active.getId())).thenReturn(Optional.of(active));

        Reader updated = readerService.updateReader(active.getId(), new UpdateReader().active(false));

        ArgumentCaptor<ReaderEntity> captor = ArgumentCaptor.forClass(ReaderEntity.class);
        verify(readerRepository).save(captor.capture());
        assertFalse(captor.getValue().isActive());
        assertFalse(updated.getActive());
        assertNotNull(updated.getApitoken());
    }

    @Test
    void updateReader_switchToRegistration_clearsTheCurrentActivity() {
        ReaderEntity line = reader("Ligne 1", true);
        line.setCurrentActivity(ActivityEntity.builder().id(UUID.randomUUID()).name("Fraise").build());
        UserEntity admin = UserEntity.builder().username("admin").build();
        when(readerRepository.findWithLockById(line.getId())).thenReturn(Optional.of(line));
        when(lineActivityService.currentUser()).thenReturn(admin);

        readerService.updateReader(line.getId(), new UpdateReader().mode(ReaderMode.ENREGISTREMENT));

        verify(lineActivityService).clear(line, admin);
        verify(lineActivityService, never()).applyDefaultIfNone(any(), any());
    }

    @Test
    void updateReader_switchBackToProduction_appliesTheLinesDefaultActivity() {
        ReaderEntity line = reader("Ligne 1", true);
        line.setMode(com.rfidback.entity.ReaderMode.ENREGISTREMENT);
        when(readerRepository.findWithLockById(line.getId())).thenReturn(Optional.of(line));

        readerService.updateReader(line.getId(), new UpdateReader().mode(ReaderMode.PRODUCTION));

        // FR-008b: its only associated active activity, if any, decided by LineActivityService.
        verify(lineActivityService).applyDefaultIfNone(eq(line), any());
    }

    @Test
    void updateReader_alreadyInProduction_doesNotApplyTheDefault() {
        ReaderEntity line = reader("Ligne 1", true);
        when(readerRepository.findWithLockById(line.getId())).thenReturn(Optional.of(line));

        readerService.updateReader(line.getId(), new UpdateReader().mode(ReaderMode.PRODUCTION));

        verify(lineActivityService, never()).applyDefaultIfNone(any(), any());
    }

    @Test
    void updateReader_disable_keepsTheCurrentActivity() {
        ReaderEntity line = reader("Ligne 1", true);
        line.setCurrentActivity(ActivityEntity.builder().id(UUID.randomUUID()).name("Fraise").build());
        when(readerRepository.findWithLockById(line.getId())).thenReturn(Optional.of(line));

        readerService.updateReader(line.getId(), new UpdateReader().active(false));

        verify(lineActivityService, never()).clear(any(), any());
    }

    @Test
    void rotateToken_replacesToken() {
        ReaderEntity disabled = reader("Poste A", false);
        String oldToken = disabled.getApitoken();
        when(readerRepository.findById(disabled.getId())).thenReturn(Optional.of(disabled));

        Reader rotated = readerService.rotateToken(disabled.getId());

        assertTrue(rotated.getApitoken().matches("[0-9a-f]{32}"));
        assertNotEquals(oldToken, rotated.getApitoken());
        ArgumentCaptor<ReaderEntity> captor = ArgumentCaptor.forClass(ReaderEntity.class);
        verify(readerRepository).save(captor.capture());
        assertEquals(rotated.getApitoken(), captor.getValue().getApitoken());
        assertFalse(rotated.getActive());
    }

    @Test
    void rotateToken_unknownId_throwsNotFound() {
        UUID readerId = UUID.randomUUID();
        when(readerRepository.findById(readerId)).thenReturn(Optional.empty());

        assertThrows(ReaderNotFoundException.class, () -> readerService.rotateToken(readerId));
    }

    @Test
    void getReaders_leavesOutDeletedReaders() {
        when(readerRepository.findAllByDeletedAtIsNull()).thenReturn(List.of());

        assertTrue(readerService.getReaders().getReaders().isEmpty());
        verify(readerRepository, never()).findAll();
    }

    @Test
    void updateReader_deletedReader_throwsNotFound() {
        ReaderEntity deleted = deletedReader("Poste A");
        when(readerRepository.findWithLockById(deleted.getId())).thenReturn(Optional.of(deleted));

        assertThrows(ReaderNotFoundException.class,
                () -> readerService.updateReader(deleted.getId(), new UpdateReader().active(true)));
        verify(readerRepository, never()).save(any());
    }

    @Test
    void rotateToken_deletedReader_throwsNotFound() {
        ReaderEntity deleted = deletedReader("Poste A");
        when(readerRepository.findById(deleted.getId())).thenReturn(Optional.of(deleted));

        assertThrows(ReaderNotFoundException.class, () -> readerService.rotateToken(deleted.getId()));
        verify(readerRepository, never()).save(any());
    }

    @Test
    void deleteReader_activeReader_throwsConflictAndChangesNothing() {
        ReaderEntity active = reader("Poste A", true);
        when(readerRepository.findWithLockById(active.getId())).thenReturn(Optional.of(active));

        assertThrows(ReaderStillActiveException.class, () -> readerService.deleteReader(active.getId()));
        assertNull(active.getDeletedAt());
        verify(readerRepository, never()).save(any());
        verify(registrationService, never()).closeForReader(any());
    }

    @Test
    void deleteReader_unknownId_throwsNotFound() {
        UUID readerId = UUID.randomUUID();
        when(readerRepository.findWithLockById(readerId)).thenReturn(Optional.empty());

        assertThrows(ReaderNotFoundException.class, () -> readerService.deleteReader(readerId));
    }

    @Test
    void deleteReader_alreadyDeleted_throwsNotFound() {
        ReaderEntity deleted = deletedReader("Poste A");
        when(readerRepository.findWithLockById(deleted.getId())).thenReturn(Optional.of(deleted));

        assertThrows(ReaderNotFoundException.class, () -> readerService.deleteReader(deleted.getId()));
        verify(readerRepository, never()).save(any());
    }

    @Test
    void deleteReader_disabledLine_dissociatesClearsClosesAndMarksDeleted() {
        ReaderEntity line = reader("Ligne 1", false);
        ActivityEntity fraise = ActivityEntity.builder().id(UUID.randomUUID()).name("Fraise").build();
        ActivityEntity framboise = ActivityEntity.builder().id(UUID.randomUUID()).name("Framboise").build();
        fraise.getLines().add(line);
        framboise.getLines().add(line);
        line.setCurrentActivity(fraise);
        UserEntity admin = UserEntity.builder().username("admin").build();
        when(readerRepository.findWithLockById(line.getId())).thenReturn(Optional.of(line));
        when(activityRepository.findAllByLine(line.getId())).thenReturn(List.of(fraise, framboise));
        when(lineActivityService.currentUser()).thenReturn(admin);

        readerService.deleteReader(line.getId());

        assertTrue(fraise.getLines().isEmpty());
        assertTrue(framboise.getLines().isEmpty());
        // The state is brought to today before the current activity is read (research R11 step 3).
        InOrder inOrder = Mockito.inOrder(activityRepository, lineActivityService);
        inOrder.verify(activityRepository).flush();
        inOrder.verify(lineActivityService).startNewDay(line);
        inOrder.verify(lineActivityService).clear(line, admin);
        // The guard of research R11 step 4: deactivation normally closed the session already.
        verify(registrationService).closeForReader(line);
        ArgumentCaptor<ReaderEntity> captor = ArgumentCaptor.forClass(ReaderEntity.class);
        verify(readerRepository).save(captor.capture());
        assertEquals(OffsetDateTime.now(CLOCK), captor.getValue().getDeletedAt());
        assertTrue(captor.getValue().isDeleted());
    }

    @Test
    void deleteReader_lineWithoutCurrentActivity_logsNoChange() {
        ReaderEntity line = reader("Ligne 1", false);
        when(readerRepository.findWithLockById(line.getId())).thenReturn(Optional.of(line));
        when(activityRepository.findAllByLine(line.getId())).thenReturn(List.of());

        readerService.deleteReader(line.getId());

        verify(lineActivityService).startNewDay(line);
        verify(lineActivityService, never()).clear(any(), any());
        assertTrue(line.isDeleted());
    }

    private static ReaderEntity deletedReader(String name) {
        ReaderEntity reader = reader(name, false);
        reader.setDeletedAt(OffsetDateTime.now(CLOCK).minusDays(1));
        return reader;
    }

    private static ReaderEntity reader(String name, boolean active) {
        return ReaderEntity.builder()
                .id(UUID.randomUUID())
                .name(name)
                .apitoken(ReaderEntity.newApitoken())
                .active(active)
                .creationDate(OffsetDateTime.now())
                .updateDate(OffsetDateTime.now())
                .build();
    }
}
