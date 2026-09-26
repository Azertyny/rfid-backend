package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.rfidback.configuration.StationProperties;
import com.rfidback.entity.ActivityChangeAuthorType;
import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.LineActivityChangeEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.LineActivityChangeRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.UserRepository;

/** The midnight rule, station time Europe/Paris (spec 012, FR-008a, research R3). */
class LineActivityServiceTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private final LineActivityChangeRepository changeRepository = Mockito.mock(LineActivityChangeRepository.class);

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

        service.clear(line, UserEntity.builder().username("admin").build());

        verify(changeRepository, never()).save(any());
    }

    @Test
    void clear_overAStaleChoice_logsTheSystemResetAtTheDueMidnightOnly() {
        LineActivityService service = serviceAt("2026-01-17T10:00:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = lineWith(fraise, "2026-01-15T08:00:00Z");

        service.clear(line, UserEntity.builder().username("admin").build());

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
    void resetIfStale_todaysChoice_isKept() {
        LineActivityService service = serviceAt("2026-01-15T10:00:00Z");
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity line = lineWith(fraise, "2026-01-15T08:00:00Z");

        assertThat(service.resetIfStale(line)).isFalse();

        assertThat(line.getCurrentActivity()).isSameAs(fraise);
        verify(changeRepository, never()).save(any());
    }

    @Test
    void resetIfStale_yesterdaysChoice_isClearedOnce() {
        LineActivityService service = serviceAt("2026-01-16T00:30:00Z");
        ReaderEntity line = lineWith(activity("Fraise"), "2026-01-15T08:00:00Z");

        assertThat(service.resetIfStale(line)).isTrue();
        assertThat(service.resetIfStale(line)).isFalse();

        verify(changeRepository, times(1)).save(any());
        assertThat(line.getCurrentActivitySetAt()).isNull();
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

    private LineActivityService serviceAt(String instant) {
        return new LineActivityService(Mockito.mock(ReaderRepository.class), Mockito.mock(ActivityRepository.class),
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
