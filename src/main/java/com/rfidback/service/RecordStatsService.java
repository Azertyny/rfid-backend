package com.rfidback.service;

import java.text.Collator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.configuration.StationProperties;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.generated.model.HourStats;
import com.rfidback.generated.model.PickerStats;
import com.rfidback.generated.model.RecordCounts;
import com.rfidback.generated.model.RecordStats;
import com.rfidback.generated.model.StatsPeriod;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RecordRepository.CountsView;
import com.rfidback.repository.RecordRepository.HourCountsView;
import com.rfidback.repository.RecordRepository.PickerCountsView;
import com.rfidback.repository.ReaderRepository;

/**
 * Dashboard statistics (spec 007, FR-002, FR-007 to FR-009): totals computed by the database for a period of days in
 * the station time zone. Hours are grouped by UTC hour in SQL and mapped here to the station's hour of day, which
 * stays exact across daylight-saving changes for a zone with whole-hour offsets (research R4).
 */
@Service
public class RecordStatsService {

    static final int MAX_DAYS = 31;
    private static final int HOURS_PER_DAY = 24;
    private static final long SECONDS_PER_HOUR = 3600;

    private final RecordRepository recordRepository;
    private final ReaderRepository readerRepository;
    private final Clock clock;
    private final ZoneId zone;

    public RecordStatsService(RecordRepository recordRepository, ReaderRepository readerRepository, Clock clock,
            StationProperties stationProperties) {
        this.recordRepository = recordRepository;
        this.readerRepository = readerRepository;
        this.clock = clock;
        this.zone = stationProperties.timeZone();
    }

    @Transactional(readOnly = true)
    public RecordStats getStats(StatsPeriod period, LocalDate from, LocalDate to, UUID readerId) {
        LocalDate[] days = resolveDays(period == null ? StatsPeriod.TODAY : period, from, to);
        LocalDate first = days[0];
        LocalDate last = days[1];
        ReaderEntity reader = readerId == null ? null
                : readerRepository.findById(readerId)
                        .orElseThrow(() -> new ReaderNotFoundException("Reader %s not found".formatted(readerId)));

        // Half-open range of instants: a 23- or 25-hour day at a daylight-saving change is covered exactly.
        OffsetDateTime start = first.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = last.plusDays(1).atStartOfDay(zone).toOffsetDateTime();

        CountsView summary = reader == null ? recordRepository.countSummary(start, end)
                : recordRepository.countSummaryForReader(start, end, reader);
        List<PickerCountsView> pickerRows = reader == null ? recordRepository.countByPicker(start, end)
                : recordRepository.countByPickerForReader(start, end, reader);
        List<HourCountsView> hourRows = reader == null ? recordRepository.countByUtcHour(start, end)
                : recordRepository.countByUtcHourForReader(start, end, reader);

        RecordStats stats = new RecordStats();
        stats.setFrom(first);
        stats.setTo(last);
        stats.setTimeZone(zone.getId());
        stats.setReaderId(readerId);
        stats.setSummary(toCounts(summary));
        stats.setPickers(toPickerStats(pickerRows));
        stats.setHours(toHourStats(hourRows));
        return stats;
    }

    private LocalDate[] resolveDays(StatsPeriod period, LocalDate from, LocalDate to) {
        if (period != StatsPeriod.CUSTOM) {
            if (from != null || to != null) {
                throw badRequest("from and to are only allowed with period CUSTOM");
            }
            LocalDate today = LocalDate.now(clock.withZone(zone));
            return switch (period) {
                case TODAY -> new LocalDate[] { today, today };
                case YESTERDAY -> new LocalDate[] { today.minusDays(1), today.minusDays(1) };
                case LAST_7_DAYS -> new LocalDate[] { today.minusDays(6), today };
                default -> throw new IllegalStateException("Unexpected period " + period);
            };
        }
        if (from == null || to == null) {
            throw badRequest("from and to are required with period CUSTOM");
        }
        if (from.isAfter(to)) {
            throw badRequest("from must not be after to");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_DAYS) {
            throw badRequest("The period must not exceed %d days".formatted(MAX_DAYS));
        }
        return new LocalDate[] { from, to };
    }

    private static RecordCounts toCounts(CountsView row) {
        RecordCounts counts = new RecordCounts();
        counts.setTotal(row == null ? 0 : toLong(row.getTotal()));
        counts.setNonCompliant(row == null ? 0 : toLong(row.getNonCompliant()));
        return counts;
    }

    // By last name then first name as a French reader expects (accents, case); the records without a picker last.
    private static List<PickerStats> toPickerStats(List<PickerCountsView> rows) {
        Collator collator = Collator.getInstance(Locale.FRENCH);
        Comparator<String> byText = Comparator.nullsLast(collator::compare);
        List<PickerCountsView> sorted = new ArrayList<>(rows);
        sorted.sort(Comparator.comparing((PickerCountsView row) -> row.getPickerId() == null)
                .thenComparing(PickerCountsView::getLastname, byText)
                .thenComparing(PickerCountsView::getFirstname, byText)
                .thenComparing(row -> String.valueOf(row.getPickerId())));

        List<PickerStats> pickers = new ArrayList<>(sorted.size());
        for (PickerCountsView row : sorted) {
            PickerStats picker = new PickerStats();
            picker.setPickerId(row.getPickerId());
            picker.setFirstname(row.getFirstname());
            picker.setLastname(row.getLastname());
            picker.setTotal(toLong(row.getTotal()));
            picker.setNonCompliant(toLong(row.getNonCompliant()));
            pickers.add(picker);
        }
        return pickers;
    }

    private List<HourStats> toHourStats(List<HourCountsView> rows) {
        long[] totals = new long[HOURS_PER_DAY];
        long[] nonCompliant = new long[HOURS_PER_DAY];
        for (HourCountsView row : rows) {
            int hour = Instant.ofEpochSecond(toLong(row.getHourIndex()) * SECONDS_PER_HOUR).atZone(zone).getHour();
            totals[hour] += toLong(row.getTotal());
            nonCompliant[hour] += toLong(row.getNonCompliant());
        }
        List<HourStats> hours = new ArrayList<>(HOURS_PER_DAY);
        for (int hour = 0; hour < HOURS_PER_DAY; hour++) {
            HourStats stats = new HourStats();
            stats.setHour(hour);
            stats.setTotal(totals[hour]);
            stats.setNonCompliant(nonCompliant[hour]);
            hours.add(stats);
        }
        return hours;
    }

    private static long toLong(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
