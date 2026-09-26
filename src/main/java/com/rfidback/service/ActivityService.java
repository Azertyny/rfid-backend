package com.rfidback.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.ActivityAlreadyExistsException;
import com.rfidback.exception.ActivityNotFoundException;
import com.rfidback.exception.LinesLosingActivityException;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.generated.model.ActivitiesList;
import com.rfidback.generated.model.Activity;
import com.rfidback.generated.model.CreateActivity;
import com.rfidback.generated.model.LineActivity;
import com.rfidback.generated.model.SetReaderActivities;
import com.rfidback.generated.model.UpdateActivity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.LineActivityChangeRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;

import lombok.RequiredArgsConstructor;

/**
 * The Administrateur's catalogue of activities and their association with lines (spec 012, FR-001 to FR-007). A
 * change that would leave a line without its current activity needs confirmation (research R6).
 */
@Service
@RequiredArgsConstructor
public class ActivityService {

    private static final String DUPLICATE_NAME_MESSAGE = "An activity with the same name already exists";

    private final ActivityRepository activityRepository;
    private final ReaderRepository readerRepository;
    private final RecordRepository recordRepository;
    private final LineActivityChangeRepository lineActivityChangeRepository;
    private final LineActivityService lineActivityService;

    @Transactional(readOnly = true)
    public ActivitiesList listActivities() {
        List<Activity> activities = activityRepository.findAllByOrderByNameAsc().stream()
                .map(ActivityService::toModel)
                .toList();
        ActivitiesList list = new ActivitiesList();
        list.setActivities(activities);
        return list;
    }

    public Activity createActivity(CreateActivity request) {
        String name = validName(request == null ? null : request.getName());
        if (activityRepository.existsByNameKey(ActivityEntity.nameKey(name))) {
            throw new ActivityAlreadyExistsException(DUPLICATE_NAME_MESSAGE);
        }
        try {
            return toModel(activityRepository.saveAndFlush(ActivityEntity.builder().name(name).build()));
        } catch (DataIntegrityViolationException exception) {
            // Another request created the same name between the check above and this insert.
            throw new ActivityAlreadyExistsException(DUPLICATE_NAME_MESSAGE);
        }
    }

    /** Renames, disables or re-enables. Disabling clears it on the lines where it is current, once confirmed. */
    @Transactional
    public Activity updateActivity(UUID activityId, UpdateActivity request) {
        if (request.getName() == null && request.getActive() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a name and/or an active state");
        }
        ActivityEntity activity = loadActivity(activityId);
        if (request.getName() != null) {
            String name = validName(request.getName());
            if (activityRepository.existsByNameKeyAndIdNot(ActivityEntity.nameKey(name), activityId)) {
                throw new ActivityAlreadyExistsException(DUPLICATE_NAME_MESSAGE);
            }
            activity.setName(name);
        }
        if (Boolean.FALSE.equals(request.getActive()) && activity.isActive()) {
            List<ReaderEntity> losing = new ArrayList<>();
            for (UUID readerId : readerRepository.findIdsByCurrentActivity(activity)) {
                readerRepository.findWithLockById(readerId)
                        .filter(reader -> isCurrent(activity, reader))
                        .ifPresent(losing::add);
            }
            clearConfirmed(losing, Boolean.TRUE.equals(request.getConfirmed()));
        }
        if (request.getActive() != null) {
            activity.setActive(request.getActive());
        }
        try {
            return toModel(activityRepository.saveAndFlush(activity));
        } catch (DataIntegrityViolationException exception) {
            throw new ActivityAlreadyExistsException(DUPLICATE_NAME_MESSAGE);
        }
    }

    /** Only an activity never used: no record carries it and no line ever had it as current (research R7). */
    @Transactional
    public void deleteActivity(UUID activityId) {
        ActivityEntity activity = loadActivity(activityId);
        if (recordRepository.existsByActivity(activity) || lineActivityChangeRepository.isReferenced(activity)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Activity already used: disable it instead");
        }
        // No reader can still point to it: every choice is logged, and the log refuses the deletion above.
        activity.getLines().clear();
        activityRepository.delete(activity);
    }

    /** Replaces the activities a line can run (FR-005, FR-006). */
    @Transactional
    public LineActivity setReaderActivities(UUID readerId, SetReaderActivities request) {
        ReaderEntity reader = readerRepository.findWithLockById(readerId)
                .orElseThrow(() -> new ReaderNotFoundException("Reader %s not found".formatted(readerId)));
        if (reader.getMode() != ReaderMode.PRODUCTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only readers in PRODUCTION mode can be associated with activities");
        }
        Set<UUID> activityIds = request.getActivityIds() == null ? Set.of() : request.getActivityIds();
        List<ActivityEntity> wanted = activityRepository.findAllById(activityIds);
        if (wanted.size() != activityIds.size()) {
            Set<UUID> found = wanted.stream().map(ActivityEntity::getId).collect(Collectors.toSet());
            String missing = activityIds.stream().filter(id -> !found.contains(id)).map(UUID::toString)
                    .collect(Collectors.joining(", "));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Activities not found: " + missing);
        }

        ActivityEntity current = lineActivityService.effectiveActivity(reader);
        if (current != null && !activityIds.contains(current.getId())) {
            clearConfirmed(List.of(reader), Boolean.TRUE.equals(request.getConfirmed()));
        }

        for (ActivityEntity activity : activityRepository.findAllByLine(readerId)) {
            if (!activityIds.contains(activity.getId())) {
                activity.getLines().removeIf(line -> line.getId().equals(readerId));
            }
        }
        for (ActivityEntity activity : wanted) {
            if (activity.getLines().stream().noneMatch(line -> line.getId().equals(readerId))) {
                activity.getLines().add(reader);
            }
        }
        activityRepository.flush();
        return lineActivityService.describe(reader);
    }

    /** FR-007: the lines are named first (409); once confirmed, each loses its current activity. */
    private void clearConfirmed(List<ReaderEntity> lockedReaders, boolean confirmed) {
        if (lockedReaders.isEmpty()) {
            return;
        }
        if (!confirmed) {
            throw new LinesLosingActivityException(lockedReaders.stream().map(ReaderEntity::getName).toList());
        }
        UserEntity author = lineActivityService.currentUser();
        for (ReaderEntity reader : lockedReaders) {
            lineActivityService.clear(reader, author);
        }
    }

    private boolean isCurrent(ActivityEntity activity, ReaderEntity reader) {
        ActivityEntity current = lineActivityService.effectiveActivity(reader);
        return current != null && current.getId().equals(activity.getId());
    }

    private ActivityEntity loadActivity(UUID activityId) {
        return activityRepository.findById(activityId)
                .orElseThrow(() -> new ActivityNotFoundException("Activity %s not found".formatted(activityId)));
    }

    private static String validName(String name) {
        if (!StringUtils.hasText(name)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name must not be blank");
        }
        String trimmed = name.trim();
        if (trimmed.length() > ActivityEntity.NAME_MAX_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name must be at most %d characters".formatted(ActivityEntity.NAME_MAX_LENGTH));
        }
        return trimmed;
    }

    private static Activity toModel(ActivityEntity entity) {
        Activity activity = new Activity(entity.getId(), entity.getName(), entity.isActive(),
                entity.getLines().stream().map(ReaderEntity::getId).sorted().toList());
        activity.setCreationDate(entity.getCreationDate());
        activity.setUpdateDate(entity.getUpdateDate());
        return activity;
    }
}
