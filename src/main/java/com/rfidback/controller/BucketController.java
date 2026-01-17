package com.rfidback.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.BucketApiDelegate;
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
}
