package com.rfidback.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

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
 * shared by the scan, the kiosk and the midnight reset: a state dated before today, station time, counts as the
 * line's default activity, its only associated active activity, else none (FR-008a, research R3, R16). Every change
 * is logged with its author (FR-012).
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

    /**
     * The reader's current activity if its state is today's (station time), possibly none; otherwise the day's
     * default activity, which the midnight job writes shortly after (Clarifications 2026-09-28, research R16).
     */
    public ActivityEntity effectiveActivity(ReaderEntity reader) {
        return isToday(reader) ? reader.getCurrentActivity() : defaultActivity(reader);
    }

    /** The line's only associated and active activity, or null if it has none or several, or is not a line. */
    public ActivityEntity defaultActivity(ReaderEntity reader) {
        if (reader.getMode() != ReaderMode.PRODUCTION) {
            return null;
        }
        List<ActivityEntity> active = activityRepository.findAllByLine(reader.getId()).stream()
                .filter(ActivityEntity::isActive)
                .toList();
        return active.size() == 1 ? active.get(0) : null;
    }

    /** Whether the line's state, activity or none, was set today (station time); never for an undated state. */
    boolean isToday(ReaderEntity reader) {
        OffsetDateTime setAt = reader.getCurrentActivitySetAt();
        return setAt != null && !setAt.isBefore(startOfToday());
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
     * The line's current activity was taken away by an Administrateur (dissociated or disabled): it gets its default
     * activity instead, or none, logged as one change (FR-007, FR-008b). The caller holds the row lock and has flushed
     * its change.
     */
    public void replaceWithDefault(ReaderEntity lockedReader, UserEntity author) {
        change(lockedReader, defaultActivity(lockedReader), ActivityChangeAuthorType.USER, author, null);
    }

    /**
     * Gives every line whose state predates today its default activity, logging each change at the midnight it was
     * due. Called at midnight and at startup by {@link ActivityDailyReset}; the row locks need this transaction
     * (analysis C1). Returns the number of lines whose activity changed.
     */
    @Transactional
    public int startNewDayForAllLines() {
        int changed = 0;
        for (UUID readerId : readerRepository.findIdsWithStaleState(startOfToday())) {
            ReaderEntity reader = readerRepository.findWithLockById(readerId).orElse(null);
            if (reader != null && startNewDay(reader)) {
                changed++;
            }
        }
        return changed;
    }

    /**
     * FR-008b: a line left without current activity by an Administrateur change gets its default activity, if it has
     * one, with the Administrateur as author. Never changes a line that has a current activity. The caller holds the
     * row lock and has flushed its change of associations or activation, so the default sees it. The author is only
     * looked up when a change is written.
     */
    public void applyDefaultIfNone(ReaderEntity lockedReader, Supplier<UserEntity> author) {
        startNewDay(lockedReader);
        if (lockedReader.getCurrentActivity() != null) {
            return;
        }
        ActivityEntity defaultActivity = defaultActivity(lockedReader);
        if (defaultActivity != null) {
            change(lockedReader, defaultActivity, ActivityChangeAuthorType.USER, author.get(), null);
        }
    }

    public LineActivity describe(ReaderEntity reader) {
        ActivityEntity current = effectiveActivity(reader);
        // A default not written yet by the midnight job counts from midnight.
        OffsetDateTime setAt = current == null ? null
                : isToday(reader) ? reader.getCurrentActivitySetAt() : startOfToday();
        List<ActivityRef> available = activityRepository.findAllByLine(reader.getId()).stream()
                .filter(ActivityEntity::isActive)
                .map(LineActivityService::toRef)
                .toList();
        long withoutActivity = recordRepository.countByReaderAndCreationDateGreaterThanEqualAndActivityIsNull(reader,
                startOfToday());

        LineActivity lineActivity = new LineActivity();
        lineActivity.setReaderUid(reader.getName());
        lineActivity.setCurrentActivity(current == null ? null : toRef(current));
        lineActivity.setSetAt(setAt);
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
     * Sets the line's current activity, first logging the start of a day still due, so the history never skips one.
     * Choosing the current value again writes nothing. The state is dated even when it becomes none (research R15).
     */
    private void change(ReaderEntity lockedReader, ActivityEntity newActivity, ActivityChangeAuthorType authorType,
            UserEntity authorUser, ReaderEntity authorReader) {
        startNewDay(lockedReader);
        ActivityEntity previous = lockedReader.getCurrentActivity();
        if (Objects.equals(idOf(previous), idOf(newActivity))) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        lockedReader.setCurrentActivity(newActivity);
        lockedReader.setCurrentActivitySetAt(now);
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

    /**
     * Brings a state from a previous day, or never dated, to today: the line gets its default activity and the state
     * is dated today's midnight. The system logs it at the midnight that followed the old state, only if the activity
     * changes (research R16). Returns whether it changed. The caller holds the row lock.
     */
    public boolean startNewDay(ReaderEntity lockedReader) {
        if (isToday(lockedReader)) {
            return false;
        }
        ActivityEntity previous = lockedReader.getCurrentActivity();
        OffsetDateTime setAt = lockedReader.getCurrentActivitySetAt();
        ActivityEntity next = defaultActivity(lockedReader);
        lockedReader.setCurrentActivity(next);
        lockedReader.setCurrentActivitySetAt(startOfToday());
        if (Objects.equals(idOf(previous), idOf(next))) {
            return false;
        }
        ZoneId zone = stationProperties.timeZone();
        OffsetDateTime dueMidnight = setAt == null ? startOfToday()
                : setAt.atZoneSameInstant(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        lineActivityChangeRepository.save(LineActivityChangeEntity.builder()
                .reader(lockedReader)
                .previousActivity(previous)
                .newActivity(next)
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
