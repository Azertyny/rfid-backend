package com.rfidback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.Sort.Order;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.exception.PickerAlreadyExistsException;
import com.rfidback.exception.PickerHasBucketsException;
import com.rfidback.exception.PickerNotFoundException;
import com.rfidback.generated.model.CreatePicker;
import com.rfidback.generated.model.Picker;
import com.rfidback.generated.model.UpdatePicker;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;

class PickerServiceTest {

    private static final Instant DELETED_AT = Instant.parse("2026-10-11T08:00:00Z");

    private PickerRepository pickerRepository;
    private BucketRepository bucketRepository;
    private PickerService pickerService;

    @BeforeEach
    void setUp() {
        pickerRepository = Mockito.mock(PickerRepository.class);
        bucketRepository = Mockito.mock(BucketRepository.class);
        pickerService = new PickerService(pickerRepository, bucketRepository, Clock.fixed(DELETED_AT, ZoneOffset.UTC));
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
        when(pickerRepository.findWithLockById(entity.getId())).thenReturn(Optional.of(entity));
        when(bucketRepository.existsByPicker(entity)).thenReturn(true);

        assertThrows(PickerHasBucketsException.class, () -> pickerService.deletePicker(entity.getId()));
        assertFalse(entity.isDeleted());
        verify(pickerRepository, never()).save(any());
    }

    @Test
    void deletePicker_withoutBucket_setsDeletedAtAndDeletesNothing() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findWithLockById(entity.getId())).thenReturn(Optional.of(entity));
        when(bucketRepository.existsByPicker(entity)).thenReturn(false);

        pickerService.deletePicker(entity.getId());

        assertEquals(DELETED_AT, entity.getDeletedAt().toInstant());
        verify(pickerRepository).save(entity);
        verify(pickerRepository, never()).delete(any());
    }

    @Test
    void deletePicker_unknownId_throwsNotFoundBeforeBucketCheck() {
        UUID pickerId = UUID.randomUUID();
        when(pickerRepository.findWithLockById(pickerId)).thenReturn(Optional.empty());

        assertThrows(PickerNotFoundException.class, () -> pickerService.deletePicker(pickerId));
        verify(bucketRepository, never()).existsByPicker(any());
    }

    @Test
    void deletePicker_alreadyDeleted_throwsNotFoundBeforeBucketCheck() {
        PickerEntity entity = deleted(picker("Dupont", "Jean"));
        when(pickerRepository.findWithLockById(entity.getId())).thenReturn(Optional.of(entity));

        assertThrows(PickerNotFoundException.class, () -> pickerService.deletePicker(entity.getId()));
        verify(bucketRepository, never()).existsByPicker(any());
        verify(pickerRepository, never()).save(any());
    }

    @Test
    void getPicker_deleted_throwsNotFound() {
        PickerEntity entity = deleted(picker("Dupont", "Jean"));
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThrows(PickerNotFoundException.class, () -> pickerService.getPicker(entity.getId()));
    }

    @Test
    void updatePicker_deleted_throwsNotFound() {
        PickerEntity entity = deleted(picker("Dupont", "Jean"));
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));

        assertThrows(PickerNotFoundException.class, () -> pickerService.updatePicker(entity.getId(),
                new UpdatePicker().lastname("Martin").firstname("Paul")));
        verify(pickerRepository, never()).save(any());
    }

    @Test
    void createPicker_nameOfADeletedPicker_throwsConflict() {
        // The duplicate check sees deleted pickers: their name stays taken (FR-010).
        when(pickerRepository.existsByLastnameIgnoreCaseAndFirstnameIgnoreCase("Dupont", "Jean")).thenReturn(true);

        assertThrows(PickerAlreadyExistsException.class,
                () -> pickerService.createPicker(new CreatePicker().lastname("Dupont").firstname("Jean")));
        verify(pickerRepository, never()).save(any());
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
        verify(pickerRepository, never()).findAllByDeletedAtIsNull(any(Pageable.class));
    }

    @Test
    void listPickers_pickerWithTwoBuckets_returnsBothNumbersSorted() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findAllByDeletedAtIsNull(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));
        when(bucketRepository.findAllByPickerIn(List.of(entity)))
                .thenReturn(List.of(bucket(47, entity), bucket(12, entity)));

        Picker picker = pickerService.listPickers(0, 20, null).getContent().get(0);

        assertEquals(List.of(12, 47), picker.getBucketNumbers());
    }

    @Test
    void listPickers_pickerWithoutBucket_returnsEmptyList() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findAllByDeletedAtIsNull(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(entity)));
        when(bucketRepository.findAllByPickerIn(List.of(entity))).thenReturn(List.of());

        Picker picker = pickerService.listPickers(0, 20, null).getContent().get(0);

        assertEquals(List.of(), picker.getBucketNumbers());
    }

    @Test
    void getPicker_twoBuckets_returnsBothNumbers() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(bucketRepository.findAllByPickerOrderByNumberAsc(entity))
                .thenReturn(List.of(bucket(12, entity), bucket(47, entity)));

        assertEquals(List.of(12, 47), pickerService.getPicker(entity.getId()).getBucketNumbers());
    }

    @Test
    void getPicker_noBucket_returnsEmptyList() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(bucketRepository.findAllByPickerOrderByNumberAsc(entity)).thenReturn(List.of());

        assertEquals(List.of(), pickerService.getPicker(entity.getId()).getBucketNumbers());
    }

    @Test
    void updatePicker_returnsBucketNumbers() {
        PickerEntity entity = picker("Dupont", "Jean");
        when(pickerRepository.findById(entity.getId())).thenReturn(Optional.of(entity));
        when(pickerRepository.save(any(PickerEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(bucketRepository.findAllByPickerOrderByNumberAsc(entity)).thenReturn(List.of(bucket(5, entity)));

        Picker picker = pickerService.updatePicker(entity.getId(),
                new UpdatePicker().lastname("Dupont").firstname("Jean"));

        assertEquals(List.of(5), picker.getBucketNumbers());
    }

    private Sort sortUsedFor(String sort) {
        when(pickerRepository.findAllByDeletedAtIsNull(any(Pageable.class))).thenReturn(Page.empty());
        pickerService.listPickers(0, 20, sort);
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(pickerRepository).findAllByDeletedAtIsNull(captor.capture());
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

    private PickerEntity deleted(PickerEntity picker) {
        picker.setDeletedAt(OffsetDateTime.parse("2026-10-01T08:00:00Z"));
        return picker;
    }

    private BucketEntity bucket(int number, PickerEntity picker) {
        return BucketEntity.builder().id(UUID.randomUUID()).number(number).picker(picker).build();
    }
}
