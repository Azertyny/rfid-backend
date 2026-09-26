package com.rfidback.controller;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import com.rfidback.generated.api.ActivityApiDelegate;
import com.rfidback.generated.model.ActivitiesList;
import com.rfidback.generated.model.Activity;
import com.rfidback.generated.model.CreateActivity;
import com.rfidback.generated.model.LineActivity;
import com.rfidback.generated.model.SetLineActivity;
import com.rfidback.generated.model.UpdateActivity;
import com.rfidback.service.ActivityService;
import com.rfidback.service.LineActivityService;

@Service
public class ActivityController implements ActivityApiDelegate {

    @Autowired
    private ActivityService activityService;

    @Autowired
    private LineActivityService lineActivityService;

    @Override
    public ResponseEntity<ActivitiesList> listActivities() throws Exception {
        return ResponseEntity.ok(activityService.listActivities());
    }

    @Override
    public ResponseEntity<Activity> createActivity(CreateActivity createActivity) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(activityService.createActivity(createActivity));
    }

    @Override
    public ResponseEntity<Activity> updateActivity(UUID activityId, UpdateActivity updateActivity) throws Exception {
        return ResponseEntity.ok(activityService.updateActivity(activityId, updateActivity));
    }

    @Override
    public ResponseEntity<Void> deleteActivity(UUID activityId) throws Exception {
        activityService.deleteActivity(activityId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<LineActivity> getLineActivity(String readerUid) throws Exception {
        return ResponseEntity.ok(lineActivityService.getLineActivity(readerUid));
    }

    @Override
    public ResponseEntity<LineActivity> setLineActivity(String readerUid, SetLineActivity setLineActivity)
            throws Exception {
        return ResponseEntity.ok(lineActivityService.setLineActivity(readerUid, setLineActivity));
    }
}
