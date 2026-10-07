package com.rfidback.service;

import java.text.Collator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.configuration.StationProperties;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.PickerWorkDayEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.PickerNotFoundException;
import com.rfidback.generated.model.SaveWorkDayRequest;
import com.rfidback.generated.model.WorkDay;
import com.rfidback.generated.model.WorkDayPicker;
import com.rfidback.generated.model.WorkHoursEntry;
import com.rfidback.repository.PickerRepository;
import com.rfidback.repository.PickerWorkDayRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RecordRepository.PickerCountsView;
import com.rfidback.repository.UserRepository;

/**
 * Work hours of the pickers (spec 014): one value per picker and station day, entered by an Administrateur, stored in
 * whole minutes (research R1, R2). A day is saved all or nothing (research R3).
 */
@Service
public class WorkHoursService {

    private static final int MINUTES_PER_HOUR = 60;
    // Hours arrive as a double: 7.25 h is exactly 435 minutes, anything further than this from a whole minute is not.
    private static final double MINUTE_TOLERANCE = 1e-6;

    private final PickerWorkDayRepository workDayRepository;
    private final PickerRepository pickerRepository;
    private final UserRepository userRepository;
    private final RecordRepository recordRepository;
    private final ZoneId zone;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTemplate;

    public WorkHoursService(PickerWorkDayRepository workDayRepository, PickerRepository pickerRepository,
            UserRepository userRepository, RecordRepository recordRepository, StationProperties stationProperties,
            Clock clock, PlatformTransactionManager transactionManager) {
        this.workDayRepository = workDayRepository;
        this.pickerRepository = pickerRepository;
        this.userRepository = userRepository;
        this.recordRepository = recordRepository;
        this.zone = stationProperties.timeZone();
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTemplate.setReadOnly(true);
    }

    /**
     * Every picker with their hours and their records of the day, flagging records without hours (FR-014);
     * {@code null} is today in the station time zone (research R4).
     */
    public WorkDay getWorkDay(LocalDate day) {
        LocalDate today = today();
        LocalDate resolved = day == null ? today : notInTheFuture(day, today);
        return readOnlyTemplate.execute(status -> describe(resolved, today));
    }

    /**
     * Sets or removes the hours of the listed pickers for one day. Every entry is checked before anything is written.
     * Not transactional itself: a unique-key violation marks its transaction rollback-only, so the single retry of a
     * concurrent insert has to run in a fresh transaction (research R3).
     */
    public WorkDay saveWorkDay(SaveWorkDayRequest request) {
        LocalDate today = today();
        if (request.getDay() == null) {
            throw badRequest("day is required");
        }
        LocalDate day = notInTheFuture(request.getDay(), today);

        // Insertion order kept, a null value removes the picker's hours.
        Map<UUID, Integer> minutesByPicker = new LinkedHashMap<>();
        for (WorkHoursEntry entry : request.getEntries()) {
            UUID pickerId = entry.getPickerId();
            if (pickerId == null) {
                throw badRequest("pickerId is required");
            }
            if (minutesByPicker.containsKey(pickerId)) {
                throw badRequest("Picker %s is listed more than once".formatted(pickerId));
            }
            minutesByPicker.put(pickerId, toMinutes(pickerId, entry.getHours()));
        }

        Map<UUID, PickerEntity> pickers = pickerRepository.findAllById(minutesByPicker.keySet()).stream()
                .collect(Collectors.toMap(PickerEntity::getId, Function.identity()));
        minutesByPicker.keySet().stream().filter(id -> !pickers.containsKey(id)).findFirst().ifPresent(id -> {
            throw new PickerNotFoundException("Picker %s not found".formatted(id));
        });
        UserEntity author = currentUser();

        try {
            write(day, pickers, minutesByPicker, author);
        } catch (DataIntegrityViolationException firstConflict) {
            // Another save inserted the same picker and day meanwhile: the retry now finds and updates its row.
            try {
                write(day, pickers, minutesByPicker, author);
            } catch (DataIntegrityViolationException secondConflict) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "The hours of this day were changed at the same time; reload the day and save again");
            }
        }
        return readOnlyTemplate.execute(status -> describe(day, today));
    }

    private void write(LocalDate day, Map<UUID, PickerEntity> pickers, Map<UUID, Integer> minutesByPicker,
            UserEntity author) {
        transactionTemplate.executeWithoutResult(status -> {
            Map<UUID, PickerWorkDayEntity> existing = workDayRepository
                    .findAllByWorkDateAndPickerIn(day, pickers.values()).stream()
                    .collect(Collectors.toMap(row -> row.getPicker().getId(), Function.identity()));
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<PickerWorkDayEntity> toSave = new ArrayList<>();
            List<PickerWorkDayEntity> toDelete = new ArrayList<>();
            minutesByPicker.forEach((pickerId, minutes) -> {
                PickerWorkDayEntity row = existing.get(pickerId);
                if (minutes == null) {
                    if (row != null) {
                        toDelete.add(row);
                    }
                    return;
                }
                if (row == null) {
                    row = PickerWorkDayEntity.builder().picker(pickers.get(pickerId)).workDate(day).build();
                } else if (row.getMinutes() == minutes) {
                    // Unchanged: the last change keeps its author and date (FR-005).
                    return;
                }
                row.setMinutes(minutes);
                row.setUpdatedAt(now);
                row.setUpdatedBy(author);
                toSave.add(row);
            });
            if (!toDelete.isEmpty()) {
                workDayRepository.deleteAll(toDelete);
            }
            if (!toSave.isEmpty()) {
                workDayRepository.saveAllAndFlush(toSave);
            }
        });
    }

    private WorkDay describe(LocalDate day, LocalDate today) {
        Map<UUID, PickerWorkDayEntity> rows = workDayRepository.findAllByWorkDate(day).stream()
                .collect(Collectors.toMap(row -> row.getPicker().getId(), Function.identity()));
        // The day's records on every reader, over the station day as the dashboard counts them.
        Map<UUID, Long> recordsByPicker = new HashMap<>();
        for (PickerCountsView count : recordRepository.countByPicker(day.atStartOfDay(zone).toOffsetDateTime(),
                day.plusDays(1).atStartOfDay(zone).toOffsetDateTime())) {
            if (count.getPickerId() != null) {
                recordsByPicker.put(count.getPickerId(), count.getTotal() == null ? 0 : count.getTotal().longValue());
            }
        }

        List<WorkDayPicker> pickers = new ArrayList<>();
        for (PickerEntity picker : pickerRepository.findAll()) {
            PickerWorkDayEntity row = rows.get(picker.getId());
            WorkDayPicker entry = new WorkDayPicker();
            entry.setPickerId(picker.getId());
            entry.setFirstname(picker.getFirstname());
            entry.setLastname(picker.getLastname());
            long records = recordsByPicker.getOrDefault(picker.getId(), 0L);
            entry.setRecords(records);
            entry.setMissingHours(records > 0 && row == null);
            if (row != null) {
                entry.setHours(row.getMinutes() / (double) MINUTES_PER_HOUR);
                entry.setUpdatedAt(row.getUpdatedAt());
                entry.setUpdatedBy(row.getUpdatedBy().getUsername());
            }
            pickers.add(entry);
        }
        // By last name then first name as a French reader expects, like the dashboard.
        Collator collator = Collator.getInstance(Locale.FRENCH);
        Comparator<String> byText = Comparator.nullsLast(collator::compare);
        pickers.sort(Comparator.comparing(WorkDayPicker::getLastname, byText)
                .thenComparing(WorkDayPicker::getFirstname, byText)
                .thenComparing(entry -> entry.getPickerId().toString()));

        WorkDay workDay = new WorkDay();
        workDay.setDay(day);
        workDay.setToday(day.equals(today));
        workDay.setPickers(pickers);
        return workDay;
    }

    private static Integer toMinutes(UUID pickerId, Double hours) {
        if (hours == null) {
            return null;
        }
        double minutes = hours * MINUTES_PER_HOUR;
        long rounded = Math.round(minutes);
        if (Double.isNaN(minutes) || Math.abs(minutes - rounded) > MINUTE_TOLERANCE
                || !PickerWorkDayEntity.isValidMinutes(rounded)) {
            throw badRequest("The hours of picker %s must be a multiple of 0.25 between 0.25 and 24".formatted(pickerId));
        }
        return (int) rounded;
    }

    private LocalDate notInTheFuture(LocalDate day, LocalDate today) {
        if (day.isAfter(today)) {
            throw badRequest("The day must not be in the future");
        }
        return day;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(zone));
    }

    private UserEntity currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String username = authentication == null ? null : authentication.getName();
        return userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalStateException("No user account for the current session"));
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
