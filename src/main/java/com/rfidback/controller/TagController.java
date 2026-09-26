package com.rfidback.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.generated.api.TagApiDelegate;
import com.rfidback.generated.model.RegisterTagsRequest;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.service.RegistrationService;
import com.rfidback.service.TagService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TagController implements TagApiDelegate {

    private final TagService tagService;
    private final RegistrationService registrationService;

    @Override
    public ResponseEntity<ScanTagResponse> scanTag(ScanTagRequest scanTagRequest) {
        ReaderEntity reader = AuthenticatedReader.require();
        // Routed here rather than in TagService: RegistrationService already depends on TagService (research R2).
        if (reader.getMode() == ReaderMode.ENREGISTREMENT) {
            return ResponseEntity.ok(registrationService.recordRead(reader, scanTagRequest.getUid()));
        }
        return ResponseEntity.ok(tagService.registerScan(reader, scanTagRequest));
    }

    @Override
    public ResponseEntity<RegisterTagsResponse> registerTagsForBucket(Integer bucketNumber,
            RegisterTagsRequest registerTagsRequest) {
        return ResponseEntity.ok(tagService.registerTagsForBucket(bucketNumber, registerTagsRequest));
    }
}
