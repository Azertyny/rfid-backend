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
import com.rfidback.generated.model.HourStats;
import com.rfidback.generated.model.PickerStats;
import com.rfidback.generated.model.RecordStats;
import com.rfidback.generated.model.StatsPeriod;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RecordRepository.CountsView;
import com.rfidback.repository.RecordRepository.HourCountsView;
import com.rfidback.repository.RecordRepository.PickerCountsView;

/** Period resolution, validation and hour mapping of the dashboard statistics (spec 007). */
class RecordStatsServiceTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private RecordRepository recordRepository;
    private ReaderRepository readerRepository;

    @BeforeEach
    void setUp() {
        recordRepository = Mockito.mock(RecordRepository.class);
        readerRepository = Mockito.mock(ReaderRepository.class);
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

    private RecordStatsService serviceAt(String instant) {
        Clock clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
        return new RecordStatsService(recordRepository, readerRepository, clock, new StationProperties(PARIS));
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
}
