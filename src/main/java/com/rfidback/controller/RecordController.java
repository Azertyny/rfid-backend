package com.rfidback.controller;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.RecordApiDelegate;
import com.rfidback.generated.model.ConformityChangesList;
import com.rfidback.generated.model.RecordStats;
import com.rfidback.generated.model.StatsPeriod;
import com.rfidback.generated.model.UpdateRecordConformityRequest;
import com.rfidback.generated.model.RecordsList;
import com.rfidback.service.RecordService;
import com.rfidback.service.RecordStatsService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecordController implements RecordApiDelegate {

    private final RecordService recordService;
    private final RecordStatsService recordStatsService;

    @Override
    public ResponseEntity<RecordsList> listLatestRecordsForReader(String readerId) {
        return ResponseEntity.ok(recordService.listLatestRecordsForReader(readerId));
    }

    @Override
    public ResponseEntity<RecordStats> getRecordStats(Optional<StatsPeriod> period, Optional<LocalDate> from,
            Optional<LocalDate> to, Optional<UUID> readerId) {
        return ResponseEntity.ok(recordStatsService.getStats(period.orElse(null), from.orElse(null), to.orElse(null),
                readerId.orElse(null)));
    }

    @Override
    public ResponseEntity<Void> updateRecordConformity(UUID recordId,
            UpdateRecordConformityRequest updateRecordConformityRequest) {
        recordService.updateRecordConformity(recordId, updateRecordConformityRequest.getIsCompliant());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ConformityChangesList> listRecordConformityChanges(UUID recordId) {
        return ResponseEntity.ok(recordService.listConformityChanges(recordId));
    }
}
