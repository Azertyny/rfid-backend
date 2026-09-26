package com.rfidback.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordConformityChangeEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.exception.RecordNotFoundException;
import com.rfidback.generated.model.ConformityChange;
import com.rfidback.generated.model.ConformityChangesList;
import com.rfidback.generated.model.RecordSummary;
import com.rfidback.generated.model.RecordsList;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.UserRepository;
import com.rfidback.security.ReaderAuthentication;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RecordService {

    private final RecordRepository recordRepository;
    private final ReaderRepository readerRepository;
    private final RecordConformityChangeRepository recordConformityChangeRepository;
    private final UserRepository userRepository;

    private static final String OTHER_READER = "A reader token only gives access to its own reader";

    @Transactional(readOnly = true)
    public RecordsList listLatestRecordsForReader(String readerUid) {
        // The line kiosk authenticates with its reader's token and only sees that reader (spec 008, FR-005a).
        ReaderEntity kioskReader = currentReader();
        if (kioskReader != null && !kioskReader.getName().equals(readerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, OTHER_READER);
        }
        readerRepository.findByName(readerUid)
                .orElseThrow(() -> new ReaderNotFoundException("Reader %s not found".formatted(readerUid)));

        List<RecordEntity> records = recordRepository.findTop10ByReader_NameOrderByCreationDateDesc(readerUid);
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

    // Locks the record so two Opérateurs clicking together give one change, not two (spec 005, research R3).
    @Transactional
    public void updateRecordConformity(UUID recordId, boolean isCompliant) {
        RecordEntity record = recordRepository.findWithLockById(recordId)
                .orElseThrow(() -> new RecordNotFoundException("Record %s not found".formatted(recordId)));
        ReaderEntity kioskReader = currentReader();
        if (kioskReader != null && !record.getReader().getId().equals(kioskReader.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, OTHER_READER);
        }
        if (record.isCompliant() == isCompliant) {
            return;
        }
        RecordConformityChangeEntity.RecordConformityChangeEntityBuilder change = RecordConformityChangeEntity.builder()
                .record(record)
                .previousCompliant(record.isCompliant())
                .newCompliant(isCompliant);
        if (kioskReader != null) {
            change.authorReader(kioskReader);
        } else {
            String username = currentUsername();
            change.author(userRepository.findByUsername(username)
                    .orElseThrow(() -> new IllegalStateException("Logged-in user %s not found".formatted(username))));
        }
        recordConformityChangeRepository.save(change.build());
        record.setCompliant(isCompliant);
    }

    @Transactional(readOnly = true)
    public ConformityChangesList listConformityChanges(UUID recordId) {
        RecordEntity record = recordRepository.findById(recordId)
                .orElseThrow(() -> new RecordNotFoundException("Record %s not found".formatted(recordId)));

        List<RecordConformityChangeEntity> changes =
                recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(record);
        List<ConformityChange> changeModels = new ArrayList<>(changes.size());
        for (RecordConformityChangeEntity change : changes) {
            changeModels.add(toConformityChange(change));
        }

        ConformityChangesList response = new ConformityChangesList();
        response.setChanges(changeModels);
        return response;
    }

    private ConformityChange toConformityChange(RecordConformityChangeEntity change) {
        ConformityChange model = new ConformityChange();
        model.setPreviousIsCompliant(change.isPreviousCompliant());
        model.setNewIsCompliant(change.isNewCompliant());
        if (change.getAuthorReader() != null) {
            model.setAuthorType(ConformityChange.AuthorTypeEnum.READER);
            model.setAuthorReaderUid(change.getAuthorReader().getName());
        } else {
            model.setAuthorType(ConformityChange.AuthorTypeEnum.USER);
            model.setAuthorUsername(change.getAuthor().getUsername());
        }
        model.setChangedAt(change.getChangedAt());
        return model;
    }

    // The reader whose token authenticated this request (line kiosk), or null for a logged-in user.
    private static ReaderEntity currentReader() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof ReaderAuthentication reader ? (ReaderEntity) reader.getPrincipal() : null;
    }

    private static String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }
}
