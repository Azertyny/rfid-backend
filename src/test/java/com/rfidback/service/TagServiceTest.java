package com.rfidback.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;

class TagServiceTest {

    private TagRepository tagRepository;
    private RecordRepository recordRepository;
    private BucketRepository bucketRepository;
    private TagService tagService;

    @BeforeEach
    void setUp() {
        tagRepository = Mockito.mock(TagRepository.class);
        recordRepository = Mockito.mock(RecordRepository.class);
        bucketRepository = Mockito.mock(BucketRepository.class);
        tagService = new TagService(tagRepository, recordRepository, bucketRepository);
    }

    @Test
    void registerScan_persistsTagAndReturnsResponse() {
        ReaderEntity reader = ReaderEntity.builder().id(1).apitoken("token").name("Reader").build();
        UUID tagId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(tagRepository.findByUid("E2000017221101891400A23G")).thenReturn(Optional.empty());
        when(tagRepository.save(any(TagEntity.class))).thenAnswer(invocation -> {
            TagEntity entity = invocation.getArgument(0);
            entity.setId(tagId);
            return entity;
        });
        when(recordRepository.save(any(RecordEntity.class))).thenAnswer(invocation -> {
            RecordEntity entity = invocation.getArgument(0);
            entity.setCreationDate(OffsetDateTime.parse("2024-01-15T09:30:00Z"));
            return entity;
        });

        ScanTagRequest request = new ScanTagRequest().uid("E2000017221101891400A23G").isCompliant(false);

        ScanTagResponse response = tagService.registerScan(reader, request);

        assertEquals(request.getUid(), response.getUid());
        assertEquals(request.getIsCompliant(), response.getIsCompliant());
        assertEquals(OffsetDateTime.parse("2024-01-15T09:30:00Z"), response.getProcessedAt());
    }

    @Test
    void registerScan_withoutReaderFailsFast() {
        ScanTagRequest request = new ScanTagRequest().uid("E2000017221101891400A23G").isCompliant(true);
        assertThrows(IllegalArgumentException.class, () -> tagService.registerScan(null, request));
    }

    @Test
    void registerScan_setsPickerFromTagBucket() {
        ReaderEntity reader = ReaderEntity.builder().id(1).apitoken("token").name("Reader").build();
        PickerEntity picker = PickerEntity.builder().firstname("Jane").lastname("Doe").build();
        BucketEntity bucket = BucketEntity.builder().number(12).picker(picker).build();
        TagEntity tag = TagEntity.builder().uid("E2000017221101891400A23G").bucket(bucket).build();

        when(tagRepository.findByUid("E2000017221101891400A23G")).thenReturn(Optional.of(tag));
        when(recordRepository.save(any(RecordEntity.class))).thenAnswer(invocation -> {
            RecordEntity entity = invocation.getArgument(0);
            entity.setCreationDate(OffsetDateTime.parse("2024-01-15T09:30:00Z"));
            return entity;
        });

        ScanTagRequest request = new ScanTagRequest().uid("E2000017221101891400A23G").isCompliant(true);

        tagService.registerScan(reader, request);

        ArgumentCaptor<RecordEntity> captor = ArgumentCaptor.forClass(RecordEntity.class);
        verify(recordRepository).save(captor.capture());
        assertEquals(picker, captor.getValue().getPicker());
    }
}
