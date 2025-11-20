package com.rfidback.controller;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.PickerApiDelegate;
import com.rfidback.generated.model.CreatePicker;
import com.rfidback.generated.model.Picker;
import com.rfidback.generated.model.PickersPage;
import com.rfidback.generated.model.UpdatePicker;
import com.rfidback.service.PickerService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PickerController implements PickerApiDelegate {

    private final PickerService pickerService;

    @Override
    public ResponseEntity<Picker> createPicker(CreatePicker createPicker) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pickerService.createPicker(createPicker));
    }

    @Override
    public ResponseEntity<Void> deletePicker(UUID pickerId) {
        pickerService.deletePicker(pickerId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Picker> getPicker(UUID pickerId) {
        return ResponseEntity.ok(pickerService.getPicker(pickerId));
    }

    @Override
    public ResponseEntity<PickersPage> getPickers(Optional<@Min(0) Integer> page,
            Optional<@Min(1) @Max(100) Integer> size,
            Optional<String> sort) {
        int requestedPage = page.orElse(0);
        int requestedSize = size.orElse(20);
        return ResponseEntity.ok(pickerService.listPickers(requestedPage, requestedSize));
    }

    @Override
    public ResponseEntity<Picker> updatePicker(UUID pickerId, UpdatePicker updatePicker) {
        return ResponseEntity.ok(pickerService.updatePicker(pickerId, updatePicker));
    }
}
