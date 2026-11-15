package com.rfidback.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.ScanSettingsApiDelegate;
import com.rfidback.generated.model.ScanSettings;
import com.rfidback.generated.model.UpdateScanSettings;
import com.rfidback.service.ScanSettingsService;

import lombok.RequiredArgsConstructor;

/** The scan delays an Administrateur changes on the settings page (spec 015, US5). */
@Service
@RequiredArgsConstructor
public class ScanSettingsController implements ScanSettingsApiDelegate {

    private final ScanSettingsService scanSettingsService;

    @Override
    public ResponseEntity<ScanSettings> getScanSettings() {
        return ResponseEntity.ok(scanSettingsService.getSettings());
    }

    @Override
    public ResponseEntity<ScanSettings> updateScanSettings(UpdateScanSettings updateScanSettings) {
        return ResponseEntity.ok(scanSettingsService.updateSettings(updateScanSettings));
    }
}
