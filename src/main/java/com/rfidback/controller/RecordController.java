package com.rfidback.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.RecordApiDelegate;
import com.rfidback.generated.model.UpdateRecordConformityRequest;
import com.rfidback.generated.model.RecordsList;
import com.rfidback.service.RecordService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecordController implements RecordApiDelegate {

    private final RecordService recordService;

    @Override
    public ResponseEntity<RecordsList> listLatestRecordsForReader(java.util.UUID readerId) {
        return ResponseEntity.ok(recordService.listLatestRecordsForReader(readerId));
    }

    @Override
    public ResponseEntity<Void> updateRecordConformity(java.util.UUID recordId,
            UpdateRecordConformityRequest updateRecordConformityRequest) {
        recordService.updateRecordConformity(recordId, updateRecordConformityRequest.getIsCompliant());
        return ResponseEntity.noContent().build();
    }
}
