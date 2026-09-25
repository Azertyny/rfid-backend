package com.rfidback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import org.mockito.Mockito;
import org.springframework.data.domain.Sort;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.exception.BucketNotFoundException;
import com.rfidback.exception.PickerNotFoundException;
import com.rfidback.generated.model.BucketWithTagsAndPicker;
import com.rfidback.generated.model.BucketsList;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;
import com.rfidback.repository.TagRepository;

/** Unit tests of the bucket list, detail and (un)assignment rules (spec 006). */
class BucketServiceTest {

    private BucketRepository bucketRepository;
    private TagRepository tagRepository;
    private PickerRepository pickerRepository;
    private BucketService bucketService;

    @BeforeEach
    void setUp() {
        bucketRepository = Mockito.mock(BucketRepository.class);
        tagRepository = Mockito.mock(TagRepository.class);
        pickerRepository = Mockito.mock(PickerRepository.class);
        bucketService = new BucketService(bucketRepository, tagRepository, pickerRepository);
    }

    @Test
    void listBuckets_sortsByNumberAndGroupsTags() {
        PickerEntity picker = picker("Dupont", "Jean", "Rang 4");
        BucketEntity first = bucket(1, picker);
        BucketEntity second = bucket(2, null);
        when(bucketRepository.findAll(Sort.by(Sort.Order.asc("number")))).thenReturn(List.of(first, second));
        when(tagRepository.findAllByBucketIn(List.of(first, second)))
                .thenReturn(List.of(tag("UID-1", first), tag("UID-2", first)));

        BucketsList response = bucketService.listBuckets();

        verify(bucketRepository).findAll(Sort.by(Sort.Order.asc("number")));
        BucketWithTagsAndPicker assigned = response.getBuckets().get(0);
        assertEquals(1, assigned.getNumber());
        assertEquals(List.of("UID-1", "UID-2"), assigned.getTags());
        assertEquals("Dupont", assigned.getPicker().getLastname());
        assertEquals("Jean", assigned.getPicker().getFirstname());
        assertEquals("Rang 4", assigned.getPicker().getComment());
        BucketWithTagsAndPicker unassigned = response.getBuckets().get(1);
        assertEquals(List.of(), unassigned.getTags());
        assertNull(unassigned.getPicker());
    }

    @Test
    void listBuckets_noBucket_doesNotQueryTags() {
        when(bucketRepository.findAll(any(Sort.class))).thenReturn(List.of());

        assertEquals(List.of(), bucketService.listBuckets().getBuckets());
        verify(tagRepository, never()).findAllByBucketIn(any());
    }

    @Test
    void getBucket_returnsTagsAndPicker() {
        BucketEntity bucket = bucket(3, picker("Martin", "Paul", null));
        when(bucketRepository.findById(bucket.getId())).thenReturn(Optional.of(bucket));
        when(tagRepository.findAllByBucket(bucket)).thenReturn(List.of(tag("UID-3", bucket)));

        BucketWithTagsAndPicker response = bucketService.getBucket(bucket.getId());

        assertEquals(3, response.getNumber());
        assertEquals(List.of("UID-3"), response.getTags());
        assertEquals("Martin", response.getPicker().getLastname());
    }

    @Test
    void getBucket_unknown_throwsBucketNotFound() {
        UUID bucketId = UUID.randomUUID();
        when(bucketRepository.findById(bucketId)).thenReturn(Optional.empty());

        assertThrows(BucketNotFoundException.class, () -> bucketService.getBucket(bucketId));
    }

    @Test
    void assignBucketToPicker_setsPickerAndSaves() {
        BucketEntity bucket = bucket(4, null);
        PickerEntity picker = picker("Dupont", "Jean", null);
        when(bucketRepository.findById(bucket.getId())).thenReturn(Optional.of(bucket));
        when(pickerRepository.findById(picker.getId())).thenReturn(Optional.of(picker));

        bucketService.assignBucketToPicker(bucket.getId(), picker.getId());

        assertSame(picker, bucket.getPicker());
        verify(bucketRepository).save(bucket);
    }

    @Test
    void assignBucketToPicker_alreadyAssigned_replacesPicker() {
        PickerEntity previous = picker("Dupont", "Jean", null);
        PickerEntity next = picker("Martin", "Paul", null);
        BucketEntity bucket = bucket(5, previous);
        when(bucketRepository.findById(bucket.getId())).thenReturn(Optional.of(bucket));
        when(pickerRepository.findById(next.getId())).thenReturn(Optional.of(next));

        bucketService.assignBucketToPicker(bucket.getId(), next.getId());

        assertSame(next, bucket.getPicker());
        verify(bucketRepository).save(bucket);
    }

    @Test
    void assignBucketToPicker_unknownBucket_throwsBucketNotFound() {
        UUID bucketId = UUID.randomUUID();
        when(bucketRepository.findById(bucketId)).thenReturn(Optional.empty());

        assertThrows(BucketNotFoundException.class,
                () -> bucketService.assignBucketToPicker(bucketId, UUID.randomUUID()));
        verify(pickerRepository, never()).findById(any());
    }

    @Test
    void assignBucketToPicker_unknownPicker_throwsPickerNotFoundAndDoesNotSave() {
        BucketEntity bucket = bucket(6, null);
        UUID pickerId = UUID.randomUUID();
        when(bucketRepository.findById(bucket.getId())).thenReturn(Optional.of(bucket));
        when(pickerRepository.findById(pickerId)).thenReturn(Optional.empty());

        assertThrows(PickerNotFoundException.class,
                () -> bucketService.assignBucketToPicker(bucket.getId(), pickerId));
        assertNull(bucket.getPicker());
        verify(bucketRepository, never()).save(any());
    }

    @Test
    void unassignBucketFromPicker_clearsPickerAndSaves() {
        BucketEntity bucket = bucket(7, picker("Dupont", "Jean", null));
        when(bucketRepository.findById(bucket.getId())).thenReturn(Optional.of(bucket));

        bucketService.unassignBucketFromPicker(bucket.getId());

        assertNull(bucket.getPicker());
        verify(bucketRepository).save(bucket);
    }

    @Test
    void unassignBucketFromPicker_noPicker_stillSucceeds() {
        BucketEntity bucket = bucket(8, null);
        when(bucketRepository.findById(bucket.getId())).thenReturn(Optional.of(bucket));

        bucketService.unassignBucketFromPicker(bucket.getId());

        assertNull(bucket.getPicker());
    }

    @Test
    void unassignBucketFromPicker_unknownBucket_throwsBucketNotFound() {
        UUID bucketId = UUID.randomUUID();
        when(bucketRepository.findById(bucketId)).thenReturn(Optional.empty());

        assertThrows(BucketNotFoundException.class, () -> bucketService.unassignBucketFromPicker(bucketId));
        verify(bucketRepository, never()).save(any());
    }

    private PickerEntity picker(String lastname, String firstname, String comment) {
        return PickerEntity.builder()
                .id(UUID.randomUUID())
                .lastname(lastname)
                .firstname(firstname)
                .comment(comment)
                .creationDate(OffsetDateTime.now())
                .build();
    }

    private BucketEntity bucket(int number, PickerEntity picker) {
        return BucketEntity.builder()
                .id(UUID.randomUUID())
                .number(number)
                .picker(picker)
                .creationDate(OffsetDateTime.now())
                .build();
    }

    private TagEntity tag(String uid, BucketEntity bucket) {
        return TagEntity.builder().id(UUID.randomUUID()).uid(uid).bucket(bucket).build();
    }
}
