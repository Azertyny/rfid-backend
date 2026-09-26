package com.rfidback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import com.rfidback.generated.model.CreateReader;
import com.rfidback.generated.model.Reader;
import com.rfidback.generated.model.UpdateReader;
import com.rfidback.repository.ReaderRepository;

class ReaderServiceTest {

    private ReaderRepository readerRepository;
    private LineActivityService lineActivityService;
    private ReaderService readerService;

    @BeforeEach
    void setUp() {
        readerRepository = Mockito.mock(ReaderRepository.class);
        lineActivityService = Mockito.mock(LineActivityService.class);
        readerService = new ReaderService(readerRepository, Mockito.mock(RegistrationService.class),
                lineActivityService);
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
        when(readerRepository.findAll()).thenReturn(List.of(disabled));

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
