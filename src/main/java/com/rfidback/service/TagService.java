package com.rfidback.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.exception.RegistrationNotConfirmedException;
import com.rfidback.generated.model.RegisterTagsRequest;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.generated.model.TagInOtherBucket;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class TagService {

    /** FR-011: one registration covers at most this many tags; the API also declares it as maxItems. */
    public static final int MAX_TAGS_PER_REGISTRATION = 100;

    static final String DUPLICATE_READ_IGNORED = "Duplicate read ignored";
    static final String OFF_LIST_SCAN_IGNORED = "Tag not in reference list, ignored";
    static final String RESCAN_TODAY = "Tag already scanned today";
    static final String SECOND_TAG_OF_CRATE = "Second tag of the crate ignored";

    private final TagRepository tagRepository;
    private final RecordRepository recordRepository;
    private final BucketRepository bucketRepository;
    private final RecordConformityChangeRepository recordConformityChangeRepository;
    private final ReferenceTagList referenceTagList;
    private final ReaderRepository readerRepository;
    private final LineActivityService lineActivityService;
    private final Clock clock;
    private final Duration duplicateWindow;
    private final Duration crateWindow;

    public TagService(TagRepository tagRepository, RecordRepository recordRepository,
            BucketRepository bucketRepository, RecordConformityChangeRepository recordConformityChangeRepository,
            ReferenceTagList referenceTagList, ReaderRepository readerRepository,
            LineActivityService lineActivityService, Clock clock,
            @Value("${app.scan.duplicate-window}") Duration duplicateWindow,
            @Value("${app.scan.crate-window}") Duration crateWindow) {
        this.tagRepository = tagRepository;
        this.recordRepository = recordRepository;
        this.bucketRepository = bucketRepository;
        this.recordConformityChangeRepository = recordConformityChangeRepository;
        this.referenceTagList = referenceTagList;
        this.readerRepository = readerRepository;
        this.lineActivityService = lineActivityService;
        this.clock = clock;
        this.duplicateWindow = duplicateWindow;
        this.crateWindow = crateWindow;
    }

    /**
     * A scan from a PRODUCTION reader. A tag not in the reference list is ignored before any database access: no Tag,
     * no Record, and {@code isCompliant} true so the line raises no alert (spec 010 révision, FR-006). Otherwise the
     * first matching rule applies (spec 004, research R13): the same pass (FR-008), the second tag of a crate (FR-010),
     * a rescan the same day (FR-009), or a new Record that takes the tag out of its bucket (FR-009). A record carries the line's current activity at scan time, if any (spec 012, FR-014).
     */
    @Transactional
    public ScanTagResponse registerScan(ReaderEntity reader, ScanTagRequest scanTagRequest) {
        Assert.notNull(reader, "Reader must not be null");

        String uid = sanitizeUid(scanTagRequest.getUid());
        Assert.isTrue(StringUtils.hasText(uid), "Tag uid must not be blank");
        if (referenceTagList.isOffList(uid)) {
            log.debug("Scan of {} by reader {} ignored: not in the reference list", uid, reader.getName());
            ScanTagResponse ignored = new ScanTagResponse();
            ignored.setUid(uid);
            ignored.setIsCompliant(true);
            ignored.setProcessedAt(OffsetDateTime.now(clock));
            ignored.setMessage(OFF_LIST_SCAN_IGNORED);
            return ignored;
        }
        boolean isCompliant = Boolean.TRUE.equals(scanTagRequest.getIsCompliant());
        OffsetDateTime now = OffsetDateTime.now(clock);
        TagEntity tag = tagRepository.findByUid(uid)
                .orElseGet(() -> tagRepository.save(TagEntity.builder().uid(uid).build()));

        Optional<RecordEntity> counted = countedRecord(tag);
        if (counted.isPresent() && isSamePass(counted.get(), reader, now)) {
            return absorb(counted.get(), tag, isCompliant, now, DUPLICATE_READ_IGNORED);
        }
        Optional<RecordEntity> crate = crateOf(tag, reader, now);
        if (crate.isPresent()) {
            tag.setBucket(null);
            tag.setCountedRecord(crate.get());
            return absorb(crate.get(), tag, isCompliant, now, SECOND_TAG_OF_CRATE);
        }
        if (counted.isPresent() && !counted.get().getCreationDate().isBefore(lineActivityService.startOfToday())) {
            return rescanToday(tag, reader, now);
        }
        return count(tag, reader, isCompliant, now);
    }

    /** The record that counted the tag since its last registration, row-locked (spec 005, FR-006; research R8). */
    private Optional<RecordEntity> countedRecord(TagEntity tag) {
        return tag.getCountedRecord() == null ? Optional.empty()
                : recordRepository.findWithLockById(tag.getCountedRecord().getId());
    }

    /** Rule 1 (FR-008, research R7): the same reader read it less than the window ago, the window sliding. */
    private boolean isSamePass(RecordEntity record, ReaderEntity reader, OffsetDateTime now) {
        return duplicateWindow.isPositive()
                && record.getReader().getId().equals(reader.getId())
                && record.getLastReadAt() != null
                && record.getLastReadAt().isAfter(now.minus(duplicateWindow));
    }

    /**
     * Rule 4 (FR-003, FR-009): a new Record with the bucket's picker, and the tag leaves its bucket. The tag is
     * managed, so its changes are written with the transaction.
     */
    private ScanTagResponse count(TagEntity tag, ReaderEntity reader, boolean isCompliant, OffsetDateTime now) {
        // Locks the bucket so a concurrent delete either waits for this scan or is seen by it (spec 006, research R12).
        if (tag.getBucket() != null && bucketRepository.findWithLockById(tag.getBucket().getId()).isEmpty()) {
            tag.setBucket(null);
        }
        BucketEntity bucket = tag.getBucket();
        RecordEntity saved = recordRepository.saveAndFlush(RecordEntity.builder()
                .tag(tag)
                .reader(reader)
                .picker(bucket != null ? bucket.getPicker() : null)
                .bucket(bucket)
                .activity(currentActivity(reader))
                .compliant(isCompliant)
                .lastReadAt(now)
                .build());
        tag.setBucket(null);
        tag.setCountedRecord(saved);
        return response(tag, saved, null);
    }

    /**
     * Rule 2 (FR-010, research R10): the Record this reader created less than the crate window ago for another tag of
     * the same bucket. Measured from that Record's creation, so a crate still in front of the reader never swallows
     * the next one.
     */
    private Optional<RecordEntity> crateOf(TagEntity tag, ReaderEntity reader, OffsetDateTime now) {
        if (tag.getBucket() == null || !crateWindow.isPositive()) {
            return Optional.empty();
        }
        return recordRepository.findFirstByReaderAndBucketAndTagNotAndCreationDateAfterOrderByCreationDateDesc(
                reader, tag.getBucket(), tag, now.minus(crateWindow));
    }

    /**
     * Rule 3 (FR-009, research R9): a tag counted earlier today, read again outside its pass, was reused: a new
     * non-compliant Record with no picker, whatever the reader says. The first Record is unchanged.
     */
    private ScanTagResponse rescanToday(TagEntity tag, ReaderEntity reader, OffsetDateTime now) {
        RecordEntity saved = recordRepository.saveAndFlush(RecordEntity.builder()
                .tag(tag)
                .reader(reader)
                .activity(currentActivity(reader))
                .compliant(false)
                .lastReadAt(now)
                .build());
        tag.setCountedRecord(saved);
        return response(tag, saved, RESCAN_TODAY);
    }

    /**
     * Read from the database, not from the reader authenticated at the start of the request: that one is detached
     * and may predate a change of activity (spec 012, research R2). No lock, the scan never waits.
     */
    private ActivityEntity currentActivity(ReaderEntity reader) {
        return readerRepository.findWithCurrentActivityById(reader.getId())
                .map(lineActivityService::effectiveActivity)
                .orElse(null);
    }

    /**
     * A read that creates no Record: it extends the record's pass, and a non-compliant one lowers the record (never
     * raises it), unless an Opérateur already changed it (spec 005, FR-006).
     */
    private ScanTagResponse absorb(RecordEntity existing, TagEntity tag, boolean isCompliant, OffsetDateTime now,
            String message) {
        existing.setLastReadAt(now);
        if (!isCompliant && existing.isCompliant() && !recordConformityChangeRepository.existsByRecord(existing)) {
            existing.setCompliant(false);
            existing = recordRepository.saveAndFlush(existing);
        }
        return response(tag, existing, message);
    }

    /** The tag the reader sent, even when the record was created for another tag of its crate. */
    private static ScanTagResponse response(TagEntity tag, RecordEntity record, String message) {
        ScanTagResponse response = new ScanTagResponse();
        response.setUid(tag.getUid());
        response.setIsCompliant(record.isCompliant());
        response.setProcessedAt(record.getCreationDate());
        response.setMessage(message);
        return response;
    }

    @Transactional
    public RegisterTagsResponse registerTagsForBucket(Integer bucketNumber, RegisterTagsRequest request) {
        List<String> uids = request == null || request.getUids() == null ? List.of() : request.getUids();
        boolean moveConfirmed = request != null && Boolean.TRUE.equals(request.getMoveConfirmed());
        return registerTagsForBucket(bucketNumber, uids, moveConfirmed);
    }

    /**
     * Adds the tags to the bucket (created if unknown). The bucket's other tags stay linked. A tag not in the
     * reference list refuses the whole request with 400, whatever the flags (spec 010 révision, FR-004). Tags linked
     * to another bucket are moved only when {@code moveConfirmed}; otherwise nothing is written and the answer is 409.
     */
    @Transactional
    public RegisterTagsResponse registerTagsForBucket(Integer bucketNumber, Collection<String> uids,
            boolean moveConfirmed) {
        Set<String> uniqueUids = new LinkedHashSet<>();
        for (String uid : uids) {
            if (StringUtils.hasText(uid)) {
                uniqueUids.add(uid.trim());
            }
        }
        if (uniqueUids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide at least one non-blank tag uid");
        }
        // Also checked here for callers that bypass the API validation (a registration session's save).
        if (uniqueUids.size() > MAX_TAGS_PER_REGISTRATION) {
            throw tooManyTags();
        }
        List<String> offListTags = uniqueUids.stream().filter(referenceTagList::isOffList).toList();
        if (!offListTags.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tags not in the reference list: " + String.join(", ", offListTags));
        }

        Map<String, TagEntity> existingTags = new HashMap<>();
        for (TagEntity tag : tagRepository.findAllByUidIn(uniqueUids)) {
            existingTags.put(tag.getUid(), tag);
        }

        // Compare numbers, not entities: the target bucket may not exist yet.
        List<TagInOtherBucket> tagsInOtherBuckets = new ArrayList<>();
        for (String uid : uniqueUids) {
            TagEntity tag = existingTags.get(uid);
            if (tag != null && tag.getBucket() != null && !bucketNumber.equals(tag.getBucket().getNumber())) {
                tagsInOtherBuckets.add(new TagInOtherBucket(uid, tag.getBucket().getNumber()));
            }
        }
        if (!tagsInOtherBuckets.isEmpty() && !moveConfirmed) {
            throw new RegistrationNotConfirmedException(tagsInOtherBuckets);
        }

        BucketEntity bucket = bucketRepository.findWithLockByNumber(bucketNumber)
                .orElseGet(() -> bucketRepository.save(BucketEntity.builder().number(bucketNumber).build()));

        List<TagEntity> tagsToSave = new ArrayList<>();
        for (String uid : uniqueUids) {
            TagEntity tag = existingTags.getOrDefault(uid, TagEntity.builder().uid(uid).build());
            tag.setBucket(bucket);
            // Registering again makes the tag new for the scan rules (spec 004, FR-009).
            tag.setCountedRecord(null);
            tagsToSave.add(tag);
        }
        tagRepository.saveAll(tagsToSave);

        RegisterTagsResponse response = new RegisterTagsResponse();
        response.setBucketNumber(bucketNumber);
        response.setRegisteredCount(tagsToSave.size());
        response.setTotalCount(Math.toIntExact(tagRepository.countByBucket(bucket)));
        return response;
    }

    public static ResponseStatusException tooManyTags() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A registration covers at most %d tags".formatted(MAX_TAGS_PER_REGISTRATION));
    }

    private String sanitizeUid(String uid) {
        return uid == null ? null : uid.trim();
    }
}
