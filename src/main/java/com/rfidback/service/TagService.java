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

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.exception.TagsInOtherBucketsException;
import com.rfidback.generated.model.RegisterTagsRequest;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.generated.model.TagInOtherBucket;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;


@Service
public class TagService {

    /** FR-011: one registration covers at most this many tags; the API also declares it as maxItems. */
    public static final int MAX_TAGS_PER_REGISTRATION = 100;

    static final String DUPLICATE_READ_IGNORED = "Duplicate read ignored";

    private final TagRepository tagRepository;
    private final RecordRepository recordRepository;
    private final BucketRepository bucketRepository;
    private final Clock clock;
    private final Duration duplicateWindow;

    public TagService(TagRepository tagRepository, RecordRepository recordRepository,
            BucketRepository bucketRepository, Clock clock,
            @Value("${app.scan.duplicate-window}") Duration duplicateWindow) {
        this.tagRepository = tagRepository;
        this.recordRepository = recordRepository;
        this.bucketRepository = bucketRepository;
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

    /** A repeated read creates no Record; a non-compliant one still lowers the Record (never raises it). */
    private ScanTagResponse ignoreDuplicate(RecordEntity existing, boolean isCompliant) {
        if (!isCompliant && existing.isCompliant()) {
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
        return registerTagsForBucket(bucketNumber, uids, moveConfirmed);
    }

    /**
     * Adds the tags to the bucket (created if unknown). The bucket's other tags stay linked. Tags linked to another
     * bucket are moved only when {@code moveConfirmed}; otherwise nothing is written and the answer is 409.
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
            throw new TagsInOtherBucketsException(tagsInOtherBuckets);
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

    public static ResponseStatusException tooManyTags() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A registration covers at most %d tags".formatted(MAX_TAGS_PER_REGISTRATION));
    }

    private String sanitizeUid(String uid) {
        return uid == null ? null : uid.trim();
    }
}
