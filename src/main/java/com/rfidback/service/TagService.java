package com.rfidback.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.ConformityStatus;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.generated.model.RegisterTagsRequest;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;
    private final RecordRepository recordRepository;
    private final BucketRepository bucketRepository;

    public ScanTagResponse registerScan(ReaderEntity reader, ScanTagRequest scanTagRequest) {
        Assert.notNull(reader, "Reader must not be null");

        String uid = sanitizeUid(scanTagRequest.getUid());
        Assert.isTrue(StringUtils.hasText(uid), "Tag uid must not be blank");
        TagEntity tag = tagRepository.findByUid(uid)
                .orElseGet(() -> tagRepository.save(TagEntity.builder().uid(uid).build()));

        boolean isCompliant = Boolean.TRUE.equals(scanTagRequest.getIsCompliant());
        RecordEntity recordEntity = RecordEntity.builder()
                .tag(tag)
                .reader(reader)
                .conformity(isCompliant ? ConformityStatus.OK : ConformityStatus.NOK)
                .comment(buildMessage(isCompliant))
                .build();

        RecordEntity saved = recordRepository.save(recordEntity);

        ScanTagResponse response = new ScanTagResponse();
        response.setUid(tag.getUid());
        response.setIsCompliant(isCompliant);
        response.setProcessedAt(saved.getCreationDate());
        response.setMessage(Optional.ofNullable(saved.getComment()));
        return response;
    }

    @Transactional
    public RegisterTagsResponse registerTagsForBucket(Integer bucketNumber, RegisterTagsRequest request) {
        BucketEntity bucket = bucketRepository.findByNumber(bucketNumber)
                .orElseGet(() -> bucketRepository.save(BucketEntity.builder().number(bucketNumber).build()));

        List<TagEntity> previousTags = tagRepository.findAllByBucket(bucket);
        if (!previousTags.isEmpty()) {
            previousTags.forEach(tag -> tag.setBucket(null));
            tagRepository.saveAll(previousTags);
        }

        Set<String> uniqueUids = new LinkedHashSet<>();
        if (request != null && request.getUids() != null) {
            for (String uid : request.getUids()) {
                if (StringUtils.hasText(uid)) {
                    uniqueUids.add(uid.trim());
                }
            }
        }

        List<TagEntity> tagsToSave = new ArrayList<>();
        for (String uid : uniqueUids) {
            TagEntity tag = tagRepository.findByUid(uid)
                    .orElseGet(() -> TagEntity.builder().uid(uid).build());
            tag.setBucket(bucket);
            tagsToSave.add(tag);
        }

        if (!tagsToSave.isEmpty()) {
            tagRepository.saveAll(tagsToSave);
        }

        RegisterTagsResponse response = new RegisterTagsResponse();
        response.setBucketNumber(bucketNumber);
        response.setRegisteredCount(tagsToSave.size());
        return response;
    }

    private String buildMessage(boolean conforme) {
        return conforme ? "Tag enregistré comme conforme" : "Tag enregistré comme non conforme";
    }

    private String sanitizeUid(String uid) {
        return uid == null ? null : uid.trim();
    }
}
