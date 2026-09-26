package com.rfidback.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.configuration.StationProperties;
import com.rfidback.entity.ActivityChangeAuthorType;
import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.LineActivityChangeEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.generated.model.ActivityRef;
import com.rfidback.generated.model.LineActivity;
import com.rfidback.generated.model.SetLineActivity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.LineActivityChangeRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.UserRepository;
import com.rfidback.security.ReaderAuthentication;

import lombok.RequiredArgsConstructor;

/**
 * The current activity of a line, a reader in PRODUCTION mode (spec 012). Owns the "effective current activity" rule
 * shared by the scan, the kiosk and the midnight reset: an activity chosen before today, station time, counts as
 * none (FR-008a, research R3). Every change is logged with its author (FR-012).
 */
@Service
@RequiredArgsConstructor
public class LineActivityService {

    private static final String OTHER_READER = "A reader token only gives access to its own reader";

    private final ReaderRepository readerRepository;
    private final ActivityRepository activityRepository;
    private final RecordRepository recordRepository;
    private final LineActivityChangeRepository lineActivityChangeRepository;
    private final UserRepository userRepository;
    private final StationProperties stationProperties;
    private final Clock clock;

    /** The reader's current activity if it was chosen today (station time), else null. */
    public ActivityEntity effectiveActivity(ReaderEntity reader) {
        OffsetDateTime setAt = reader.getCurrentActivitySetAt();
        if (reader.getCurrentActivity() == null || setAt == null || setAt.isBefore(startOfToday())) {
            return null;
        }
        return reader.getCurrentActivity();
    }

    public OffsetDateTime startOfToday() {
        ZoneId zone = stationProperties.timeZone();
        return LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toOffsetDateTime();
    }

    @Transactional(readOnly = true)
    public LineActivity getLineActivity(String readerUid) {
        checkKioskLine(readerUid);
        ReaderEntity reader = readerRepository.findWithCurrentActivityByName(readerUid)
                .orElseThrow(() -> readerNotFound(readerUid));
        checkProduction(reader);
        return describe(reader);
    }

    /** FR-009 to FR-011: the kiosk of this line, or a logged-in Opérateur or Administrateur. */
    @Transactional
    public LineActivity setLineActivity(String readerUid, SetLineActivity request) {
        ReaderEntity kioskReader = checkKioskLine(readerUid);
        ReaderEntity reader = readerRepository.findWithLockByName(readerUid)
                .orElseThrow(() -> readerNotFound(readerUid));
        checkProduction(reader);

        UUID activityId = request == null ? null : request.getActivityId();
        ActivityEntity activity = null;
        if (activityId != null) {
            activity = activityRepository.findById(activityId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Activity %s not found".formatted(activityId)));
            if (!activity.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Activity %s is disabled".formatted(activity.getName()));
            }
            boolean associated = activity.getLines().stream().anyMatch(line -> line.getId().equals(reader.getId()));
            if (!associated) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Activity %s is not associated with line %s".formatted(activity.getName(), readerUid));
            }
        }

        if (kioskReader != null) {
            change(reader, activity, ActivityChangeAuthorType.READER, null, reader);
        } else {
            change(reader, activity, ActivityChangeAuthorType.USER, currentUser(), null);
        }
        return describe(reader);
    }

    /** Leaves the line without current activity; {@code lockedReader} must be row-locked by the caller. */
    public void clear(ReaderEntity lockedReader, UserEntity author) {
        change(lockedReader, null, ActivityChangeAuthorType.USER, author, null);
    }

    /**
     * Clears every current activity chosen before today and logs each reset at the midnight it was due. Called at
     * midnight and at startup by {@link ActivityDailyReset}; the row locks need this transaction (analysis C1).
     */
    @Transactional
    public int resetStaleActivities() {
        int reset = 0;
        for (UUID readerId : readerRepository.findIdsByCurrentActivitySetAtBefore(startOfToday())) {
            ReaderEntity reader = readerRepository.findWithLockById(readerId).orElse(null);
            if (reader != null && resetIfStale(reader)) {
                reset++;
            }
        }
        return reset;
    }

    public LineActivity describe(ReaderEntity reader) {
        ActivityEntity current = effectiveActivity(reader);
        List<ActivityRef> available = activityRepository.findAllByLine(reader.getId()).stream()
                .filter(ActivityEntity::isActive)
                .map(LineActivityService::toRef)
                .toList();
        long withoutActivity = recordRepository.countByReaderAndCreationDateGreaterThanEqualAndActivityIsNull(reader,
                startOfToday());

        LineActivity lineActivity = new LineActivity();
        lineActivity.setReaderUid(reader.getName());
        lineActivity.setCurrentActivity(current == null ? null : toRef(current));
        lineActivity.setSetAt(current == null ? null : reader.getCurrentActivitySetAt());
        lineActivity.setAvailableActivities(available);
        lineActivity.setRecordsWithoutActivityToday(Math.toIntExact(withoutActivity));
        return lineActivity;
    }

    /** The logged-in user who acts, for the change log. */
    public UserEntity currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null ? null : authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("Logged-in user %s not found".formatted(username)));
    }

    /**
     * Sets the line's current activity, first logging a reset still due from a previous day, so the history never
     * skips one. Choosing the current value again writes nothing.
     */
    private void change(ReaderEntity lockedReader, ActivityEntity newActivity, ActivityChangeAuthorType authorType,
            UserEntity authorUser, ReaderEntity authorReader) {
        resetIfStale(lockedReader);
        ActivityEntity previous = lockedReader.getCurrentActivity();
        if (Objects.equals(idOf(previous), idOf(newActivity))) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        lockedReader.setCurrentActivity(newActivity);
        lockedReader.setCurrentActivitySetAt(newActivity == null ? null : now);
        lineActivityChangeRepository.save(LineActivityChangeEntity.builder()
                .reader(lockedReader)
                .previousActivity(previous)
                .newActivity(newActivity)
                .changedAt(now)
                .authorType(authorType)
                .authorUser(authorUser)
                .authorReader(authorReader)
                .build());
    }

    /** Clears a current activity chosen before today, logged by the system at the following midnight. */
    boolean resetIfStale(ReaderEntity lockedReader) {
        ActivityEntity current = lockedReader.getCurrentActivity();
        OffsetDateTime setAt = lockedReader.getCurrentActivitySetAt();
        if (current == null || (setAt != null && !setAt.isBefore(startOfToday()))) {
            return false;
        }
        ZoneId zone = stationProperties.timeZone();
        OffsetDateTime dueMidnight = setAt == null ? startOfToday()
                : setAt.atZoneSameInstant(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        lockedReader.setCurrentActivity(null);
        lockedReader.setCurrentActivitySetAt(null);
        lineActivityChangeRepository.save(LineActivityChangeEntity.builder()
                .reader(lockedReader)
                .previousActivity(current)
                .changedAt(dueMidnight)
                .authorType(ActivityChangeAuthorType.SYSTEM)
                .build());
        return true;
    }

    /** The kiosk's reader, which may only act on its own line (FR-009); null for a logged-in user. */
    private static ReaderEntity checkKioskLine(String readerUid) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof ReaderAuthentication readerAuthentication)) {
            return null;
        }
        ReaderEntity kioskReader = (ReaderEntity) readerAuthentication.getPrincipal();
        if (!kioskReader.getName().equals(readerUid)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, OTHER_READER);
        }
        return kioskReader;
    }

    private static void checkProduction(ReaderEntity reader) {
        if (reader.getMode() != ReaderMode.PRODUCTION) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Only readers in PRODUCTION mode have an activity");
        }
    }

    private static ReaderNotFoundException readerNotFound(String readerUid) {
        return new ReaderNotFoundException("Reader %s not found".formatted(readerUid));
    }

    static ActivityRef toRef(ActivityEntity activity) {
        return new ActivityRef(activity.getId(), activity.getName());
    }

    private static UUID idOf(ActivityEntity activity) {
        return activity == null ? null : activity.getId();
    }
}
