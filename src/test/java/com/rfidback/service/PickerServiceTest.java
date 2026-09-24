package com.rfidback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.PickerEntity;
import com.rfidback.exception.PickerAlreadyExistsException;
import com.rfidback.exception.PickerHasBucketsException;
import com.rfidback.exception.PickerNotFoundException;
import com.rfidback.generated.model.CreatePicker;
import com.rfidback.generated.model.UpdatePicker;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;

class PickerServiceTest {

    private PickerRepository pickerRepository;
    private BucketRepository bucketRepository;
    private PickerService pickerService;

    @BeforeEach
    void setUp() {
        pickerRepository = Mockito.mock(PickerRepository.class);
        bucketRepository = Mockito.mock(BucketRepository.class);
        pickerService = new PickerService(pickerRepository, bucketRepository);
    }

    @Test
    void getPicker_unknownId_throwsNotFound() {
        UUID pickerId = UUID.randomUUID();
        when(pickerRepository.findById(pickerId)).thenReturn(Optional.empty());

        assertThrows(PickerNotFoundException.class, () -> pickerService.getPicker(pickerId));
    }

    @Test
    void createPicker_trimsNamesAndBlankCommentBecomesNull() {
        when(pickerRepository.save(any(PickerEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        pickerService.createPicker(new CreatePicker().lastname("  Dupont ").firstname(" Jean ").comment("   "));

        ArgumentCaptor<PickerEntity> captor = ArgumentCaptor.forClass(PickerEntity.class);
        verify(pickerRepository).save(captor.capture());
        assertEquals("Dupont", captor.getValue().getLastname());
        assertEquals("Jean", captor.getValue().getFirstname());
        assertNull(captor.getValue().getComment());
    }

    @Test
    void createPicker_duplicateIgnoringCase_throwsConflict() {
        when(pickerRepository.existsByLastnameIgnoreCaseAndFirstnameIgnoreCase("DUPONT", "jean")).thenReturn(true);

        assertThrows(PickerAlreadyExistsException.class,
                () -> pickerService.createPicker(new CreatePicker().lastname("DUPONT").firstname("jean")));
        verify(pickerRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({ "'   ', Jean", "'', Jean", "Dupont, '   '", "Dupont, ''" })
    void createPicker_blankName_throwsBadRequest(String lastname, String firstname) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> pickerService.createPicker(new CreatePicker().lastname(lastname).firstname(firstname)));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(pickerRepository, never()).save(any());
    }

    @ParameterizedTest
    @CsvSource({ "'   ', Jean", "'', Jean", "Dupont, '   '", "Dupont, ''" })
    void updatePicker_blankName_throwsBadRequest(String lastname, String firstname) {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> pickerService.updatePicker(entity.getId(),
                        new UpdatePicker().lastname(lastname).firstname(firstname)));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(pickerRepository, never()).save(any());
    }

    @Test
    void updatePicker_keepingOwnName_succeeds() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(pickerRepository.save(any(PickerEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        pickerService.updatePicker(entity.getId(),
                new UpdatePicker().lastname("Dupont").firstname("Jean").comment("Rang 4"));

        verify(pickerRepository).existsByLastnameIgnoreCaseAndFirstnameIgnoreCaseAndIdNot("Dupont", "Jean",
                entity.getId());
        assertEquals("Rang 4", entity.getComment());
    }

    @Test
    void updatePicker_nameTakenByAnother_throwsConflict() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(pickerRepository.existsByLastnameIgnoreCaseAndFirstnameIgnoreCaseAndIdNot("Martin", "Paul",
                entity.getId())).thenReturn(true);

        assertThrows(PickerAlreadyExistsException.class, () -> pickerService.updatePicker(entity.getId(),
                new UpdatePicker().lastname("Martin").firstname("Paul")));
        verify(pickerRepository, never()).save(any());
    }

    @Test
    void updatePicker_unknownId_throwsNotFound() {
        UUID pickerId = UUID.randomUUID();
        when(pickerRepository.findById(pickerId)).thenReturn(Optional.empty());

        assertThrows(PickerNotFoundException.class, () -> pickerService.updatePicker(pickerId,
                new UpdatePicker().lastname("Martin").firstname("Paul")));
    }

    @Test
    void deletePicker_withBucket_throwsConflictAndKeepsPicker() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(bucketRepository.existsByPicker(entity)).thenReturn(true);

        assertThrows(PickerHasBucketsException.class, () -> pickerService.deletePicker(entity.getId()));
        verify(pickerRepository, never()).delete(any());
    }

    @Test
    void deletePicker_withoutBucket_deletes() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(bucketRepository.existsByPicker(entity)).thenReturn(false);

        pickerService.deletePicker(entity.getId());

        verify(pickerRepository).delete(entity);
    }

    @Test
    void deletePicker_unknownId_throwsNotFoundBeforeBucketCheck() {
        UUID pickerId = UUID.randomUUID();
        when(pickerRepository.findById(pickerId)).thenReturn(Optional.empty());

        assertThrows(PickerNotFoundException.class, () -> pickerService.deletePicker(pickerId));
        verify(bucketRepository, never()).existsByPicker(any());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = { "", "  " })
    void listPickers_withoutSort_ordersByLastnameThenFirstname(String sort) {
        assertEquals(Sort.by(Order.asc("lastname"), Order.asc("firstname"), Order.asc("id")), sortUsedFor(sort));
    }

    @Test
    void listPickers_firstnameDesc_addsTieBreakers() {
        assertEquals(Sort.by(Order.desc("firstname"), Order.asc("lastname"), Order.asc("id")),
                sortUsedFor("firstname,desc"));
    }

    @Test
    void listPickers_creationDateAsc_addsTieBreakers() {
        assertEquals(Sort.by(Order.asc("creationDate"), Order.asc("lastname"), Order.asc("firstname"),
                Order.asc("id")), sortUsedFor("creationDate,asc"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "lastname,DESC", " lastname , desc " })
    void listPickers_directionIsCaseInsensitiveAndTrimmed(String sort) {
        assertEquals(Sort.by(Order.desc("lastname"), Order.asc("firstname"), Order.asc("id")), sortUsedFor(sort));
    }

    @ParameterizedTest
    @ValueSource(strings = { "foo,asc", "lastname,up", "lastname", "lastname,asc,extra", "creationdate,asc",
            ",asc", "id,asc" })
    void listPickers_invalidSort_throwsBadRequest(String sort) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> pickerService.listPickers(0, 20, sort));
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(pickerRepository, never()).findAll(any(Pageable.class));
    }

    private Sort sortUsedFor(String sort) {
        when(pickerRepository.findAll(any(Pageable.class))).thenReturn(Page.empty());
        pickerService.listPickers(0, 20, sort);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pickerRepository).findAll(captor.capture());
        return captor.getValue().getSort();
    }

    private PickerEntity picker(String lastname, String firstname) {
        return PickerEntity.builder()
                .id(UUID.randomUUID())
                .lastname(lastname)
                .firstname(firstname)
                .creationDate(OffsetDateTime.now())
                .build();
    }
}
