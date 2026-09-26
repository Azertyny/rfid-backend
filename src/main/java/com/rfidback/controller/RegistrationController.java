package com.rfidback.controller;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.RegistrationApiDelegate;
import com.rfidback.generated.model.RegisterTagsResponse;
import com.rfidback.generated.model.RegistrationReadsRequest;
import com.rfidback.generated.model.RegistrationReadsResponse;
import com.rfidback.generated.model.RegistrationSession;
import com.rfidback.generated.model.SaveRegistrationSession;
import com.rfidback.generated.model.StartRegistrationSession;
import com.rfidback.service.RegistrationService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RegistrationController implements RegistrationApiDelegate {

    private final RegistrationService registrationService;

    @Override
    public ResponseEntity<RegistrationSession> startRegistrationSession(StartRegistrationSession request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(registrationService.start(request.getReaderId()));
    }

    @Override
    public ResponseEntity<RegistrationSession> getRegistrationSession(UUID sessionId) {
        return ResponseEntity.ok(registrationService.get(sessionId));
    }

    @Override
    public ResponseEntity<Void> cancelRegistrationSession(UUID sessionId) {
        registrationService.cancel(sessionId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<RegisterTagsResponse> saveRegistrationSession(UUID sessionId,
            SaveRegistrationSession request) {
        return ResponseEntity.ok(registrationService.save(sessionId, request));
    }

    @Override
    public ResponseEntity<RegistrationReadsResponse> recordRegistrationReads(RegistrationReadsRequest request) {
        return ResponseEntity.ok(registrationService.recordReads(AuthenticatedReader.require(), request.getUids()));
    }
}
