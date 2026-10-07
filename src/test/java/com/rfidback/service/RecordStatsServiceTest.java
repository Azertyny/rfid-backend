package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.configuration.StationProperties;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.generated.model.ActivityColumn;
import com.rfidback.generated.model.ActivityCount;
import com.rfidback.generated.model.HourStats;
import com.rfidback.generated.model.PickerStats;
import com.rfidback.generated.model.RecordStats;
import com.rfidback.generated.model.StatsPeriod;
import com.rfidback.repository.PickerWorkDayRepository;
import com.rfidback.repository.PickerWorkDayRepository.PickerMinutesView;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RecordRepository.CountsView;
import com.rfidback.repository.RecordRepository.HourCountsView;
import com.rfidback.repository.RecordRepository.PickerActivityCountsView;
import com.rfidback.repository.RecordRepository.PickerCountsView;

/**
 * Period resolution, validation and hour mapping of the dashboard statistics (spec 007); activity columns and work
 * hours per picker (spec 014).
 */
class RecordStatsServiceTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private RecordRepository recordRepository;
    private ReaderRepository readerRepository;
    private PickerWorkDayRepository workDayRepository;

    @BeforeEach
    void setUp() {
        recordRepository = Mockito.mock(RecordRepository.class);
        readerRepository = Mockito.mock(ReaderRepository.class);
        workDayRepository = Mockito.mock(PickerWorkDayRepository.class);
        when(recordRepository.countSummary(any(), any())).thenReturn(counts(0, 0));
        when(recordRepository.countByPicker(any(), any())).thenReturn(List.of());
        when(recordRepository.countByUtcHour(any(), any())).thenReturn(List.of());
    }

    // --- period resolution (FR-007) ---

    @Test
    void todayIsTheDefaultPeriodInTheStationZone() {
        RecordStats stats = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null);

        assertEquals(LocalDate.of(2026, 9, 25), stats.getFrom());
        assertEquals(LocalDate.of(2026, 9, 25), stats.getTo());
        assertEquals("Europe/Paris", stats.getTimeZone());
    }

    @Test
    void todayFollowsTheStationZoneNotUtc() {
        // 22:30 UTC is 00:30 in Paris on the next day.
        RecordStats stats = serviceAt("2026-09-25T22:30:00Z").getStats(StatsPeriod.TODAY, null, null, null);

        assertEquals(LocalDate.of(2026, 9, 26), stats.getFrom());
        assertEquals(LocalDate.of(2026, 9, 26), stats.getTo());
    }

    @Test
    void yesterdayAndLastSevenDays() {
        RecordStatsService service = serviceAt("2026-09-25T10:00:00Z");

        RecordStats yesterday = service.getStats(StatsPeriod.YESTERDAY, null, null, null);
        RecordStats week = service.getStats(StatsPeriod.LAST_7_DAYS, null, null, null);

        assertEquals(LocalDate.of(2026, 9, 24), yesterday.getFrom());
        assertEquals(LocalDate.of(2026, 9, 24), yesterday.getTo());
        assertEquals(LocalDate.of(2026, 9, 19), week.getFrom());
        assertEquals(LocalDate.of(2026, 9, 25), week.getTo());
    }

    @Test
    void customPeriodIsKeptAndQueriedFromMidnightToTheNextMidnightInParis() {
        RecordStats stats = serviceAt("2026-09-25T10:00:00Z")
                .getStats(StatsPeriod.CUSTOM, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30), null);

        assertEquals(LocalDate.of(2026, 9, 1), stats.getFrom());
        assertEquals(LocalDate.of(2026, 9, 30), stats.getTo());
        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(recordRepository).countSummary(start.capture(), end.capture());
        assertEquals(Instant.parse("2026-08-31T22:00:00Z"), start.getValue().toInstant());
        assertEquals(Instant.parse("2026-09-30T22:00:00Z"), end.getValue().toInstant());
    }

    @Test
    void theAutumnChangeDayLasts25Hours() {
        serviceAt("2026-09-25T10:00:00Z")
                .getStats(StatsPeriod.CUSTOM, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 25), null);

        ArgumentCaptor<OffsetDateTime> start = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> end = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(recordRepository).countSummary(start.capture(), end.capture());
        assertEquals(Duration.ofHours(25), Duration.between(start.getValue(), end.getValue()));
    }

    // --- validation (FR-007) ---

    @Test
    void customNeedsBothDates() {
        RecordStatsService service = serviceAt("2026-09-25T10:00:00Z");

        assertBadRequest(() -> service.getStats(StatsPeriod.CUSTOM, null, LocalDate.of(2026, 1, 1), null));
        assertBadRequest(() -> service.getStats(StatsPeriod.CUSTOM, LocalDate.of(2026, 1, 1), null, null));
    }

    @Test
    void fromMustNotBeAfterTo() {
        assertBadRequest(() -> serviceAt("2026-09-25T10:00:00Z")
                .getStats(StatsPeriod.CUSTOM, LocalDate.of(2026, 1, 2), LocalDate.of(2026, 1, 1), null));
    }

    @Test
    void customAcceptsThirtyOneDaysAndRefusesThirtyTwo() {
        RecordStatsService service = serviceAt("2026-09-25T10:00:00Z");

        service.getStats(StatsPeriod.CUSTOM, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), null);
        assertBadRequest(() -> service.getStats(StatsPeriod.CUSTOM, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 2, 1), null));
    }

    @Test
    void datesAreRefusedWithANamedPeriod() {
        assertBadRequest(() -> serviceAt("2026-09-25T10:00:00Z")
                .getStats(StatsPeriod.TODAY, LocalDate.of(2026, 1, 1), null, null));
    }

    // --- reader filter ---

    @Test
    void unknownReaderIsNotFoundAndNothingIsCounted() {
        UUID readerId = UUID.randomUUID();
        when(readerRepository.findById(readerId)).thenReturn(Optional.empty());

        assertThrows(ReaderNotFoundException.class,
                () -> serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, readerId));
        verify(recordRepository, never()).countSummary(any(), any());
        verify(recordRepository, never()).countSummaryForReader(any(), any(), any());
    }

    @Test
    void aReaderUsesTheReaderQueriesOnly() {
        UUID readerId = UUID.randomUUID();
        ReaderEntity reader = ReaderEntity.builder().id(readerId).name("Reader 1").build();
        when(readerRepository.findById(readerId)).thenReturn(Optional.of(reader));
        when(recordRepository.countSummaryForReader(any(), any(), any())).thenReturn(counts(4, 1));
        when(recordRepository.countByPickerForReader(any(), any(), any())).thenReturn(List.of());
        when(recordRepository.countByUtcHourForReader(any(), any(), any())).thenReturn(List.of());

        RecordStats stats = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, readerId);

        assertEquals(readerId, stats.getReaderId());
        assertEquals(4L, stats.getSummary().getTotal());
        verify(recordRepository).countByPickerForReader(any(), any(), any());
        verify(recordRepository).countByUtcHourForReader(any(), any(), any());
        verify(recordRepository, never()).countSummary(any(), any());
        verify(recordRepository, never()).countByPicker(any(), any());
        verify(recordRepository, never()).countByUtcHour(any(), any());
    }

    // --- hours (FR-007, research R4) ---

    @Test
    void utcHoursAreMappedToParisHoursAcrossDaylightSavingChanges() {
        when(recordRepository.countByUtcHour(any(), any())).thenReturn(List.of(
                hourRow("2026-03-29T00:00:00Z", 1, 0), // 01h CET
                hourRow("2026-03-29T01:00:00Z", 2, 1), // 03h CEST, 02h does not exist that day
                hourRow("2026-10-25T00:00:00Z", 3, 0), // 02h CEST
                hourRow("2026-10-25T01:00:00Z", 4, 2))); // 02h CET, the repeated hour

        List<HourStats> hours = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null).getHours();

        assertThat(hours).hasSize(24);
        assertThat(hours).extracting(HourStats::getHour).containsExactlyElementsOf(
                IntStream.range(0, 24).boxed().toList());
        assertEquals(1L, hours.get(1).getTotal());
        assertEquals(7L, hours.get(2).getTotal());
        assertEquals(2L, hours.get(2).getNonCompliant());
        assertEquals(2L, hours.get(3).getTotal());
        assertEquals(1L, hours.get(3).getNonCompliant());
        assertEquals(0L, hours.get(0).getTotal());
    }

    // --- pickers (FR-009) ---

    @Test
    void pickersAreSortedByNameWithTheUnassignedRowLast() {
        UUID moreau = UUID.randomUUID();
        UUID diallo = UUID.randomUUID();
        UUID ecole = UUID.randomUUID();
        when(recordRepository.countByPicker(any(), any())).thenReturn(List.of(
                pickerRow(null, null, null, 3, 1),
                pickerRow(moreau, "Valérie", "Moreau", 5, 0),
                pickerRow(ecole, "Anne", "École", 2, 0),
                pickerRow(diallo, "Amadou", "Diallo", 4, 2)));

        List<PickerStats> pickers = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null).getPickers();

        assertThat(pickers).extracting(PickerStats::getPickerId).containsExactly(diallo, ecole, moreau, null);
        assertEquals(3L, pickers.get(3).getTotal());
        assertEquals(1L, pickers.get(3).getNonCompliant());
    }

    @Test
    void anEmptyPeriodGivesZeros() {
        when(recordRepository.countSummary(any(), any())).thenReturn(counts(0, null));

        RecordStats stats = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null);

        assertEquals(0L, stats.getSummary().getTotal());
        assertEquals(0L, stats.getSummary().getNonCompliant());
        assertThat(stats.getPickers()).isEmpty();
        assertThat(stats.getHours()).hasSize(24).allMatch(hour -> hour.getTotal() == 0);
    }

    // --- activity columns and work hours (spec 014) ---

    @Test
    void activityColumnsAreSortedByNameWithoutActivityLast() {
        UUID fraise = UUID.randomUUID();
        UUID ecole = UUID.randomUUID();
        UUID picker = UUID.randomUUID();
        when(recordRepository.countByPicker(any(), any())).thenReturn(List.of(pickerRow(picker, "A", "Diallo", 6, 0)));
        when(recordRepository.countByPickerAndActivity(any(), any())).thenReturn(List.of(
                activityRow(picker, null, null, 1),
                activityRow(picker, fraise, "Fraise", 3),
                activityRow(picker, ecole, "Échalote", 2)));

        RecordStats stats = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null);

        assertThat(stats.getActivities()).extracting(ActivityColumn::getName).containsExactly("Échalote", "Fraise", null);
        assertThat(stats.getActivities()).extracting(ActivityColumn::getActivityId).containsExactly(ecole, fraise, null);
        List<ActivityCount> counts = stats.getPickers().get(0).getActivities();
        assertThat(counts).extracting(ActivityCount::getActivityId).containsExactly(ecole, fraise, null);
        assertThat(counts).extracting(ActivityCount::getTotal).containsExactly(2L, 3L, 1L);
        assertEquals(stats.getPickers().get(0).getTotal(),
                counts.stream().mapToLong(ActivityCount::getTotal).sum());
    }

    @Test
    void noSansActiviteColumnWhenEveryRecordHasAnActivity() {
        UUID fraise = UUID.randomUUID();
        UUID picker = UUID.randomUUID();
        when(recordRepository.countByPicker(any(), any())).thenReturn(List.of(pickerRow(picker, "A", "Diallo", 3, 0)));
        when(recordRepository.countByPickerAndActivity(any(), any()))
                .thenReturn(List.of(activityRow(picker, fraise, "Fraise", 3)));

        RecordStats stats = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null);

        assertThat(stats.getActivities()).extracting(ActivityColumn::getActivityId).containsExactly(fraise);
    }

    @Test
    void theUnassignedRowGetsItsActivitiesButNoHours() {
        UUID fraise = UUID.randomUUID();
        when(recordRepository.countByPicker(any(), any())).thenReturn(List.of(pickerRow(null, null, null, 2, 0)));
        when(recordRepository.countByPickerAndActivity(any(), any()))
                .thenReturn(List.of(activityRow(null, fraise, "Fraise", 2)));

        PickerStats unassigned = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null)
                .getPickers().get(0);

        assertThat(unassigned.getActivities()).extracting(ActivityCount::getTotal).containsExactly(2L);
        assertThat(unassigned.getWorkHours()).isNull();
    }

    @Test
    void hoursAreSummedOverThePeriodAndAddUpToTheTotal() {
        UUID diallo = UUID.randomUUID();
        UUID moreau = UUID.randomUUID();
        when(recordRepository.countByPicker(any(), any())).thenReturn(List.of(
                pickerRow(diallo, "Amadou", "Diallo", 60, 0), pickerRow(moreau, "Valérie", "Moreau", 4, 0)));
        // 240 + 480 minutes over two days, summed by the query.
        when(workDayRepository.sumMinutesByPicker(LocalDate.of(2026, 9, 19), LocalDate.of(2026, 9, 25)))
                .thenReturn(List.of(minutesRow(diallo, "Amadou", "Diallo", 720L)));

        RecordStats stats = serviceAt("2026-09-25T10:00:00Z").getStats(StatsPeriod.LAST_7_DAYS, null, null, null);

        assertEquals(12.0, stats.getPickers().get(0).getWorkHours());
        assertThat(stats.getPickers().get(1).getWorkHours()).isNull();
        assertEquals(12.0, stats.getWorkHours());
    }

    @Test
    void aPickerWithHoursAndNoRecordJoinsTheRowsInOrder() {
        UUID diallo = UUID.randomUUID();
        UUID ecole = UUID.randomUUID();
        UUID moreau = UUID.randomUUID();
        when(recordRepository.countByPicker(any(), any())).thenReturn(List.of(
                pickerRow(null, null, null, 1, 0),
                pickerRow(moreau, "Valérie", "Moreau", 4, 0),
                pickerRow(diallo, "Amadou", "Diallo", 3, 0)));
        when(workDayRepository.sumMinutesByPicker(any(), any())).thenReturn(List.of(
                minutesRow(ecole, "Anne", "École", 450L), minutesRow(diallo, "Amadou", "Diallo", 480L)));

        RecordStats stats = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null);

        List<PickerStats> pickers = stats.getPickers();
        assertThat(pickers).extracting(PickerStats::getPickerId).containsExactly(diallo, ecole, moreau, null);
        PickerStats hoursOnly = pickers.get(1);
        assertEquals(0L, hoursOnly.getTotal());
        assertEquals(0L, hoursOnly.getNonCompliant());
        assertThat(hoursOnly.getActivities()).isEmpty();
        assertEquals(7.5, hoursOnly.getWorkHours());
        assertEquals(15.5, stats.getWorkHours());
        // The rows still add up to the summary: the added row counts nothing.
        assertEquals(8L, pickers.stream().mapToLong(PickerStats::getTotal).sum());
    }

    @Test
    void withoutAnyHoursTheTotalIsZero() {
        assertEquals(0.0, serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, null).getWorkHours());
    }

    @Test
    void aReaderGivesNoHoursAndUsesTheReaderActivityQuery() {
        UUID readerId = UUID.randomUUID();
        UUID picker = UUID.randomUUID();
        when(readerRepository.findById(readerId))
                .thenReturn(Optional.of(ReaderEntity.builder().id(readerId).name("Reader 1").build()));
        when(recordRepository.countSummaryForReader(any(), any(), any())).thenReturn(counts(2, 0));
        when(recordRepository.countByPickerForReader(any(), any(), any()))
                .thenReturn(List.of(pickerRow(picker, "A", "Diallo", 2, 0)));

        RecordStats stats = serviceAt("2026-09-25T10:00:00Z").getStats(null, null, null, readerId);

        assertThat(stats.getWorkHours()).isNull();
        assertThat(stats.getPickers()).hasSize(1).allMatch(row -> row.getWorkHours() == null);
        verify(workDayRepository, never()).sumMinutesByPicker(any(), any());
        verify(recordRepository).countByPickerAndActivityForReader(any(), any(), any());
        verify(recordRepository, never()).countByPickerAndActivity(any(), any());
    }

    @Test
    void includesTodayFollowsThePeriod() {
        RecordStatsService service = serviceAt("2026-09-25T10:00:00Z");

        assertThat(service.getStats(StatsPeriod.TODAY, null, null, null).getIncludesToday()).isTrue();
        assertThat(service.getStats(StatsPeriod.LAST_7_DAYS, null, null, null).getIncludesToday()).isTrue();
        assertThat(service.getStats(StatsPeriod.YESTERDAY, null, null, null).getIncludesToday()).isFalse();
        assertThat(service.getStats(StatsPeriod.CUSTOM, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 24), null)
                .getIncludesToday()).isFalse();
        assertThat(service.getStats(StatsPeriod.CUSTOM, LocalDate.of(2026, 9, 20), LocalDate.of(2026, 9, 25), null)
                .getIncludesToday()).isTrue();
    }

    private RecordStatsService serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new RecordStatsService(recordRepository, readerRepository, workDayRepository, clock,
                new StationProperties(PARIS));
    }

    private static void assertBadRequest(Executable call) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, call);
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private static CountsView counts(Number total, Number nonCompliant) {
        return new CountsView() {
            @Override
            public Number getTotal() {
                return total;
            }

            @Override
            public Number getNonCompliant() {
                return nonCompliant;
            }
        };
    }

    private static HourCountsView hourRow(String utcHour, long total, long nonCompliant) {
        long hourIndex = Instant.parse(utcHour).getEpochSecond() / 3600;
        return new HourCountsView() {
            @Override
            public Number getHourIndex() {
                // Some databases return floor() as a decimal.
                return (double) hourIndex;
            }

            @Override
            public Number getTotal() {
                return total;
            }

            @Override
            public Number getNonCompliant() {
                return nonCompliant;
            }
        };
    }

    private static PickerCountsView pickerRow(UUID id, String firstname, String lastname, long total,
            long nonCompliant) {
        return new PickerCountsView() {
            @Override
            public UUID getPickerId() {
                return id;
            }

            @Override
            public String getFirstname() {
                return firstname;
            }

            @Override
            public String getLastname() {
                return lastname;
            }

            @Override
            public Number getTotal() {
                return total;
            }

            @Override
            public Number getNonCompliant() {
                return nonCompliant;
            }
        };
    }

    private static PickerActivityCountsView activityRow(UUID pickerId, UUID activityId, String activityName,
            long total) {
        return new PickerActivityCountsView() {
            @Override
            public UUID getPickerId() {
                return pickerId;
            }

            @Override
            public UUID getActivityId() {
                return activityId;
            }

            @Override
            public String getActivityName() {
                return activityName;
            }

            @Override
            public Number getTotal() {
                return total;
            }
        };
    }

    private static PickerMinutesView minutesRow(UUID pickerId, String firstname, String lastname, Number minutes) {
        return new PickerMinutesView() {
            @Override
            public UUID getPickerId() {
                return pickerId;
            }

            @Override
            public String getFirstname() {
                return firstname;
            }

            @Override
            public String getLastname() {
                return lastname;
            }

            @Override
            public Number getMinutes() {
                return minutes;
            }
        };
    }
}
