package com.rfidback.service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
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
import com.rfidback.exception.ActivityAlreadyExistsException;
import com.rfidback.exception.ActivityNotFoundException;
import com.rfidback.exception.LinesLosingActivityException;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.generated.model.ActivitiesList;
import com.rfidback.generated.model.Activity;
import com.rfidback.generated.model.CreateActivity;
import com.rfidback.generated.model.LineActivity;
import com.rfidback.generated.model.LineLosingActivity;
import com.rfidback.generated.model.SetReaderActivities;
import com.rfidback.generated.model.UpdateActivity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.LineActivityChangeRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;

import lombok.RequiredArgsConstructor;

/**
 * The Administrateur's catalogue of activities and their association with lines (spec 012, FR-001 to FR-007). A
 * change that would take a line's current activity away needs confirmation (research R6). A line left without
 * current activity and with exactly one associated active activity gets it (FR-008b, research R17). Several readers
 * are always locked in ascending id order, so two concurrent changes cannot deadlock.
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

    /**
     * Renames, disables or re-enables. Disabling takes it from the lines where it is current, once confirmed; turning
     * it on or off can leave an associated line with a single active activity, which it then gets (FR-008b).
     */
    @Transactional
    public Activity updateActivity(UUID activityId, UpdateActivity request) {
        if (request.getName() == null && request.getActive() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a name and/or an active state");
        }
        ActivityEntity activity = loadActivity(activityId);
        boolean flips = request.getActive() != null && request.getActive() != activity.isActive();
        // Locked before anything reads the activity's lines, so each reader comes back as it is once locked.
        List<ReaderEntity> lines = flips ? lockLines(activity) : List.of();
        if (request.getName() != null) {
            String name = validName(request.getName());
            if (activityRepository.existsByNameKeyAndIdNot(ActivityEntity.nameKey(name), activityId)) {
                throw new ActivityAlreadyExistsException(DUPLICATE_NAME_MESSAGE);
            }
            activity.setName(name);
        }
        boolean disabling = flips && !request.getActive();
        List<ReaderEntity> losing = disabling
                ? lines.stream().filter(reader -> isCurrent(activity, reader)).toList()
                : List.of();
        if (!losing.isEmpty() && !Boolean.TRUE.equals(request.getConfirmed())) {
            throw new LinesLosingActivityException(losing.stream()
                    .map(reader -> losingLine(reader, activeAssociatedExcept(reader, activity)))
                    .toList());
        }
        if (request.getActive() != null) {
            activity.setActive(request.getActive());
        }
        Activity saved;
        try {
            saved = toModel(activityRepository.saveAndFlush(activity));
        } catch (DataIntegrityViolationException exception) {
            throw new ActivityAlreadyExistsException(DUPLICATE_NAME_MESSAGE);
        }
        for (ReaderEntity reader : lines) {
            if (losing.contains(reader)) {
                lineActivityService.replaceWithDefault(reader, lineActivityService.currentUser());
            } else {
                lineActivityService.applyDefaultIfNone(reader, lineActivityService::currentUser);
            }
        }
        return saved;
    }

    /**
     * Only an activity never used: no record carries it and no line ever had it as current (research R7). A line it
     * leaves with a single active activity gets that one (FR-008b).
     */
    @Transactional
    public void deleteActivity(UUID activityId) {
        ActivityEntity activity = loadActivity(activityId);
        if (recordRepository.existsByActivity(activity) || lineActivityChangeRepository.isReferenced(activity)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Activity already used: disable it instead");
        }
        List<ReaderEntity> lines = lockLines(activity);
        // No reader can still point to it: every choice is logged, and the log refuses the deletion above.
        activity.getLines().clear();
        activityRepository.delete(activity);
        activityRepository.flush();
        lines.forEach(reader -> lineActivityService.applyDefaultIfNone(reader, lineActivityService::currentUser));
    }

    /** Replaces the activities a line can run (FR-005, FR-006). */
    @Transactional
    public LineActivity setReaderActivities(UUID readerId, SetReaderActivities request) {
        ReaderEntity reader = readerRepository.findWithLockById(readerId)
                .filter(found -> !found.isDeleted())
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

        lineActivityService.startNewDay(reader);
        List<ActivityEntity> associated = activityRepository.findAllByLine(readerId);
        Set<UUID> activeBefore = activeIds(associated);
        List<ActivityEntity> wantedActive = wanted.stream().filter(ActivityEntity::isActive).toList();
        ActivityEntity current = reader.getCurrentActivity();
        boolean losesCurrent = current != null && !activityIds.contains(current.getId());
        if (losesCurrent && !Boolean.TRUE.equals(request.getConfirmed())) {
            throw new LinesLosingActivityException(List.of(losingLine(reader, wantedActive)));
        }

        for (ActivityEntity activity : associated) {
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
        if (losesCurrent) {
            lineActivityService.replaceWithDefault(reader, lineActivityService.currentUser());
        } else if (!activeBefore.equals(activeIds(wantedActive))) {
            // Re-saving an unchanged row keeps a deliberate "no activity" (research R17).
            lineActivityService.applyDefaultIfNone(reader, lineActivityService::currentUser);
        }
        return lineActivityService.describe(reader);
    }

    /**
     * The lines associated with the activity, or having it as current, row-locked in ascending id order, each with
     * its state brought to today so the change applies to today's activity.
     */
    private List<ReaderEntity> lockLines(ActivityEntity activity) {
        Set<UUID> ids = new TreeSet<>(readerRepository.findIdsByActivity(activity));
        ids.addAll(readerRepository.findIdsByCurrentActivity(activity));
        List<ReaderEntity> lines = ids.stream()
                .map(readerRepository::findWithLockById)
                .flatMap(Optional::stream)
                .toList();
        lines.forEach(lineActivityService::startNewDay);
        return lines;
    }

    /** FR-007: a line losing its current activity, named with the one it would get: its only remaining one. */
    private static LineLosingActivity losingLine(ReaderEntity reader, List<ActivityEntity> remainingActive) {
        ActivityEntity next = remainingActive.size() == 1 ? remainingActive.get(0) : null;
        return new LineLosingActivity(reader.getName(), next == null ? null : LineActivityService.toRef(next));
    }

    private List<ActivityEntity> activeAssociatedExcept(ReaderEntity reader, ActivityEntity excluded) {
        return activityRepository.findAllByLine(reader.getId()).stream()
                .filter(ActivityEntity::isActive)
                .filter(activity -> !activity.getId().equals(excluded.getId()))
                .toList();
    }

    private static Set<UUID> activeIds(List<ActivityEntity> activities) {
        return activities.stream().filter(ActivityEntity::isActive).map(ActivityEntity::getId)
                .collect(Collectors.toSet());
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
