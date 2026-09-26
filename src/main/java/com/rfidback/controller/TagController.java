package com.rfidback.controller;

import java.util.Optional;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.generated.api.TagApiDelegate;
import com.rfidback.generated.model.OffListTagsList;
import com.rfidback.generated.model.RegisterTagsRequest;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.ScanTagRequest;
import com.rfidback.generated.model.ScanTagResponse;
import com.rfidback.security.ReaderAuthentication;
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
        ReaderEntity reader = resolveAuthenticatedReader()
                .orElseThrow(() -> new IllegalStateException("Authenticated reader not found in context"));
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

    @Override
    public ResponseEntity<OffListTagsList> listOffListTags() {
        return ResponseEntity.ok(tagService.listOffListTags());
    }

    private Optional<ReaderEntity> resolveAuthenticatedReader() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ReaderAuthentication readerAuthentication) {
            return Optional.of((ReaderEntity) readerAuthentication.getPrincipal());
        }
        return Optional.empty();
    }
}
