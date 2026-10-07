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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.configuration.StationProperties;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.exception.ReaderNotFoundException;
import com.rfidback.generated.model.ActivityColumn;
import com.rfidback.generated.model.ActivityCount;
import com.rfidback.generated.model.HourStats;
import com.rfidback.generated.model.PickerStats;
import com.rfidback.generated.model.RecordCounts;
import com.rfidback.generated.model.RecordStats;
import com.rfidback.generated.model.StatsPeriod;
import com.rfidback.repository.PickerWorkDayRepository;
import com.rfidback.repository.PickerWorkDayRepository.PickerMinutesView;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RecordRepository.CountsView;
import com.rfidback.repository.RecordRepository.HourCountsView;
import com.rfidback.repository.RecordRepository.PickerActivityCountsView;
import com.rfidback.repository.RecordRepository.PickerCountsView;
import com.rfidback.repository.ReaderRepository;

/**
 * Dashboard statistics (spec 007, FR-002, FR-007 to FR-009): totals computed by the database for a period of days in
 * the station time zone. Hours are grouped by UTC hour in SQL and mapped here to the station's hour of day, which
 * stays exact across daylight-saving changes for a zone with whole-hour offsets (research R4). Spec 014 adds, per
 * picker, the records per activity and the work hours entered by the Administrateur; hours are not per line, so they
 * are only given without a reader.
 */
@Service
public class RecordStatsService {

    static final int MAX_DAYS = 31;
    private static final int HOURS_PER_DAY = 24;
    private static final long SECONDS_PER_HOUR = 3600;
    private static final double MINUTES_PER_HOUR = 60.0;

    private final RecordRepository recordRepository;
    private final ReaderRepository readerRepository;
    private final PickerWorkDayRepository workDayRepository;
    private final Clock clock;
    private final ZoneId zone;

    public RecordStatsService(RecordRepository recordRepository, ReaderRepository readerRepository,
            PickerWorkDayRepository workDayRepository, Clock clock, StationProperties stationProperties) {
        this.recordRepository = recordRepository;
        this.readerRepository = readerRepository;
        this.workDayRepository = workDayRepository;
        this.clock = clock;
        this.zone = stationProperties.timeZone();
    }

    @Transactional(readOnly = true)
    public RecordStats getStats(StatsPeriod period, LocalDate from, LocalDate to, UUID readerId) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate[] days = resolveDays(period == null ? StatsPeriod.TODAY : period, from, to, today);
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
        List<PickerActivityCountsView> activityRows = reader == null
                ? recordRepository.countByPickerAndActivity(start, end)
                : recordRepository.countByPickerAndActivityForReader(start, end, reader);
        // Hours are entered per picker and day, not per line (spec 014, FR-011).
        List<PickerMinutesView> minuteRows = reader == null ? workDayRepository.sumMinutesByPicker(first, last)
                : List.of();

        List<ActivityColumn> columns = toActivityColumns(activityRows);
        List<PickerStats> pickers = toPickerStats(pickerRows);
        addActivityCounts(pickers, activityRows, columns);

        RecordStats stats = new RecordStats();
        stats.setFrom(first);
        stats.setTo(last);
        stats.setTimeZone(zone.getId());
        stats.setReaderId(readerId);
        stats.setSummary(toCounts(summary));
        if (reader == null) {
            stats.setWorkHours(addWorkHours(pickers, minuteRows));
        }
        stats.setPickers(sorted(pickers));
        stats.setHours(toHourStats(hourRows));
        stats.setActivities(columns);
        stats.setIncludesToday(!today.isBefore(first) && !today.isAfter(last));
        return stats;
    }

    private LocalDate[] resolveDays(StatsPeriod period, LocalDate from, LocalDate to, LocalDate today) {
        if (period != StatsPeriod.CUSTOM) {
            if (from != null || to != null) {
                throw badRequest("from and to are only allowed with period CUSTOM");
            }
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

    private static List<PickerStats> toPickerStats(List<PickerCountsView> rows) {
        List<PickerStats> pickers = new ArrayList<>(rows.size());
        for (PickerCountsView row : rows) {
            PickerStats picker = new PickerStats();
            picker.setPickerId(row.getPickerId());
            picker.setFirstname(row.getFirstname());
            picker.setLastname(row.getLastname());
            picker.setTotal(toLong(row.getTotal()));
            picker.setNonCompliant(toLong(row.getNonCompliant()));
            picker.setActivities(new ArrayList<>());
            pickers.add(picker);
        }
        return pickers;
    }

    // By last name then first name as a French reader expects (accents, case); the records without a picker last.
    private static List<PickerStats> sorted(List<PickerStats> pickers) {
        Collator collator = Collator.getInstance(Locale.FRENCH);
        Comparator<String> byText = Comparator.nullsLast(collator::compare);
        List<PickerStats> sorted = new ArrayList<>(pickers);
        sorted.sort(Comparator.comparing((PickerStats picker) -> picker.getPickerId() == null)
                .thenComparing(PickerStats::getLastname, byText)
                .thenComparing(PickerStats::getFirstname, byText)
                .thenComparing(picker -> String.valueOf(picker.getPickerId())));
        return sorted;
    }

    // The activities carried by the period's records, by name, then "Sans activité" (null) when some carry none.
    private static List<ActivityColumn> toActivityColumns(List<PickerActivityCountsView> rows) {
        Map<UUID, String> names = new HashMap<>();
        boolean withoutActivity = false;
        for (PickerActivityCountsView row : rows) {
            if (row.getActivityId() == null) {
                withoutActivity = true;
            } else {
                names.put(row.getActivityId(), row.getActivityName());
            }
        }
        Collator collator = Collator.getInstance(Locale.FRENCH);
        List<ActivityColumn> columns = new ArrayList<>();
        names.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<UUID, String> entry) -> entry.getValue(),
                        Comparator.nullsLast(collator::compare)).thenComparing(entry -> entry.getKey().toString()))
                .forEach(entry -> columns.add(column(entry.getKey(), entry.getValue())));
        if (withoutActivity) {
            columns.add(column(null, null));
        }
        return columns;
    }

    private static ActivityColumn column(UUID activityId, String name) {
        ActivityColumn column = new ActivityColumn();
        column.setActivityId(activityId);
        column.setName(name);
        return column;
    }

    // Each picker row gets its non-zero activity counts, in column order; they add up to its total.
    private static void addActivityCounts(List<PickerStats> pickers, List<PickerActivityCountsView> rows,
            List<ActivityColumn> columns) {
        Map<UUID, Integer> columnIndex = new HashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            columnIndex.put(columns.get(i).getActivityId(), i);
        }
        Map<UUID, PickerStats> byPicker = new HashMap<>();
        pickers.forEach(picker -> byPicker.put(picker.getPickerId(), picker));
        for (PickerActivityCountsView row : rows) {
            PickerStats picker = byPicker.get(row.getPickerId());
            long total = toLong(row.getTotal());
            if (picker == null || total == 0) {
                continue;
            }
            ActivityCount count = new ActivityCount();
            count.setActivityId(row.getActivityId());
            count.setTotal(total);
            picker.getActivities().add(count);
        }
        pickers.forEach(picker -> picker.getActivities()
                .sort(Comparator.comparing((ActivityCount count) -> columnIndex.get(count.getActivityId()))));
    }

    // Hours of each picker over the period; a picker with hours and no record joins the rows with zero counts
    // (spec 014, FR-009). Returns the hours of all pickers, the Total row's value.
    private static double addWorkHours(List<PickerStats> pickers, List<PickerMinutesView> rows) {
        Map<UUID, PickerStats> byPicker = new LinkedHashMap<>();
        pickers.stream().filter(picker -> picker.getPickerId() != null)
                .forEach(picker -> byPicker.put(picker.getPickerId(), picker));
        long totalMinutes = 0;
        for (PickerMinutesView row : rows) {
            long minutes = toLong(row.getMinutes());
            totalMinutes += minutes;
            PickerStats picker = byPicker.get(row.getPickerId());
            if (picker == null) {
                picker = new PickerStats();
                picker.setPickerId(row.getPickerId());
                picker.setFirstname(row.getFirstname());
                picker.setLastname(row.getLastname());
                picker.setTotal(0L);
                picker.setNonCompliant(0L);
                picker.setActivities(new ArrayList<>());
                pickers.add(picker);
            }
            picker.setWorkHours(minutes / MINUTES_PER_HOUR);
        }
        return totalMinutes / MINUTES_PER_HOUR;
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
