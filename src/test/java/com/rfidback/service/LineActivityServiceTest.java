package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.rfidback.configuration.StationProperties;
import com.rfidback.entity.ActivityChangeAuthorType;
import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.LineActivityChangeEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.LineActivityChangeRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.UserRepository;

/**
 * The midnight rule, station time Europe/Paris, and the day's default activity (spec 012, FR-008a, FR-008b, research
 * R3, R16, R17). Without associations mocked, a line's default activity is none.
 */
class LineActivityServiceTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final LineActivityChangeRepository changeRepository = Mockito.mock(LineActivityChangeRepository.class);
    private final ActivityRepository activityRepository = Mockito.mock(ActivityRepository.class);
    private final UserEntity admin = UserEntity.builder().username("admin").build();

    @Test
    void effectiveActivity_lastsUntilMidnightStationTime_inWinterAndSummer() {
        // 23:59 in Paris is 22:59Z in winter (+01:00) and 21:59Z in summer (+02:00).
        assertEffectiveAt("2026-01-15T08:00:00Z", "2026-01-15T22:59:00Z", true);
        assertEffectiveAt("2026-01-15T08:00:00Z", "2026-01-15T23:00:00Z", false);
        assertEffectiveAt("2026-07-15T08:00:00Z", "2026-07-15T21:59:00Z", true);
        assertEffectiveAt("2026-07-15T08:00:00Z", "2026-07-15T22:00:00Z", false);
    }

    @Test
    void effectiveActivity_withoutActivity_isNull() {
        LineActivityService service = serviceAt("2026-01-15T10:00:00Z");

        assertThat(service.effectiveActivity(ReaderEntity.builder().name("L1").build())).isNull();
    }

    @Test
    void clear_sameValue_writesNothing() {
        LineActivityService service = serviceAt("2026-01-15T10:00:00Z");
        ReaderEntity line = ReaderEntity.builder().id(UUID.randomUUID()).name("L1").build();

        service.clear(line, admin);

        verify(changeRepository, never()).save(any());
    }

    @Test
    void clear_overAStaleChoice_logsTheSystemResetAtTheDueMidnightOnly() {
        LineActivityService service = serviceAt("2026-01-17T10:00:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = lineWith(fraise, "2026-01-15T08:00:00Z");

        service.clear(line, admin);

        ArgumentCaptor<LineActivityChangeEntity> captor = ArgumentCaptor.forClass(LineActivityChangeEntity.class);
        verify(changeRepository).save(captor.capture());
        LineActivityChangeEntity reset = captor.getValue();
        assertThat(reset.getAuthorType()).isEqualTo(ActivityChangeAuthorType.SYSTEM);
        assertThat(reset.getPreviousActivity()).isSameAs(fraise);
        assertThat(reset.getNewActivity()).isNull();
        // Midnight after 15 January, Paris time (+01:00 in winter).
        assertThat(reset.getChangedAt().toInstant()).isEqualTo(Instant.parse("2026-01-15T23:00:00Z"));
        assertThat(line.getCurrentActivity()).isNull();
    }

    @Test
    void startNewDay_todaysChoice_isKept() {
        LineActivityService service = serviceAt("2026-01-15T10:00:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = lineWith(fraise, "2026-01-15T08:00:00Z");

        assertThat(service.startNewDay(line)).isFalse();

        assertThat(line.getCurrentActivity()).isSameAs(fraise);
        verify(changeRepository, never()).save(any());
    }

    @Test
    void startNewDay_yesterdaysChoice_withSeveralActivities_isClearedOnce() {
        LineActivityService service = serviceAt("2026-01-16T00:30:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = lineWith(fraise, "2026-01-15T08:00:00Z");
        associate(line, fraise, activity("Framboise"));

        assertThat(service.startNewDay(line)).isTrue();
        assertThat(service.startNewDay(line)).isFalse();

        verify(changeRepository, times(1)).save(any());
        assertThat(line.getCurrentActivity()).isNull();
        // Dated today's midnight: a restart later today does not start the day again.
        assertThat(line.getCurrentActivitySetAt().toInstant()).isEqualTo(Instant.parse("2026-01-15T23:00:00Z"));
    }

    // --- the day's default activity (Clarifications 2026-09-28) ---

    @Test
    void effectiveActivity_staleState_isTheOnlyActiveAssociatedActivity() {
        LineActivityService service = serviceAt("2026-01-16T00:00:01Z");
        ActivityEntity fraise = activity("Fraise");
        ActivityEntity disabled = activity("Disabled");
        disabled.setActive(false);
        ReaderEntity line = lineWith(fraise, "2026-01-15T08:00:00Z");

        associate(line, fraise, disabled);
        assertThat(service.effectiveActivity(line)).isSameAs(fraise);

        associate(line, fraise, activity("Framboise"));
        assertThat(service.effectiveActivity(line)).isNull();

        associate(line);
        assertThat(service.effectiveActivity(line)).isNull();
    }

    @Test
    void effectiveActivity_undatedLine_hasItsDefault_butNotARegistrationReader() {
        LineActivityService service = serviceAt("2026-01-16T10:00:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = ReaderEntity.builder().id(UUID.randomUUID()).name("L1").build();
        associate(line, fraise);

        assertThat(service.effectiveActivity(line)).isSameAs(fraise);

        line.setMode(ReaderMode.ENREGISTREMENT);
        assertThat(service.effectiveActivity(line)).isNull();
    }

    @Test
    void effectiveActivity_noneChosenToday_staysNone() {
        LineActivityService service = serviceAt("2026-01-16T10:00:00Z");
        ReaderEntity line = lineWith(null, "2026-01-16T09:00:00Z");
        associate(line, activity("Fraise"));

        assertThat(service.effectiveActivity(line)).isNull();
    }

    @Test
    void startNewDay_undatedLineWithoutActivity_getsItsDefault_loggedAtTodaysMidnight() {
        LineActivityService service = serviceAt("2026-01-16T10:00:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = ReaderEntity.builder().id(UUID.randomUUID()).name("L1").build();
        associate(line, fraise);

        assertThat(service.startNewDay(line)).isTrue();

        LineActivityChangeEntity change = savedChange();
        assertThat(change.getAuthorType()).isEqualTo(ActivityChangeAuthorType.SYSTEM);
        assertThat(change.getPreviousActivity()).isNull();
        assertThat(change.getNewActivity()).isSameAs(fraise);
        assertThat(change.getChangedAt().toInstant()).isEqualTo(Instant.parse("2026-01-15T23:00:00Z"));
        assertThat(line.getCurrentActivity()).isSameAs(fraise);
    }

    @Test
    void startNewDay_yesterdaysOtherChoice_isReplacedByTheDefault() {
        LineActivityService service = serviceAt("2026-01-16T00:30:00Z");
        ActivityEntity fraise = activity("Fraise");
        ActivityEntity framboise = activity("Framboise");
        ReaderEntity line = lineWith(fraise, "2026-01-15T08:00:00Z");
        associate(line, framboise);

        assertThat(service.startNewDay(line)).isTrue();

        LineActivityChangeEntity change = savedChange();
        assertThat(change.getPreviousActivity()).isSameAs(fraise);
        assertThat(change.getNewActivity()).isSameAs(framboise);
        assertThat(change.getChangedAt().toInstant()).isEqualTo(Instant.parse("2026-01-15T23:00:00Z"));
    }

    @Test
    void startNewDay_yesterdaysChoiceIsTheDefault_writesNothingButDatesItToday() {
        LineActivityService service = serviceAt("2026-01-16T00:30:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = lineWith(fraise, "2026-01-15T08:00:00Z");
        associate(line, fraise);

        assertThat(service.startNewDay(line)).isFalse();

        verify(changeRepository, never()).save(any());
        assertThat(line.getCurrentActivity()).isSameAs(fraise);
        assertThat(line.getCurrentActivitySetAt().toInstant()).isEqualTo(Instant.parse("2026-01-15T23:00:00Z"));
    }

    @Test
    void clear_datesTheStateOfNoActivity() {
        LineActivityService service = serviceAt("2026-01-15T10:00:00Z");
        ReaderEntity line = lineWith(activity("Fraise"), "2026-01-15T08:00:00Z");

        service.clear(line, admin);

        assertThat(line.getCurrentActivity()).isNull();
        assertThat(line.getCurrentActivitySetAt().toInstant()).isEqualTo(Instant.parse("2026-01-15T10:00:00Z"));
    }

    @Test
    void applyDefaultIfNone_lineWithoutActivity_getsTheSingleOne_byTheAdministrateur() {
        LineActivityService service = serviceAt("2026-01-15T10:00:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = lineWith(null, "2026-01-15T08:00:00Z");
        associate(line, fraise);

        service.applyDefaultIfNone(line, () -> admin);

        assertThat(line.getCurrentActivity()).isSameAs(fraise);
        LineActivityChangeEntity change = savedChange();
        assertThat(change.getAuthorType()).isEqualTo(ActivityChangeAuthorType.USER);
        assertThat(change.getAuthorUser()).isSameAs(admin);
        assertThat(change.getNewActivity()).isSameAs(fraise);
    }

    @Test
    void applyDefaultIfNone_neverChangesACurrentActivity_norChoosesAmongSeveral() {
        LineActivityService service = serviceAt("2026-01-15T10:00:00Z");
        ActivityEntity fraise = activity("Fraise");
        ActivityEntity framboise = activity("Framboise");
        ReaderEntity current = lineWith(fraise, "2026-01-15T08:00:00Z");
        associate(current, framboise);
        ReaderEntity several = lineWith(null, "2026-01-15T08:00:00Z");
        associate(several, fraise, framboise);

        service.applyDefaultIfNone(current, () -> admin);
        service.applyDefaultIfNone(several, () -> admin);

        assertThat(current.getCurrentActivity()).isSameAs(fraise);
        assertThat(several.getCurrentActivity()).isNull();
        verify(changeRepository, never()).save(any());
    }

    private void assertEffectiveAt(String setAt, String now, boolean effective) {
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = lineWith(fraise, setAt);

        ActivityEntity result = serviceAt(now).effectiveActivity(line);

        if (effective) {
            assertThat(result).as("at %s", now).isSameAs(fraise);
        } else {
            assertThat(result).as("at %s", now).isNull();
        }
    }

    private void associate(ReaderEntity line, ActivityEntity... activities) {
        when(activityRepository.findAllByLine(line.getId())).thenReturn(List.of(activities));
    }

    private LineActivityChangeEntity savedChange() {
        ArgumentCaptor<LineActivityChangeEntity> captor = ArgumentCaptor.forClass(LineActivityChangeEntity.class);
        verify(changeRepository).save(captor.capture());
        return captor.getValue();
    }

    private LineActivityService serviceAt(String instant) {
        return new LineActivityService(Mockito.mock(ReaderRepository.class), activityRepository,
                Mockito.mock(RecordRepository.class), changeRepository, Mockito.mock(UserRepository.class),
                new StationProperties(PARIS), Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    private static ReaderEntity lineWith(ActivityEntity activity, String setAt) {
        ReaderEntity line = ReaderEntity.builder().id(UUID.randomUUID()).name("L1").build();
        line.setCurrentActivity(activity);
        line.setCurrentActivitySetAt(OffsetDateTime.parse(setAt));
        return line;
    }

    private static ActivityEntity activity(String name) {
        return ActivityEntity.builder().id(UUID.randomUUID()).name(name).build();
    }
}
