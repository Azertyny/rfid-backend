package com.rfidback.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.BucketApiDelegate;
import com.rfidback.generated.model.AssignBucketToPickerRequest;
import com.rfidback.generated.model.BucketWithTagsAndPicker;
import com.rfidback.generated.model.BucketsList;
import com.rfidback.service.BucketService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class BucketController implements BucketApiDelegate {

    private final BucketService bucketService;

    @Override
    public ResponseEntity<BucketsList> listBuckets() {
        return ResponseEntity.ok(bucketService.listBuckets());
    }

    @Override
    public ResponseEntity<BucketWithTagsAndPicker> getBucket(java.util.UUID bucketId) {
        return ResponseEntity.ok(bucketService.getBucket(bucketId));
    }

    @Override
    public ResponseEntity<Void> assignBucketToPicker(java.util.UUID bucketId,
            AssignBucketToPickerRequest assignBucketToPickerRequest) {
        bucketService.assignBucketToPicker(bucketId, assignBucketToPickerRequest.getPickerId());
        return ResponseEntity.noContent().build();
    }
}
