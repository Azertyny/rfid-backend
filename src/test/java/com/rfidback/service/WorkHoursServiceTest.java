package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.configuration.StationProperties;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.PickerWorkDayEntity;
import com.rfidback.entity.Role;
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

/** Entry, validation and day view of the pickers' work hours (spec 014, FR-001 to FR-006). */
class WorkHoursServiceTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    // 10:00 in Paris on 2026-10-07.
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    private PickerWorkDayRepository workDayRepository;
    private PickerRepository pickerRepository;
    private UserRepository userRepository;
    private RecordRepository recordRepository;
    private WorkHoursService service;
    private UserEntity admin;
    private PickerEntity durand;
    private PickerEntity emile;
    private PickerEntity martin;

    @BeforeEach
    void setUp() {
        workDayRepository = Mockito.mock(PickerWorkDayRepository.class);
        pickerRepository = Mockito.mock(PickerRepository.class);
        userRepository = Mockito.mock(UserRepository.class);
        recordRepository = Mockito.mock(RecordRepository.class);
        service = new WorkHoursService(workDayRepository, pickerRepository, userRepository,
                recordRepository, new StationProperties(PARIS), Clock.fixed(NOW, ZoneOffset.UTC),
                Mockito.mock(PlatformTransactionManager.class));

        admin = UserEntity.builder().id(UUID.randomUUID()).username("admin").role(Role.ADMINISTRATEUR).build();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken("admin", null, List.of()));

        durand = picker("Paul", "Durand");
        emile = picker("Zoé", "Émile");
        martin = picker("Anne", "Martin");
        when(pickerRepository.findAll()).thenReturn(List.of(martin, emile, durand));
        when(workDayRepository.findAllByWorkDate(any())).thenReturn(List.of());
        when(workDayRepository.findAllByWorkDateAndPickerIn(any(), anyCollection())).thenReturn(List.of());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // --- day view ---

    @Test
    void withoutDayTheViewIsTodayInTheStationZone() {
        WorkDay day = service.getWorkDay(null);

        assertEquals(TODAY, day.getDay());
        assertThat(day.getToday()).isTrue();
    }

    @Test
    void aPastDayIsNotToday() {
        assertThat(service.getWorkDay(TODAY.minusDays(1)).getToday()).isFalse();
    }

    @Test
    void theViewListsEveryPickerInFrenchOrderWithTheirHours() {
        PickerWorkDayEntity row = row(emile, TODAY, 435);
        when(workDayRepository.findAllByWorkDate(TODAY)).thenReturn(List.of(row));

        List<WorkDayPicker> pickers = service.getWorkDay(TODAY).getPickers();

        assertThat(pickers).extracting(WorkDayPicker::getLastname).containsExactly("Durand", "Émile", "Martin");
        assertNull(pickers.get(0).getHours());
        assertNull(pickers.get(0).getUpdatedBy());
        assertEquals(7.25, pickers.get(1).getHours());
        assertEquals("admin", pickers.get(1).getUpdatedBy());
        assertEquals(row.getUpdatedAt(), pickers.get(1).getUpdatedAt());
    }

    @Test
    void aFutureDayIsRefused() {
        assertBadRequest(() -> service.getWorkDay(TODAY.plusDays(1)));
        assertBadRequest(() -> service.saveWorkDay(request(TODAY.plusDays(1), entry(durand, 8.0))));
        verifyNothingWritten();
    }

    @Test
    void todayFollowsTheStationZoneNotUtc() {
        // 22:30 UTC on 2026-10-07 is already 2026-10-08 in Paris.
        WorkHoursService late = new WorkHoursService(workDayRepository, pickerRepository, userRepository,
                recordRepository, new StationProperties(PARIS), Clock.fixed(Instant.parse("2026-10-07T22:30:00Z"), ZoneOffset.UTC),
                Mockito.mock(PlatformTransactionManager.class));

        assertEquals(LocalDate.of(2026, 10, 8), late.getWorkDay(null).getDay());
    }

    // --- save ---

    @Test
    void hoursAreStoredAsMinutesWithTheirAuthorAndDate() {
        pickersExist(durand);

        service.saveWorkDay(request(TODAY, entry(durand, 7.25)));

        PickerWorkDayEntity saved = singleSaved();
        assertEquals(durand, saved.getPicker());
        assertEquals(TODAY, saved.getWorkDate());
        assertEquals(435, saved.getMinutes());
        assertEquals(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), saved.getUpdatedAt());
        assertEquals(admin, saved.getUpdatedBy());
    }

    @Test
    void savingAgainReplacesTheDaysValue() {
        pickersExist(durand);
        PickerWorkDayEntity existing = row(durand, TODAY, 450);
        when(workDayRepository.findAllByWorkDateAndPickerIn(eq(TODAY), anyCollection())).thenReturn(List.of(existing));

        service.saveWorkDay(request(TODAY, entry(durand, 8.0)));

        PickerWorkDayEntity saved = singleSaved();
        assertThat(saved).isSameAs(existing);
        assertEquals(480, saved.getMinutes());
        assertEquals(admin, saved.getUpdatedBy());
    }

    @Test
    void nullHoursDeleteTheDaysValue() {
        pickersExist(durand);
        PickerWorkDayEntity existing = row(durand, TODAY, 450);
        when(workDayRepository.findAllByWorkDateAndPickerIn(eq(TODAY), anyCollection())).thenReturn(List.of(existing));

        service.saveWorkDay(request(TODAY, entry(durand, null)));

        verify(workDayRepository).deleteAll(List.of(existing));
        assertThat(savedRows()).isEmpty();
    }

    @Test
    void aWholeTeamIsSavedInOneCall() {
        pickersExist(durand, emile, martin);

        service.saveWorkDay(request(TODAY, entry(durand, 8.0), entry(emile, 7.5), entry(martin, 4.0)));

        assertThat(savedRows()).extracting(PickerWorkDayEntity::getMinutes).containsExactly(480, 450, 240);
    }

    @Test
    void aPastDayCanBeEnteredLate() {
        pickersExist(durand);

        service.saveWorkDay(request(TODAY.minusDays(3), entry(durand, 8.0)));

        assertEquals(TODAY.minusDays(3), singleSaved().getWorkDate());
    }

    @ParameterizedTest
    @ValueSource(doubles = { 0, -1, 24.25, 7.3, 0.1 })
    void invalidHoursRefuseTheWholeRequest(double hours) {
        pickersExist(durand, emile);

        // The valid entry before the bad one is not saved either: all or nothing.
        assertBadRequest(() -> service.saveWorkDay(request(TODAY, entry(durand, 8.0), entry(emile, hours))));
        verifyNothingWritten();
    }

    @Test
    void twentyFourHoursAndAQuarterHourAreAccepted() {
        pickersExist(durand, emile);

        service.saveWorkDay(request(TODAY, entry(durand, 24.0), entry(emile, 0.25)));

        assertThat(savedRows()).extracting(PickerWorkDayEntity::getMinutes).containsExactly(1440, 15);
    }

    @Test
    void aPickerListedTwiceIsRefused() {
        pickersExist(durand);

        assertBadRequest(() -> service.saveWorkDay(request(TODAY, entry(durand, 8.0), entry(durand, 7.0))));
        verifyNothingWritten();
    }

    @Test
    void anUnknownPickerIsNotFoundAndNothingIsWritten() {
        PickerEntity ghost = picker("Ghost", "Unknown");
        pickersExist(durand);

        assertThrows(PickerNotFoundException.class,
                () -> service.saveWorkDay(request(TODAY, entry(durand, 8.0), entry(ghost, 8.0))));
        verifyNothingWritten();
    }

    @Test
    void aConcurrentInsertIsRetriedOnce() {
        pickersExist(durand);
        when(workDayRepository.saveAllAndFlush(anyList()))
                .thenThrow(new DataIntegrityViolationException("uk_picker_work_day_picker_date"))
                .thenAnswer(invocation -> invocation.getArgument(0));

        WorkDay day = service.saveWorkDay(request(TODAY, entry(durand, 8.0)));

        assertEquals(TODAY, day.getDay());
        // The second attempt reads the rows again, so it updates the other request's row.
        verify(workDayRepository, times(2)).findAllByWorkDateAndPickerIn(eq(TODAY), anyCollection());
    }

    @Test
    void aSecondConflictIsAConflict() {
        pickersExist(durand);
        when(workDayRepository.saveAllAndFlush(anyList()))
                .thenThrow(new DataIntegrityViolationException("uk_picker_work_day_picker_date"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.saveWorkDay(request(TODAY, entry(durand, 8.0))));
        assertEquals(HttpStatus.CONFLICT, exception.getStatusCode());
    }

    // --- records of the day and missing hours (FR-014, User Story 3) ---

    @Test
    void recordsAreCountedOverTheStationDay() {
        service.getWorkDay(LocalDate.of(2026, 10, 6));

        verify(recordRepository).countByPicker(OffsetDateTime.parse("2026-10-06T00:00:00+02:00"),
                OffsetDateTime.parse("2026-10-07T00:00:00+02:00"));
    }

    @Test
    void missingHoursMeansRecordsWithoutHours() {
        PickerEntity bertin = picker("Luc", "Bertin");
        when(pickerRepository.findAll()).thenReturn(List.of(durand, emile, martin, bertin));
        // Durand: records and hours; Émile: records, no hours; Martin: hours, no record; Bertin: neither.
        when(recordRepository.countByPicker(any(), any())).thenReturn(List.of(
                countsRow(durand, 12), countsRow(emile, 5), countsRow(null, 3)));
        when(workDayRepository.findAllByWorkDate(TODAY))
                .thenReturn(List.of(row(durand, TODAY, 480), row(martin, TODAY, 480)));

        List<WorkDayPicker> pickers = service.getWorkDay(TODAY).getPickers();

        assertThat(pickers).extracting(WorkDayPicker::getLastname)
                .containsExactly("Bertin", "Durand", "Émile", "Martin");
        assertThat(pickers).extracting(WorkDayPicker::getRecords).containsExactly(0L, 12L, 5L, 0L);
        assertThat(pickers).extracting(WorkDayPicker::getMissingHours).containsExactly(false, false, true, false);
    }

    // --- helpers ---

    private void pickersExist(PickerEntity... pickers) {
        when(pickerRepository.findAllById(anyIterable())).thenReturn(Arrays.asList(pickers));
    }

    @SuppressWarnings("unchecked")
    private List<PickerWorkDayEntity> savedRows() {
        ArgumentCaptor<List<PickerWorkDayEntity>> captor = ArgumentCaptor.forClass(List.class);
        verify(workDayRepository, Mockito.atLeast(0)).saveAllAndFlush(captor.capture());
        List<PickerWorkDayEntity> rows = new ArrayList<>();
        captor.getAllValues().forEach(rows::addAll);
        return rows;
    }

    private PickerWorkDayEntity singleSaved() {
        List<PickerWorkDayEntity> rows = savedRows();
        assertEquals(1, rows.size());
        return rows.get(0);
    }

    private void verifyNothingWritten() {
        verify(workDayRepository, never()).saveAllAndFlush(any());
        verify(workDayRepository, never()).deleteAll(any());
    }

    private static void assertBadRequest(Executable call) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, call);
        assertEquals(HttpStatus.BAD_REQUEST, exception.getStatusCode());
    }

    private PickerWorkDayEntity row(PickerEntity picker, LocalDate day, int minutes) {
        return PickerWorkDayEntity.builder()
                .id(UUID.randomUUID())
                .picker(picker)
                .workDate(day)
                .minutes(minutes)
                .updatedAt(OffsetDateTime.parse("2026-10-07T06:30:00Z"))
                .updatedBy(admin)
                .build();
    }

    private static PickerEntity picker(String firstname, String lastname) {
        return PickerEntity.builder().id(UUID.randomUUID()).firstname(firstname).lastname(lastname).build();
    }

    private static PickerCountsView countsRow(PickerEntity picker, long total) {
        return new PickerCountsView() {
            @Override
            public UUID getPickerId() {
                return picker == null ? null : picker.getId();
            }

            @Override
            public String getFirstname() {
                return picker == null ? null : picker.getFirstname();
            }

            @Override
            public String getLastname() {
                return picker == null ? null : picker.getLastname();
            }

            @Override
            public Number getTotal() {
                return total;
            }

            @Override
            public Number getNonCompliant() {
                return 0;
            }
        };
    }

    private static WorkHoursEntry entry(PickerEntity picker, Double hours) {
        WorkHoursEntry entry = new WorkHoursEntry();
        entry.setPickerId(picker.getId());
        entry.setHours(hours);
        return entry;
    }

    private static SaveWorkDayRequest request(LocalDate day, WorkHoursEntry... entries) {
        SaveWorkDayRequest request = new SaveWorkDayRequest();
        request.setDay(day);
        request.setEntries(new ArrayList<>(List.of(entries)));
        return request;
    }
}
