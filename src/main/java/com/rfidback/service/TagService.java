package com.rfidback.service;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.exception.RegistrationNotConfirmedException;
import com.rfidback.generated.model.OffListTag;
import com.rfidback.generated.model.OffListTagsList;
import com.rfidback.generated.model.RegisterTagsRequest;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.generated.model.TagInOtherBucket;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RecordRepository.TagRecordsView;
import com.rfidback.repository.TagRepository;


@Service
public class TagService {

    /** FR-011: one registration covers at most this many tags; the API also declares it as maxItems. */
    public static final int MAX_TAGS_PER_REGISTRATION = 100;

    static final String DUPLICATE_READ_IGNORED = "Duplicate read ignored";

    private final TagRepository tagRepository;
    private final RecordRepository recordRepository;
    private final BucketRepository bucketRepository;
    private final RecordConformityChangeRepository recordConformityChangeRepository;
    private final ReferenceTagList referenceTagList;
    private final Clock clock;
    private final Duration duplicateWindow;

    public TagService(TagRepository tagRepository, RecordRepository recordRepository,
            BucketRepository bucketRepository, RecordConformityChangeRepository recordConformityChangeRepository,
            ReferenceTagList referenceTagList, Clock clock,
            @Value("${app.scan.duplicate-window}") Duration duplicateWindow) {
        this.tagRepository = tagRepository;
        this.recordRepository = recordRepository;
        this.bucketRepository = bucketRepository;
        this.recordConformityChangeRepository = recordConformityChangeRepository;
        this.referenceTagList = referenceTagList;
        this.clock = clock;
        this.duplicateWindow = duplicateWindow;
    }

    @Transactional
    public ScanTagResponse registerScan(ReaderEntity reader, ScanTagRequest scanTagRequest) {
        Assert.notNull(reader, "Reader must not be null");

        String uid = sanitizeUid(scanTagRequest.getUid());
        Assert.isTrue(StringUtils.hasText(uid), "Tag uid must not be blank");
        boolean isCompliant = Boolean.TRUE.equals(scanTagRequest.getIsCompliant());
        Optional<TagEntity> existingTag = tagRepository.findByUid(uid);
        if (existingTag.isPresent()) {
            Optional<RecordEntity> recent = findRecentRecord(reader, existingTag.get());
            if (recent.isPresent()) {
                return ignoreDuplicate(recent.get(), isCompliant);
            }
        }
        TagEntity tag = existingTag.orElseGet(() -> tagRepository.save(TagEntity.builder().uid(uid).build()));

        PickerEntity picker = tag.getBucket() != null ? tag.getBucket().getPicker() : null;
        RecordEntity recordEntity = RecordEntity.builder()
                .tag(tag)
                .reader(reader)
                .picker(picker)
                .compliant(isCompliant)
                .build();

        RecordEntity saved = recordRepository.saveAndFlush(recordEntity);

        ScanTagResponse response = new ScanTagResponse();
        response.setUid(tag.getUid());
        response.setIsCompliant(isCompliant);
        response.setProcessedAt(saved.getCreationDate());
        response.setMessage(saved.getComment());
        return response;
    }

    /** FR-008: a Record of this tag by this reader within the duplicate window; a new tag has none. */
    private Optional<RecordEntity> findRecentRecord(ReaderEntity reader, TagEntity tag) {
        if (duplicateWindow.isZero() || duplicateWindow.isNegative()) {
            return Optional.empty();
        }
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minus(duplicateWindow);
        return recordRepository.findFirstByReaderAndTagAndCreationDateAfterOrderByCreationDateDesc(reader, tag,
                cutoff);
    }

    /**
     * A repeated read creates no Record; a non-compliant one still lowers the Record (never raises it), unless an
     * Opérateur already changed it (spec 005, FR-006).
     */
    private ScanTagResponse ignoreDuplicate(RecordEntity existing, boolean isCompliant) {
        if (!isCompliant && existing.isCompliant() && !recordConformityChangeRepository.existsByRecord(existing)) {
            existing.setCompliant(false);
            existing = recordRepository.saveAndFlush(existing);
        }
        ScanTagResponse response = new ScanTagResponse();
        response.setUid(existing.getTag().getUid());
        response.setIsCompliant(existing.isCompliant());
        response.setProcessedAt(existing.getCreationDate());
        response.setMessage(DUPLICATE_READ_IGNORED);
        return response;
    }

    @Transactional
    public RegisterTagsResponse registerTagsForBucket(Integer bucketNumber, RegisterTagsRequest request) {
        List<String> uids = request == null || request.getUids() == null ? List.of() : request.getUids();
        boolean moveConfirmed = request != null && Boolean.TRUE.equals(request.getMoveConfirmed());
        boolean offListConfirmed = request != null && Boolean.TRUE.equals(request.getOffListConfirmed());
        return registerTagsForBucket(bucketNumber, uids, moveConfirmed, offListConfirmed);
    }

    /**
     * Adds the tags to the bucket (created if unknown). The bucket's other tags stay linked. Tags linked to another
     * bucket are moved only when {@code moveConfirmed}, and tags not in the reference list are registered only when
     * {@code offListConfirmed} (spec 010); otherwise nothing is written and the answer is 409, listing both kinds.
     */
    @Transactional
    public RegisterTagsResponse registerTagsForBucket(Integer bucketNumber, Collection<String> uids,
            boolean moveConfirmed, boolean offListConfirmed) {
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
        List<String> offListTags = uniqueUids.stream().filter(referenceTagList::isOffList).toList();
        if ((!tagsInOtherBuckets.isEmpty() && !moveConfirmed) || (!offListTags.isEmpty() && !offListConfirmed)) {
            throw new RegistrationNotConfirmedException(tagsInOtherBuckets, offListTags);
        }

        BucketEntity bucket = bucketRepository.findByNumber(bucketNumber)
                .orElseGet(() -> bucketRepository.save(BucketEntity.builder().number(bucketNumber).build()));

        List<TagEntity> tagsToSave = new ArrayList<>();
        for (String uid : uniqueUids) {
            TagEntity tag = existingTags.getOrDefault(uid, TagEntity.builder().uid(uid).build());
            tag.setBucket(bucket);
            tagsToSave.add(tag);
        }
        tagRepository.saveAll(tagsToSave);

        RegisterTagsResponse response = new RegisterTagsResponse();
        response.setBucketNumber(bucketNumber);
        response.setRegisteredCount(tagsToSave.size());
        response.setTotalCount(Math.toIntExact(tagRepository.countByBucket(bucket)));
        return response;
    }

    /**
     * Known tags whose uid is not in the reference list, with their bucket, record count and latest record, most
     * recently read first; never-read tags come last (spec 010, FR-009).
     */
    @Transactional(readOnly = true)
    public OffListTagsList listOffListTags() {
        List<TagEntity> offListTags = tagRepository.findAllWithBucket().stream()
                .filter(tag -> referenceTagList.isOffList(tag.getUid()))
                .toList();
        Map<UUID, TagRecordsView> recordsByTag = new HashMap<>();
        if (!offListTags.isEmpty()) {
            for (TagRecordsView view : recordRepository.findStatsByTagIds(
                    offListTags.stream().map(TagEntity::getId).toList())) {
                recordsByTag.put(view.getTagId(), view);
            }
        }

        List<OffListTag> models = new ArrayList<>(offListTags.size());
        for (TagEntity tag : offListTags) {
            TagRecordsView records = recordsByTag.get(tag.getId());
            OffListTag model = new OffListTag(tag.getUid(),
                    records == null ? 0L : records.getRecordCount().longValue(), tag.getCreationDate());
            model.setBucketNumber(tag.getBucket() == null ? null : tag.getBucket().getNumber());
            model.setLastRecordAt(records == null ? null : records.getLastRecordAt());
            models.add(model);
        }
        models.sort(Comparator.comparing(OffListTag::getLastRecordAt,
                Comparator.nullsLast(Comparator.<OffsetDateTime>reverseOrder()))
                .thenComparing(OffListTag::getUid));

        OffListTagsList response = new OffListTagsList();
        response.setReferenceListSize(referenceTagList.size());
        response.setTags(models);
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
