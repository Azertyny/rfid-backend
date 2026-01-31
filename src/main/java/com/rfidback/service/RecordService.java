package com.rfidback.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.exception.RecordNotFoundException;
import com.rfidback.generated.model.RecordSummary;
import com.rfidback.generated.model.RecordsList;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecordService {

    private final RecordRepository recordRepository;
    private final ReaderRepository readerRepository;

    @Transactional(readOnly = true)
    public RecordsList listLatestRecordsForReader(UUID readerId) {
        readerRepository.findById(readerId)
                .orElseThrow(() -> new ReaderNotFoundException("Reader %s not found".formatted(readerId)));

        List<RecordEntity> records = recordRepository.findTop10ByReader_IdOrderByCreationDateDesc(readerId);
        List<RecordSummary> recordModels = new ArrayList<>(records.size());
        for (RecordEntity record : records) {
            recordModels.add(toRecordSummary(record));
        }

        RecordsList response = new RecordsList();
        response.setRecords(recordModels);
        return response;
    }

    private RecordSummary toRecordSummary(RecordEntity record) {
        RecordSummary model = new RecordSummary();
        model.setId(record.getId());
        TagEntity tag = record.getTag();
        if (tag != null) {
            model.setTagUid(tag.getUid());
        }
        PickerEntity picker = record.getPicker();
        if (picker != null) {
            model.setPickerId(picker.getId());
        }
        model.setIsCompliant(record.isCompliant());
        model.setCreationDate(record.getCreationDate());
        return model;
    }

    @Transactional
    public void updateRecordConformity(java.util.UUID recordId, boolean isCompliant) {
        RecordEntity record = recordRepository.findById(recordId)
                .orElseThrow(() -> new RecordNotFoundException("Record %s not found".formatted(recordId)));
        record.setCompliant(isCompliant);
        recordRepository.save(record);
    }
}
