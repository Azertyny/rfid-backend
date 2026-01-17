package com.rfidback.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.generated.model.BucketWithTags;
import com.rfidback.generated.model.BucketsList;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.TagRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BucketService {

    private final BucketRepository bucketRepository;
    private final TagRepository tagRepository;

    public BucketsList listBuckets() {
        List<BucketEntity> buckets = bucketRepository.findAll(Sort.by(Sort.Order.asc("number")));
        Map<UUID, List<String>> tagsByBucket = new HashMap<>();

        if (!buckets.isEmpty()) {
            List<TagEntity> tags = tagRepository.findAllByBucketIn(buckets);
            for (TagEntity tag : tags) {
                BucketEntity bucket = tag.getBucket();
                if (bucket == null) {
                    continue;
                }
                tagsByBucket.computeIfAbsent(bucket.getId(), ignored -> new ArrayList<>()).add(tag.getUid());
            }
        }

        List<BucketWithTags> bucketModels = new ArrayList<>(buckets.size());
        for (BucketEntity bucket : buckets) {
            BucketWithTags model = new BucketWithTags();
            model.setId(bucket.getId());
            model.setNumber(bucket.getNumber());
            model.setCreationDate(bucket.getCreationDate());
            model.setTags(tagsByBucket.getOrDefault(bucket.getId(), List.of()));
            bucketModels.add(model);
        }

        BucketsList response = new BucketsList();
        response.setBuckets(bucketModels);
        return response;
    }
}
