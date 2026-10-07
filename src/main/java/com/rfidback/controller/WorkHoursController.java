package com.rfidback.controller;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.WorkHoursApiDelegate;
import com.rfidback.generated.model.SaveWorkDayRequest;
import com.rfidback.generated.model.WorkDay;
import com.rfidback.service.WorkHoursService;

@Service
public class WorkHoursController implements WorkHoursApiDelegate {

    @Autowired
    private WorkHoursService workHoursService;

    @Override
    public ResponseEntity<WorkDay> getWorkDay(Optional<LocalDate> day) throws Exception {
        return ResponseEntity.ok(workHoursService.getWorkDay(day.orElse(null)));
    }

    @Override
    public ResponseEntity<WorkDay> saveWorkDay(SaveWorkDayRequest saveWorkDayRequest) throws Exception {
        return ResponseEntity.ok(workHoursService.saveWorkDay(saveWorkDayRequest));
    }
}
