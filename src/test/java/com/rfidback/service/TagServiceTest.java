package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.never;
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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.exception.TagsInOtherBucketsException;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;

class TagServiceTest {

    private static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(10);

    private TagRepository tagRepository;
    private RecordRepository recordRepository;
    private BucketRepository bucketRepository;
    private RecordConformityChangeRepository recordConformityChangeRepository;
    private Clock clock;
    private TagService tagService;

    @BeforeEach
    void setUp() {
        tagRepository = Mockito.mock(TagRepository.class);
        recordRepository = Mockito.mock(RecordRepository.class);
        bucketRepository = Mockito.mock(BucketRepository.class);
        recordConformityChangeRepository = Mockito.mock(RecordConformityChangeRepository.class);
        clock = Clock.fixed(Instant.parse("2024-01-15T09:30:00Z"), ZoneOffset.UTC);
        tagService = new TagService(tagRepository, recordRepository, bucketRepository,
                recordConformityChangeRepository, clock, DUPLICATE_WINDOW);
    }

    @Test
    void registerScan_persistsTagAndReturnsResponse() {
        ReaderEntity reader = ReaderEntity.builder().id(UUID.randomUUID()).apitoken("token").name("Reader").build();
        UUID tagId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        when(tagRepository.findByUid("E2000017221101891400A23G")).thenReturn(Optional.empty());
        when(tagRepository.save(any(TagEntity.class))).thenAnswer(invocation -> {
            TagEntity entity = invocation.getArgument(0);
            entity.setId(tagId);
            return entity;
        });
        when(recordRepository.saveAndFlush(any(RecordEntity.class))).thenAnswer(invocation -> {
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
        ReaderEntity reader = ReaderEntity.builder().id(UUID.randomUUID()).apitoken("token").name("Reader").build();
        PickerEntity picker = PickerEntity.builder().firstname("Jane").lastname("Doe").build();
        BucketEntity bucket = BucketEntity.builder().number(12).picker(picker).build();
        TagEntity tag = TagEntity.builder().uid("E2000017221101891400A23G").bucket(bucket).build();

        when(tagRepository.findByUid("E2000017221101891400A23G")).thenReturn(Optional.of(tag));
        when(recordRepository.saveAndFlush(any(RecordEntity.class))).thenAnswer(invocation -> {
            RecordEntity entity = invocation.getArgument(0);
            entity.setCreationDate(OffsetDateTime.parse("2024-01-15T09:30:00Z"));
            return entity;
        });

        ScanTagRequest request = new ScanTagRequest().uid("E2000017221101891400A23G").isCompliant(true);

        tagService.registerScan(reader, request);

        ArgumentCaptor<RecordEntity> captor = ArgumentCaptor.forClass(RecordEntity.class);
        verify(recordRepository).saveAndFlush(captor.capture());
        assertEquals(picker, captor.getValue().getPicker());
    }

    // --- registerScan duplicates (spec 004, FR-008: same reader and tag within the window) ---

    private static final String DUPLICATE_UID = "E2000017221101891400A23G";
    private static final OffsetDateTime EXPECTED_CUTOFF = OffsetDateTime.parse("2024-01-15T09:29:50Z");

    private ReaderEntity scanReader() {
        return ReaderEntity.builder().id(UUID.randomUUID()).apitoken("token").name("Reader").build();
    }

    private TagEntity existingTag() {
        TagEntity tag = TagEntity.builder().id(UUID.randomUUID()).uid(DUPLICATE_UID).build();
        when(tagRepository.findByUid(DUPLICATE_UID)).thenReturn(Optional.of(tag));
        return tag;
    }

    private RecordEntity recentRecord(ReaderEntity reader, TagEntity tag, boolean compliant) {
        RecordEntity record = RecordEntity.builder().id(UUID.randomUUID()).reader(reader).tag(tag)
                .compliant(compliant).creationDate(OffsetDateTime.parse("2024-01-15T09:29:55Z")).build();
        when(recordRepository.findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(
                reader, tag, EXPECTED_CUTOFF)).thenReturn(Optional.of(record));
        return record;
    }

    private void stubNewRecordSave() {
        when(recordRepository.saveAndFlush(any(RecordEntity.class))).thenAnswer(invocation -> {
            RecordEntity entity = invocation.getArgument(0);
            entity.setCreationDate(OffsetDateTime.parse("2024-01-15T09:30:00Z"));
            return entity;
        });
    }

    @Test
    void registerScan_duplicateWithinWindow_returnsExistingRecordWithoutSaving() {
        ReaderEntity reader = scanReader();
        TagEntity tag = existingTag();
        recentRecord(reader, tag, true);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(recordRepository).findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(
                any(ReaderEntity.class), any(TagEntity.class), cutoff.capture());
        assertEquals(EXPECTED_CUTOFF, cutoff.getValue());
        assertEquals(DUPLICATE_UID, response.getUid());
        assertEquals(true, response.getIsCompliant());
        assertEquals(OffsetDateTime.parse("2024-01-15T09:29:55Z"), response.getProcessedAt());
        assertEquals("Duplicate read ignored", response.getMessage());
    }

    @Test
    void registerScan_nonCompliantRepeatOfCompliantRecord_lowersIt() {
        ReaderEntity reader = scanReader();
        TagEntity tag = existingTag();
        RecordEntity record = recentRecord(reader, tag, true);
        when(recordConformityChangeRepository.existsByRecord(record)).thenReturn(false);
        when(recordRepository.saveAndFlush(record)).thenReturn(record);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(false));

        ArgumentCaptor<RecordEntity> saved = ArgumentCaptor.forClass(RecordEntity.class);
        verify(recordRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue()).isSameAs(record);
        assertThat(saved.getValue().isCompliant()).isFalse();
        assertEquals(false, response.getIsCompliant());
        assertEquals(OffsetDateTime.parse("2024-01-15T09:29:55Z"), response.getProcessedAt());
        assertEquals("Duplicate read ignored", response.getMessage());
    }

    @Test
    void registerScan_nonCompliantRepeatOfChangedRecord_keepsIt() {
        ReaderEntity reader = scanReader();
        TagEntity tag = existingTag();
        RecordEntity record = recentRecord(reader, tag, true);
        when(recordConformityChangeRepository.existsByRecord(record)).thenReturn(true);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(false));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        assertThat(record.isCompliant()).isTrue();
        assertEquals(true, response.getIsCompliant());
        assertEquals("Duplicate read ignored", response.getMessage());
    }

    @Test
    void registerScan_compliantRepeat_skipsChangeLookup() {
        ReaderEntity reader = scanReader();
        TagEntity tag = existingTag();
        recentRecord(reader, tag, true);

        tagService.registerScan(reader, new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordConformityChangeRepository, never()).existsByRecord(any());
    }

    @Test
    void registerScan_compliantRepeatOfNonCompliantRecord_keepsIt() {
        ReaderEntity reader = scanReader();
        TagEntity tag = existingTag();
        RecordEntity record = recentRecord(reader, tag, false);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        assertThat(record.isCompliant()).isFalse();
        assertEquals(false, response.getIsCompliant());
        assertEquals("Duplicate read ignored", response.getMessage());
    }

    @Test
    void registerScan_noRecordInWindow_createsRecord() {
        ReaderEntity reader = scanReader();
        existingTag();
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository).saveAndFlush(any(RecordEntity.class));
        assertEquals(null, response.getMessage());
    }

    @Test
    void registerScan_newTag_skipsDuplicateLookup() {
        ReaderEntity reader = scanReader();
        when(tagRepository.findByUid(DUPLICATE_UID)).thenReturn(Optional.empty());
        when(tagRepository.save(any(TagEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubNewRecordSave();

        tagService.registerScan(reader, new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(
                any(), any(), any());
        verify(recordRepository).saveAndFlush(any(RecordEntity.class));
    }

    @Test
    void registerScan_zeroWindow_skipsDuplicateLookup() {
        TagService withoutDeduplication = new TagService(tagRepository, recordRepository, bucketRepository,
                recordConformityChangeRepository, clock, Duration.ZERO);
        ReaderEntity reader = scanReader();
        existingTag();
        stubNewRecordSave();

        withoutDeduplication.registerScan(reader, new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(
                any(), any(), any());
        verify(recordRepository).saveAndFlush(any(RecordEntity.class));
    }

    // --- registerTagsForBucket (spec 003: add-only, move confirmation, counts) ---

    private BucketEntity existingBucket(int number) {
        BucketEntity bucket = BucketEntity.builder().id(UUID.randomUUID()).number(number).build();
        when(bucketRepository.findByNumber(number)).thenReturn(Optional.of(bucket));
        return bucket;
    }

    @SuppressWarnings("unchecked")
    private List<TagEntity> savedTags() {
        ArgumentCaptor<List<TagEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(tagRepository).saveAll(captor.capture());
        return captor.getValue();
    }

    @Test
    void registerTagsForBucket_addsTagsWithoutDetachingExistingOnes() {
        BucketEntity bucket = existingBucket(12);
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of());
        when(tagRepository.countByBucket(bucket)).thenReturn(2L);

        RegisterTagsResponse response = tagService.registerTagsForBucket(12, List.of("T2"), false);

        assertEquals(12, response.getBucketNumber());
        assertEquals(1, response.getRegisteredCount());
        assertEquals(2, response.getTotalCount());
        verify(tagRepository, never()).findAllByBucket(any());
        assertThat(savedTags()).singleElement().satisfies(tag -> {
            assertEquals("T2", tag.getUid());
            assertEquals(bucket, tag.getBucket());
        });
    }

    @Test
    void registerTagsForBucket_createsBucketWhenUnknown() {
        when(bucketRepository.findByNumber(12)).thenReturn(Optional.empty());
        when(bucketRepository.save(any(BucketEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of());

        tagService.registerTagsForBucket(12, List.of("T1"), false);

        ArgumentCaptor<BucketEntity> captor = ArgumentCaptor.forClass(BucketEntity.class);
        verify(bucketRepository).save(captor.capture());
        assertEquals(12, captor.getValue().getNumber());
        assertEquals(12, savedTags().get(0).getBucket().getNumber());
    }

    @Test
    void registerTagsForBucket_dedupsAndIgnoresBlankUids() {
        existingBucket(12);
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of());

        RegisterTagsResponse response = tagService.registerTagsForBucket(12, List.of(" A ", "A", "", "B"), false);

        assertEquals(2, response.getRegisteredCount());
        assertThat(savedTags()).extracting(TagEntity::getUid).containsExactly("A", "B");
    }

    @Test
    void registerTagsForBucket_moreThan100Uids_throws400AndWritesNothing() {
        List<String> uids = java.util.stream.IntStream.range(0, 101).mapToObj(i -> "T" + i).toList();

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> tagService.registerTagsForBucket(12, uids, false));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(tagRepository, never()).saveAll(anyIterable());
        verify(bucketRepository, never()).save(any());
    }

    @Test
    void registerTagsForBucket_allBlankUids_throws400AndWritesNothing() {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> tagService.registerTagsForBucket(12, List.of(" ", ""), false));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(tagRepository, never()).saveAll(anyIterable());
        verify(bucketRepository, never()).save(any());
    }

    @Test
    void registerTagsForBucket_tagInOtherBucket_withoutConfirmation_throwsConflictAndWritesNothing() {
        BucketEntity otherBucket = BucketEntity.builder().id(UUID.randomUUID()).number(7).build();
        TagEntity tag = TagEntity.builder().uid("T1").bucket(otherBucket).build();
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of(tag));

        TagsInOtherBucketsException exception = assertThrows(TagsInOtherBucketsException.class,
                () -> tagService.registerTagsForBucket(12, List.of("T1", "T2"), false));

        assertThat(exception.getTags()).singleElement().satisfies(conflict -> {
            assertEquals("T1", conflict.getUid());
            assertEquals(7, conflict.getBucketNumber());
        });
        assertEquals(otherBucket, tag.getBucket());
        verify(tagRepository, never()).saveAll(anyIterable());
        verify(bucketRepository, never()).save(any());
    }

    @Test
    void registerTagsForBucket_tagInOtherBucket_withConfirmation_movesIt() {
        BucketEntity bucket = existingBucket(12);
        BucketEntity otherBucket = BucketEntity.builder().id(UUID.randomUUID()).number(7).build();
        TagEntity tag = TagEntity.builder().uid("T1").bucket(otherBucket).build();
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of(tag));

        tagService.registerTagsForBucket(12, List.of("T1"), true);

        assertEquals(bucket, tag.getBucket());
        assertThat(savedTags()).containsExactly(tag);
    }

    @Test
    void registerTagsForBucket_tagAlreadyInSameBucket_isNotAConflict() {
        BucketEntity bucket = existingBucket(12);
        TagEntity tag = TagEntity.builder().uid("T1").bucket(bucket).build();
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of(tag));

        RegisterTagsResponse response = tagService.registerTagsForBucket(12, List.of("T1"), false);

        assertEquals(1, response.getRegisteredCount());
        assertThat(savedTags()).containsExactly(tag);
    }
}
