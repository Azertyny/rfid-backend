package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordAnomaly;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.exception.RegistrationNotConfirmedException;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;

class TagServiceTest {

    private static final Duration DUPLICATE_WINDOW = Duration.ofSeconds(10);
    private static final Duration CRATE_WINDOW = Duration.ofSeconds(2);
    private static final Duration REREAD_WINDOW = Duration.ofMinutes(30);

    private TagRepository tagRepository;
    private RecordRepository recordRepository;
    private BucketRepository bucketRepository;
    private RecordConformityChangeRepository recordConformityChangeRepository;
    private ReferenceTagList referenceTagList;
    private ReaderRepository readerRepository;
    private LineActivityService lineActivityService;
    private Clock clock;
    private TagService tagService;

    @BeforeEach
    void setUp() {
        tagRepository = Mockito.mock(TagRepository.class);
        recordRepository = Mockito.mock(RecordRepository.class);
        bucketRepository = Mockito.mock(BucketRepository.class);
        recordConformityChangeRepository = Mockito.mock(RecordConformityChangeRepository.class);
        // A mock answers isOffList = false: every uid is in the list unless a test says otherwise.
        referenceTagList = Mockito.mock(ReferenceTagList.class);
        // No reader found: the scan carries no activity unless a test says otherwise (spec 012).
        readerRepository = Mockito.mock(ReaderRepository.class);
        lineActivityService = Mockito.mock(LineActivityService.class);
        clock = Clock.fixed(Instant.parse("2024-01-15T09:30:00Z"), ZoneOffset.UTC);
        // The bucket of a counted tag still exists unless a test deletes it meanwhile (spec 006, research R12). Only
        // its existence is read: the picker comes from the tag's own bucket.
        when(bucketRepository.findWithLockById(any())).thenAnswer(
                invocation -> Optional.of(BucketEntity.builder().id(invocation.getArgument(0)).build()));
        tagService = tagService(DUPLICATE_WINDOW, CRATE_WINDOW);
    }

    private TagService tagService(Duration duplicateWindow, Duration crateWindow) {
        return tagService(duplicateWindow, crateWindow, REREAD_WINDOW);
    }

    /** The two delays an Administrateur can change come from ScanSettingsService at every scan (spec 015, FR-008). */
    private TagService tagService(Duration duplicateWindow, Duration crateWindow, Duration rereadWindow) {
        ScanSettingsService scanSettingsService = Mockito.mock(ScanSettingsService.class);
        when(scanSettingsService.windows()).thenReturn(new ScanSettingsService.Windows(rereadWindow, crateWindow));
        return new TagService(tagRepository, recordRepository, bucketRepository, recordConformityChangeRepository,
                referenceTagList, readerRepository, lineActivityService, scanSettingsService, clock, duplicateWindow);
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

    // --- registerScan rules (spec 015, research R3): 1 same pass, 2 Cheat, 3 recent read, 4 Doublon / perdu, 5 count ---

    private static final String DUPLICATE_UID = "E2000017221101891400A23G";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2024-01-15T09:30:00Z");

    private ReaderEntity scanReader() {
        return ReaderEntity.builder().id(UUID.randomUUID()).apitoken("token").name("Reader").build();
    }

    private TagEntity existingTag() {
        TagEntity tag = TagEntity.builder().id(UUID.randomUUID()).uid(DUPLICATE_UID).build();
        when(tagRepository.findByUid(DUPLICATE_UID)).thenReturn(Optional.of(tag));
        return tag;
    }

    /** The record that counted the tag, found by id and row-locked by the scan (research R8). */
    private RecordEntity countedRecord(ReaderEntity reader, String createdAt, String lastReadAt, boolean compliant) {
        RecordEntity record = RecordEntity.builder().id(UUID.randomUUID()).reader(reader).compliant(compliant)
                .creationDate(OffsetDateTime.parse(createdAt))
                .lastReadAt(lastReadAt == null ? null : OffsetDateTime.parse(lastReadAt))
                .build();
        when(recordRepository.findWithLockById(record.getId())).thenReturn(Optional.of(record));
        return record;
    }

    /** An existing tag, out of its bucket, counted by {@code record}. */
    private TagEntity countedTag(RecordEntity record) {
        TagEntity tag = existingTag();
        tag.setCountedRecord(record);
        record.setTag(tag);
        return tag;
    }

    /** A record counted 60 s ago by {@code reader}, last read 5 s ago: the next read is the same pass. */
    private RecordEntity samePassRecord(ReaderEntity reader, boolean compliant) {
        RecordEntity record = countedRecord(reader, "2024-01-15T09:29:00Z", "2024-01-15T09:29:55Z", compliant);
        countedTag(record);
        return record;
    }

    private void stubNewRecordSave() {
        when(recordRepository.saveAndFlush(any(RecordEntity.class))).thenAnswer(invocation -> {
            RecordEntity entity = invocation.getArgument(0);
            if (entity.getCreationDate() == null) {
                entity.setCreationDate(NOW);
            }
            return entity;
        });
    }

    private RecordEntity savedRecord() {
        ArgumentCaptor<RecordEntity> saved = ArgumentCaptor.forClass(RecordEntity.class);
        verify(recordRepository).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    @Test
    void registerScan_carriesTheLinesEffectiveActivity_readFromTheDatabase() {
        ReaderEntity authenticated = scanReader();
        ReaderEntity fresh = ReaderEntity.builder().id(authenticated.getId()).name(authenticated.getName()).build();
        ActivityEntity fraise = ActivityEntity.builder().id(UUID.randomUUID()).name("Fraise").build();
        when(readerRepository.findWithCurrentActivityById(authenticated.getId())).thenReturn(Optional.of(fresh));
        when(lineActivityService.effectiveActivity(fresh)).thenReturn(fraise);
        when(tagRepository.save(any(TagEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubNewRecordSave();

        tagService.registerScan(authenticated, new ScanTagRequest().uid("E2000017221101891400A23G").isCompliant(true));

        assertThat(savedRecord().getActivity()).isSameAs(fraise);
    }

    // Rule 4: count (FR-003, FR-009)

    @Test
    void registerScan_tagInBucket_countsIt_andTheTagLeavesTheBucket() {
        ReaderEntity reader = scanReader();
        PickerEntity picker = PickerEntity.builder().firstname("Jane").lastname("Doe").build();
        BucketEntity bucket = BucketEntity.builder().id(UUID.randomUUID()).number(12).picker(picker).build();
        TagEntity tag = existingTag();
        tag.setBucket(bucket);
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        RecordEntity saved = savedRecord();
        assertThat(saved.getPicker()).isSameAs(picker);
        assertThat(saved.getBucket()).isSameAs(bucket);
        assertEquals(NOW, saved.getLastReadAt());
        assertThat(tag.getBucket()).isNull();
        assertThat(tag.getCountedRecord()).isSameAs(saved);
        assertEquals(null, response.getMessage());
    }

    @Test
    void registerScan_newTag_skipsTheCountedRecordLookup() {
        ReaderEntity reader = scanReader();
        when(tagRepository.findByUid(DUPLICATE_UID)).thenReturn(Optional.empty());
        when(tagRepository.save(any(TagEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        stubNewRecordSave();

        tagService.registerScan(reader, new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).findWithLockById(any());
        assertThat(savedRecord().getTag().getCountedRecord()).isNotNull();
    }

    // Rule 1: same pass, a sliding window from the last read (FR-008, research R7)

    @Test
    void registerScan_samePass_measuredFromTheLastRead() {
        ReaderEntity reader = scanReader();
        RecordEntity record = samePassRecord(reader, true);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        assertEquals(NOW, record.getLastReadAt());
        assertEquals(DUPLICATE_UID, response.getUid());
        assertEquals(true, response.getIsCompliant());
        assertEquals(OffsetDateTime.parse("2024-01-15T09:29:00Z"), response.getProcessedAt());
        assertEquals("Duplicate read ignored", response.getMessage());
    }

    @Test
    void registerScan_samePass_lastReadNull_isNotTheSamePass() {
        ReaderEntity reader = scanReader();
        countedTag(countedRecord(reader, "2024-01-14T17:00:00Z", null, true));
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertThat(savedRecord().getCreationDate()).isEqualTo(NOW);
        assertEquals("Tag already scanned today", response.getMessage());
    }

    @Test
    void registerScan_zeroWindow_neverTheSamePass() {
        // The re-read rule (spec 015) is off too, so the same-pass rule is tested alone.
        TagService withoutDeduplication = tagService(Duration.ZERO, CRATE_WINDOW, Duration.ZERO);
        ReaderEntity reader = scanReader();
        countedTag(countedRecord(reader, "2024-01-14T17:00:00Z", "2024-01-15T09:29:59Z", true));
        stubNewRecordSave();

        ScanTagResponse response = withoutDeduplication.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertThat(savedRecord().getCreationDate()).isEqualTo(NOW);
        assertEquals("Tag already scanned today", response.getMessage());
    }

    @Test
    void registerScan_duplicate_leavesTheExistingRecordsActivity() {
        ReaderEntity reader = scanReader();
        RecordEntity existing = samePassRecord(reader, true);
        ActivityEntity fraise = ActivityEntity.builder().id(UUID.randomUUID()).name("Fraise").build();
        existing.setActivity(fraise);

        tagService.registerScan(reader, new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertThat(existing.getActivity()).isSameAs(fraise);
        verify(lineActivityService, never()).effectiveActivity(any());
    }

    @Test
    void registerScan_nonCompliantRepeatOfCompliantRecord_lowersIt() {
        ReaderEntity reader = scanReader();
        RecordEntity record = samePassRecord(reader, true);
        when(recordConformityChangeRepository.existsByRecord(record)).thenReturn(false);
        when(recordRepository.saveAndFlush(record)).thenReturn(record);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(false));

        assertThat(savedRecord()).isSameAs(record);
        assertThat(record.isCompliant()).isFalse();
        assertEquals(false, response.getIsCompliant());
        assertEquals(OffsetDateTime.parse("2024-01-15T09:29:00Z"), response.getProcessedAt());
        assertEquals("Duplicate read ignored", response.getMessage());
    }

    @Test
    void registerScan_nonCompliantRepeatOfChangedRecord_keepsIt() {
        ReaderEntity reader = scanReader();
        RecordEntity record = samePassRecord(reader, true);
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
        samePassRecord(reader, true);

        tagService.registerScan(reader, new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordConformityChangeRepository, never()).existsByRecord(any());
    }

    @Test
    void registerScan_compliantRepeatOfNonCompliantRecord_keepsIt() {
        ReaderEntity reader = scanReader();
        RecordEntity record = samePassRecord(reader, false);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        assertThat(record.isCompliant()).isFalse();
        assertEquals(false, response.getIsCompliant());
        assertEquals("Duplicate read ignored", response.getMessage());
    }

    // Rule 4: rescan of a counted tag, on any day → Doublon / perdu, no picker (spec 015, FR-005, research R5)

    @Test
    void registerScan_rescanToday_afterTheWindow_createsANonCompliantRecordWithoutPicker() {
        ReaderEntity reader = scanReader();
        RecordEntity first = countedRecord(reader, "2024-01-15T08:00:00Z", "2024-01-15T08:00:00Z", true);
        TagEntity tag = countedTag(first);
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        RecordEntity saved = savedRecord();
        assertThat(saved).isNotSameAs(first);
        assertThat(saved.isCompliant()).isFalse();
        assertThat(saved.getAnomaly()).isEqualTo(RecordAnomaly.DOUBLON_PERDU);
        assertThat(saved.getPicker()).isNull();
        assertThat(saved.getBucket()).isNull();
        assertEquals(NOW, saved.getLastReadAt());
        assertThat(tag.getCountedRecord()).isSameAs(saved);
        assertThat(first.isCompliant()).isTrue();
        assertThat(first.getAnomaly()).isNull();
        assertEquals(OffsetDateTime.parse("2024-01-15T08:00:00Z"), first.getLastReadAt());
        assertEquals(false, response.getIsCompliant());
        assertEquals(NOW, response.getProcessedAt());
        assertEquals("Tag already scanned today", response.getMessage());
    }

    @Test
    void registerScan_rescanToday_byAnotherReader_afterTheRereadDelay_isKO() {
        countedTag(countedRecord(scanReader(), "2024-01-15T08:00:00Z", "2024-01-15T08:59:00Z", true));
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(scanReader(),
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertThat(savedRecord().isCompliant()).isFalse();
        assertEquals("Tag already scanned today", response.getMessage());
    }

    // Rule 3 (spec 015, FR-003): a counted tag read again within the re-read delay of its last read is ignored

    @Test
    void registerScan_countedTagReadAgainByAnotherReaderWithinTheDelay_isIgnored() {
        RecordEntity first = countedRecord(scanReader(), "2024-01-15T09:25:00Z", "2024-01-15T09:25:00Z", true);
        TagEntity tag = countedTag(first);

        ScanTagResponse response = tagService.registerScan(scanReader(),
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(false));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        assertThat(first.isCompliant()).isTrue();
        assertEquals(OffsetDateTime.parse("2024-01-15T09:25:00Z"), first.getLastReadAt());
        assertThat(tag.getCountedRecord()).isSameAs(first);
        assertEquals(NOW, tag.getLastReadAt());
        assertEquals(DUPLICATE_UID, response.getUid());
        assertEquals(true, response.getIsCompliant());
        assertEquals(OffsetDateTime.parse("2024-01-15T09:25:00Z"), response.getProcessedAt());
        assertEquals("Recent read ignored", response.getMessage());
    }

    @Test
    void registerScan_rereadDelay_slidesFromTheTagsLastRead() {
        RecordEntity first = countedRecord(scanReader(), "2024-01-15T07:00:00Z", "2024-01-15T07:00:00Z", true);
        TagEntity tag = countedTag(first);
        tag.setLastReadAt(OffsetDateTime.parse("2024-01-15T09:01:00Z"));

        ScanTagResponse response = tagService.registerScan(scanReader(),
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        assertEquals("Recent read ignored", response.getMessage());
        assertEquals(NOW, tag.getLastReadAt());
    }

    @Test
    void registerScan_rereadDelay_withoutTagLastRead_usesTheRecordsLastRead() {
        countedTag(countedRecord(scanReader(), "2024-01-15T07:00:00Z", "2024-01-15T09:10:00Z", true));

        ScanTagResponse response = tagService.registerScan(scanReader(),
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertEquals("Recent read ignored", response.getMessage());
    }

    @Test
    void registerScan_rereadDelay_withoutAnyLastRead_usesTheRecordsCreation() {
        countedTag(countedRecord(scanReader(), "2024-01-15T09:10:00Z", null, true));

        ScanTagResponse response = tagService.registerScan(scanReader(),
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertEquals("Recent read ignored", response.getMessage());
    }

    @Test
    void registerScan_rereadDelayElapsed_isNotIgnored() {
        TagEntity tag = countedTag(countedRecord(scanReader(), "2024-01-15T09:00:00Z", "2024-01-15T09:00:00Z", true));
        tag.setLastReadAt(OffsetDateTime.parse("2024-01-15T08:59:59Z"));
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(scanReader(),
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository).saveAndFlush(any(RecordEntity.class));
        assertThat(response.getMessage()).isNotEqualTo("Recent read ignored");
    }

    @Test
    void registerScan_zeroRereadDelay_neverIgnores() {
        TagService withoutRereadRule = tagService(DUPLICATE_WINDOW, CRATE_WINDOW, Duration.ZERO);
        countedTag(countedRecord(scanReader(), "2024-01-15T09:29:00Z", "2024-01-15T09:29:00Z", true));
        stubNewRecordSave();

        ScanTagResponse response = withoutRereadRule.registerScan(scanReader(),
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository).saveAndFlush(any(RecordEntity.class));
        assertThat(response.getMessage()).isNotEqualTo("Recent read ignored");
    }

    @Test
    void registerScan_samePassStillComesFirst_andLowersTheRecord() {
        ReaderEntity reader = scanReader();
        RecordEntity record = samePassRecord(reader, true);
        when(recordRepository.saveAndFlush(record)).thenReturn(record);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(false));

        assertEquals("Duplicate read ignored", response.getMessage());
        assertThat(record.isCompliant()).isFalse();
        assertEquals(NOW, record.getTag().getLastReadAt());
    }

    @Test
    void registerScan_countedTag_alwaysRecordsTheTagsLastRead() {
        TagEntity tag = countedTag(countedRecord(scanReader(), "2024-01-15T08:00:00Z", "2024-01-15T08:00:00Z", true));
        stubNewRecordSave();

        tagService.registerScan(scanReader(), new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertEquals(NOW, tag.getLastReadAt());
    }

    @Test
    void registerScan_rescanAtStartOfToday_isKO() {
        ReaderEntity reader = scanReader();
        countedTag(countedRecord(reader, "2024-01-15T00:00:00Z", "2024-01-15T00:00:00Z", true));
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertEquals("Tag already scanned today", response.getMessage());
    }

    @Test
    void registerScan_countedOnAnEarlierDay_isDoublonPerdu_whateverTheVerdict() {
        ReaderEntity reader = scanReader();
        TagEntity tag = countedTag(countedRecord(reader, "2024-01-14T17:00:00Z", "2024-01-14T17:00:00Z", true));
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        RecordEntity saved = savedRecord();
        assertThat(saved.isCompliant()).isFalse();
        assertThat(saved.getAnomaly()).isEqualTo(RecordAnomaly.DOUBLON_PERDU);
        assertThat(saved.getPicker()).isNull();
        assertThat(tag.getCountedRecord()).isSameAs(saved);
        assertEquals(false, response.getIsCompliant());
        assertEquals("Tag already scanned today", response.getMessage());
        verify(lineActivityService, never()).startOfToday();
    }

    @Test
    void registerScan_doublonScannedAgainLater_isAnotherDoublon() {
        RecordEntity doublon = countedRecord(scanReader(), "2024-01-15T08:00:00Z", "2024-01-15T08:00:00Z", false);
        doublon.setAnomaly(RecordAnomaly.DOUBLON_PERDU);
        countedTag(doublon);
        stubNewRecordSave();

        tagService.registerScan(scanReader(), new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        RecordEntity saved = savedRecord();
        assertThat(saved).isNotSameAs(doublon);
        assertThat(saved.getAnomaly()).isEqualTo(RecordAnomaly.DOUBLON_PERDU);
    }

    @Test
    void registerScan_tagNeverCountedWithoutBucket_isAnOrdinaryRecord() {
        existingTag();
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(scanReader(),
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        RecordEntity saved = savedRecord();
        assertThat(saved.isCompliant()).isTrue();
        assertThat(saved.getAnomaly()).isNull();
        assertThat(saved.getPicker()).isNull();
        assertEquals(null, response.getMessage());
    }

    // Rule 2: the second tag of a crate joins the crate's Record, which becomes Cheat (spec 015, FR-007, research R5)

    private static final OffsetDateTime CRATE_CUTOFF = OffsetDateTime.parse("2024-01-15T09:29:58Z");

    /** An existing tag still in a bucket, and the crate's Record that the lookup finds for it. */
    private TagEntity secondTagOf(RecordEntity crate, BucketEntity bucket) {
        TagEntity tag = existingTag();
        tag.setBucket(bucket);
        when(recordRepository.findFirstByReaderAndBucketAndTagNotAndCreationDateAfterOrderByCreationDateDesc(
                crate.getReader(), bucket, tag, CRATE_CUTOFF)).thenReturn(Optional.of(crate));
        return tag;
    }

    private RecordEntity crate(ReaderEntity reader, BucketEntity bucket, boolean compliant) {
        TagEntity first = TagEntity.builder().id(UUID.randomUUID()).uid("E2000017221101891400FIRST").build();
        return RecordEntity.builder().id(UUID.randomUUID()).reader(reader).tag(first).bucket(bucket)
                .compliant(compliant).creationDate(OffsetDateTime.parse("2024-01-15T09:29:59Z"))
                .lastReadAt(OffsetDateTime.parse("2024-01-15T09:29:59Z")).build();
    }

    private BucketEntity bucket() {
        return BucketEntity.builder().id(UUID.randomUUID()).number(12).build();
    }

    @Test
    void scan_bucketDeletedMeanwhile_countsWithoutPickerNorBucket() {
        BucketEntity bucket = bucket();
        bucket.setPicker(PickerEntity.builder().id(UUID.randomUUID()).firstname("Jane").lastname("Doe").build());
        TagEntity tag = existingTag();
        tag.setBucket(bucket);
        when(bucketRepository.findWithLockById(bucket.getId())).thenReturn(Optional.empty());
        stubNewRecordSave();

        tagService.registerScan(scanReader(), new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        RecordEntity record = savedRecord();
        assertNull(record.getPicker());
        assertNull(record.getBucket());
        assertNull(tag.getBucket());
        assertEquals(record, tag.getCountedRecord());
    }

    @Test
    void registerScan_secondTagOfACrate_joinsItsRecord_whichBecomesCheat() {
        ReaderEntity reader = scanReader();
        BucketEntity bucket = bucket();
        bucket.setPicker(PickerEntity.builder().id(UUID.randomUUID()).firstname("Jane").lastname("Doe").build());
        RecordEntity crate = crate(reader, bucket, true);
        crate.setPicker(bucket.getPicker());
        TagEntity tag = secondTagOf(crate, bucket);
        when(recordRepository.saveAndFlush(crate)).thenReturn(crate);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(recordRepository).findFirstByReaderAndBucketAndTagNotAndCreationDateAfterOrderByCreationDateDesc(
                any(), any(), any(), cutoff.capture());
        assertEquals(CRATE_CUTOFF, cutoff.getValue());
        assertThat(savedRecord()).isSameAs(crate);
        assertThat(crate.getAnomaly()).isEqualTo(RecordAnomaly.CHEAT);
        assertThat(crate.isCompliant()).isFalse();
        assertThat(crate.getPicker()).isSameAs(bucket.getPicker());
        assertThat(tag.getBucket()).isNull();
        assertThat(tag.getCountedRecord()).isSameAs(crate);
        assertEquals(NOW, crate.getLastReadAt());
        assertEquals(NOW, tag.getLastReadAt());
        assertEquals(DUPLICATE_UID, response.getUid());
        assertEquals(false, response.getIsCompliant());
        assertEquals(OffsetDateTime.parse("2024-01-15T09:29:59Z"), response.getProcessedAt());
        assertEquals("Second tag of the crate ignored", response.getMessage());
    }

    @Test
    void registerScan_secondTagOfANonCompliantCrate_isCheatToo() {
        ReaderEntity reader = scanReader();
        BucketEntity bucket = bucket();
        RecordEntity crate = crate(reader, bucket, false);
        secondTagOf(crate, bucket);
        when(recordRepository.saveAndFlush(crate)).thenReturn(crate);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(false));

        assertThat(savedRecord()).isSameAs(crate);
        assertThat(crate.getAnomaly()).isEqualTo(RecordAnomaly.CHEAT);
        assertEquals(false, response.getIsCompliant());
    }

    @Test
    void registerScan_secondTagOfAChangedCrate_keepsItsStatus() {
        ReaderEntity reader = scanReader();
        BucketEntity bucket = bucket();
        RecordEntity crate = crate(reader, bucket, true);
        TagEntity tag = secondTagOf(crate, bucket);
        when(recordConformityChangeRepository.existsByRecord(crate)).thenReturn(true);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(false));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        assertThat(crate.isCompliant()).isTrue();
        assertThat(crate.getAnomaly()).isNull();
        assertEquals(NOW, crate.getLastReadAt());
        assertThat(tag.getBucket()).isNull();
        assertEquals(true, response.getIsCompliant());
    }

    @Test
    void registerScan_thirdTagOfACheatCrate_staysOneCheatRecord() {
        ReaderEntity reader = scanReader();
        BucketEntity bucket = bucket();
        RecordEntity crate = crate(reader, bucket, false);
        crate.setAnomaly(RecordAnomaly.CHEAT);
        secondTagOf(crate, bucket);

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never()).saveAndFlush(any(RecordEntity.class));
        assertThat(crate.getAnomaly()).isEqualTo(RecordAnomaly.CHEAT);
        assertEquals(NOW, crate.getLastReadAt());
        assertEquals("Second tag of the crate ignored", response.getMessage());
    }

    @Test
    void registerScan_cheatGapFromTheSettings_isUsedForTheCrateLookup() {
        TagService withLongGap = tagService(DUPLICATE_WINDOW, Duration.ofSeconds(5));
        BucketEntity bucket = bucket();
        existingTag().setBucket(bucket);
        stubNewRecordSave();

        withLongGap.registerScan(scanReader(), new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(recordRepository).findFirstByReaderAndBucketAndTagNotAndCreationDateAfterOrderByCreationDateDesc(
                any(), any(), any(), cutoff.capture());
        assertEquals(NOW.minusSeconds(5), cutoff.getValue());
    }

    @Test
    void registerScan_noCrate_countsTheTag() {
        ReaderEntity reader = scanReader();
        BucketEntity bucket = bucket();
        TagEntity tag = existingTag();
        tag.setBucket(bucket);
        stubNewRecordSave();

        ScanTagResponse response = tagService.registerScan(reader,
                new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        assertThat(savedRecord().getBucket()).isSameAs(bucket);
        assertEquals(null, response.getMessage());
    }

    @Test
    void registerScan_tagWithoutBucket_skipsTheCrateLookup() {
        existingTag();
        stubNewRecordSave();

        tagService.registerScan(scanReader(), new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never())
                .findFirstByReaderAndBucketAndTagNotAndCreationDateAfterOrderByCreationDateDesc(any(), any(), any(),
                        any());
    }

    @Test
    void registerScan_zeroCrateWindow_skipsTheCrateLookup() {
        TagService withoutCrates = tagService(DUPLICATE_WINDOW, Duration.ZERO);
        existingTag().setBucket(bucket());
        stubNewRecordSave();

        withoutCrates.registerScan(scanReader(), new ScanTagRequest().uid(DUPLICATE_UID).isCompliant(true));

        verify(recordRepository, never())
                .findFirstByReaderAndBucketAndTagNotAndCreationDateAfterOrderByCreationDateDesc(any(), any(), any(),
                        any());
        verify(recordRepository).saveAndFlush(any(RecordEntity.class));
    }

    // --- registerTagsForBucket (spec 003: add-only, move confirmation, counts) ---

    private BucketEntity existingBucket(int number) {
        BucketEntity bucket = BucketEntity.builder().id(UUID.randomUUID()).number(number).build();
        when(bucketRepository.findWithLockByNumber(number)).thenReturn(Optional.of(bucket));
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
        when(bucketRepository.findWithLockByNumber(12)).thenReturn(Optional.empty());
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

        RegistrationNotConfirmedException exception = assertThrows(RegistrationNotConfirmedException.class,
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
    void registerTagsForBucket_clearsTheCountedRecord() {
        BucketEntity bucket = existingBucket(5);
        RecordEntity counted = RecordEntity.builder().id(UUID.randomUUID()).build();
        TagEntity tag = TagEntity.builder().uid("T1").countedRecord(counted).build();
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of(tag));

        tagService.registerTagsForBucket(5, List.of("T1"), false);

        assertEquals(bucket, tag.getBucket());
        assertThat(tag.getCountedRecord()).isNull();
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

    // --- registerScan: reference list (spec 010 révision, FR-006) ---

    @Test
    void registerScan_offListUid_returnsIgnored_withoutTouchingTheDatabase() {
        ReaderEntity reader = ReaderEntity.builder().id(UUID.randomUUID()).apitoken("token").name("Reader").build();
        when(referenceTagList.isOffList("OFF")).thenReturn(true);

        ScanTagResponse response = tagService.registerScan(reader, new ScanTagRequest().uid(" OFF ").isCompliant(false));

        assertEquals("OFF", response.getUid());
        assertEquals(true, response.getIsCompliant());
        assertEquals(OffsetDateTime.now(clock), response.getProcessedAt());
        assertEquals(TagService.OFF_LIST_SCAN_IGNORED, response.getMessage());
        Mockito.verifyNoInteractions(tagRepository, recordRepository, recordConformityChangeRepository);
    }

    @Test
    void registerScan_blankUid_isRefusedBeforeTheListIsConsulted() {
        ReaderEntity reader = ReaderEntity.builder().id(UUID.randomUUID()).apitoken("token").name("Reader").build();

        assertThrows(IllegalArgumentException.class,
                () -> tagService.registerScan(reader, new ScanTagRequest().uid("  ").isCompliant(true)));

        Mockito.verifyNoInteractions(referenceTagList, tagRepository, recordRepository);
    }

    // --- registerTagsForBucket: reference list (spec 010 révision, FR-004) ---

    @Test
    void registerTagsForBucket_offListTag_throws400NamingItAndWritesNothing() {
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of());
        when(referenceTagList.isOffList("OFF")).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> tagService.registerTagsForBucket(12, List.of("IN", "OFF"), false));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        assertThat(exception.getReason()).contains("Tags not in the reference list").contains("OFF")
                .doesNotContain(": IN");
        verify(tagRepository, never()).saveAll(anyIterable());
        verify(tagRepository, never()).save(any());
        verify(bucketRepository, never()).save(any());
    }

    @Test
    void registerTagsForBucket_moveAndOffList_moveConfirmed_stillThrows400() {
        BucketEntity otherBucket = BucketEntity.builder().id(UUID.randomUUID()).number(7).build();
        TagEntity moved = TagEntity.builder().uid("MOVED").bucket(otherBucket).build();
        when(tagRepository.findAllByUidIn(anyCollection())).thenReturn(List.of(moved));
        when(referenceTagList.isOffList("OFF")).thenReturn(true);

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> tagService.registerTagsForBucket(12, List.of("MOVED", "OFF"), true));

        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
        verify(tagRepository, never()).saveAll(anyIterable());
        assertEquals(otherBucket, moved.getBucket());
    }
}
