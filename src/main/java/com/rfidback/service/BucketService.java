package com.rfidback.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.exception.BucketNotFoundException;
import com.rfidback.exception.PickerNotFoundException;
import com.rfidback.generated.model.BucketWithTagsAndPicker;
import com.rfidback.generated.model.BucketWithTagsAndPickerAllOfPicker;
import com.rfidback.generated.model.BucketsList;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;
import com.rfidback.repository.TagRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BucketService {

    private final BucketRepository bucketRepository;
    private final TagRepository tagRepository;
    private final PickerRepository pickerRepository;

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

        List<BucketWithTagsAndPicker> bucketModels = new ArrayList<>(buckets.size());
        for (BucketEntity bucket : buckets) {
            BucketWithTagsAndPicker model = new BucketWithTagsAndPicker();
            model.setId(bucket.getId());
            model.setNumber(bucket.getNumber());
            model.setCreationDate(bucket.getCreationDate());
            model.setTags(tagsByBucket.getOrDefault(bucket.getId(), List.of()));
            PickerEntity picker = bucket.getPicker();
            if (picker != null) {
                model.setPicker(toPickerModel(picker));
            }
            bucketModels.add(model);
        }

        BucketsList response = new BucketsList();
        response.setBuckets(bucketModels);
        return response;
    }

    public BucketWithTagsAndPicker getBucket(UUID bucketId) {
        BucketEntity bucket = bucketRepository.findById(bucketId)
                .orElseThrow(() -> new BucketNotFoundException("Bucket %s not found".formatted(bucketId)));
        List<TagEntity> tags = tagRepository.findAllByBucket(bucket);

        BucketWithTagsAndPicker model = new BucketWithTagsAndPicker();
        model.setId(bucket.getId());
        model.setNumber(bucket.getNumber());
        model.setCreationDate(bucket.getCreationDate());
        model.setTags(tags.stream().map(TagEntity::getUid).toList());

        PickerEntity picker = bucket.getPicker();
        if (picker != null) {
            model.setPicker(toPickerModel(picker));
        } else {
            model.setPicker(null);
        }
        return model;
    }

    @Transactional
    public void assignBucketToPicker(UUID bucketId, UUID pickerId) {
        BucketEntity bucket = bucketRepository.findById(bucketId)
                .orElseThrow(() -> new BucketNotFoundException("Bucket %s not found".formatted(bucketId)));
        PickerEntity picker = pickerRepository.findById(pickerId)
                .orElseThrow(() -> new PickerNotFoundException("Picker %s not found".formatted(pickerId)));

        bucket.setPicker(picker);
        bucketRepository.save(bucket);
    }

    private BucketWithTagsAndPickerAllOfPicker toPickerModel(PickerEntity entity) {
        BucketWithTagsAndPickerAllOfPicker picker = new BucketWithTagsAndPickerAllOfPicker();
        picker.setLastname(entity.getLastname());
        picker.setFirstname(entity.getFirstname());
        picker.setComment(entity.getComment());
        return picker;
    }
}
