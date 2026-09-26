package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.UserEntity;
import com.rfidback.exception.ActivityAlreadyExistsException;
import com.rfidback.exception.LinesLosingActivityException;
import com.rfidback.generated.model.CreateActivity;
import com.rfidback.generated.model.SetReaderActivities;
import com.rfidback.generated.model.UpdateActivity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.LineActivityChangeRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;

class ActivityServiceTest {

    private ActivityRepository activityRepository;
    private ReaderRepository readerRepository;
    private RecordRepository recordRepository;
    private LineActivityChangeRepository lineActivityChangeRepository;
    private LineActivityService lineActivityService;
    private ActivityService activityService;

    @BeforeEach
    void setUp() {
        activityRepository = Mockito.mock(ActivityRepository.class);
        readerRepository = Mockito.mock(ReaderRepository.class);
        recordRepository = Mockito.mock(RecordRepository.class);
        lineActivityChangeRepository = Mockito.mock(LineActivityChangeRepository.class);
        lineActivityService = Mockito.mock(LineActivityService.class);
        activityService = new ActivityService(activityRepository, readerRepository, recordRepository,
                lineActivityChangeRepository, lineActivityService);
        when(activityRepository.saveAndFlush(any(ActivityEntity.class))).thenAnswer(invocation -> {
            ActivityEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(UUID.randomUUID());
            }
            return entity;
        });
    }

    // --- create and rename (FR-001, FR-002) ---

    @Test
    void createActivity_trimsName() {
        activityService.createActivity(new CreateActivity("  Framboise  "));

        ArgumentCaptor<ActivityEntity> captor = ArgumentCaptor.forClass(ActivityEntity.class);
        verify(activityRepository).saveAndFlush(captor.capture());
        assertEquals("Framboise", captor.getValue().getName());
    }

    @Test
    void createActivity_blankOrTooLong_returns400() {
        assertStatus(HttpStatus.BAD_REQUEST, () -> activityService.createActivity(new CreateActivity("   ")));
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> activityService.createActivity(new CreateActivity("x".repeat(101))));
        verify(activityRepository, never()).saveAndFlush(any());
    }

    @Test
    void createActivity_sameNameIgnoringCaseAndSpaces_isRefused() {
        when(activityRepository.existsByNameKey("framboise")).thenReturn(true);

        assertThrows(ActivityAlreadyExistsException.class,
                () -> activityService.createActivity(new CreateActivity("Framboise ")));
    }

    @Test
    void createActivity_concurrentInsert_isRefusedAsDuplicate() {
        when(activityRepository.saveAndFlush(any(ActivityEntity.class)))
                .thenThrow(new DataIntegrityViolationException("unique"));

        assertThrows(ActivityAlreadyExistsException.class,
                () -> activityService.createActivity(new CreateActivity("Fraise")));
    }

    @Test
    void updateActivity_renameToAnotherActivitysName_isRefused() {
        ActivityEntity activity = activity("Fraise");
        when(activityRepository.findById(activity.getId())).thenReturn(Optional.of(activity));
        when(activityRepository.existsByNameKeyAndIdNot("framboise", activity.getId())).thenReturn(true);

        assertThrows(ActivityAlreadyExistsException.class,
                () -> activityService.updateActivity(activity.getId(), new UpdateActivity().name("FRAMBOISE")));
    }

    // --- disabling an activity that is current on lines (FR-007) ---

    @Test
    void disable_currentOnLines_withoutConfirmation_namesThemAndChangesNothing() {
        ActivityEntity activity = activity("Fraise");
        ReaderEntity line = currentOn(activity, "L1");

        LinesLosingActivityException exception = assertThrows(LinesLosingActivityException.class,
                () -> activityService.updateActivity(activity.getId(), new UpdateActivity().active(false)));

        assertThat(exception.getReaderUids()).containsExactly("L1");
        assertThat(activity.isActive()).isTrue();
        verify(lineActivityService, never()).clear(any(), any());
        verify(activityRepository, never()).saveAndFlush(any());
        assertThat(line.getCurrentActivity()).isSameAs(activity);
    }

    @Test
    void disable_currentOnLines_confirmed_clearsEachLine() {
        ActivityEntity activity = activity("Fraise");
        ReaderEntity line = currentOn(activity, "L1");
        UserEntity admin = UserEntity.builder().username("admin").build();
        when(lineActivityService.currentUser()).thenReturn(admin);

        activityService.updateActivity(activity.getId(), new UpdateActivity().active(false).confirmed(true));

        verify(lineActivityService).clear(line, admin);
        assertThat(activity.isActive()).isFalse();
    }

    // --- delete (FR-003, research R7) ---

    @Test
    void delete_usedByRecordOrHistory_returns409() {
        ActivityEntity activity = activity("Fraise");
        when(activityRepository.findById(activity.getId())).thenReturn(Optional.of(activity));
        when(recordRepository.existsByActivity(activity)).thenReturn(true);
        assertStatus(HttpStatus.CONFLICT, () -> activityService.deleteActivity(activity.getId()));

        when(recordRepository.existsByActivity(activity)).thenReturn(false);
        when(lineActivityChangeRepository.isReferenced(activity)).thenReturn(true);
        assertStatus(HttpStatus.CONFLICT, () -> activityService.deleteActivity(activity.getId()));

        verify(activityRepository, never()).delete(any());
    }

    @Test
    void delete_neverUsed_removesAssociationsAndActivity() {
        ActivityEntity activity = activity("Test");
        activity.getLines().add(reader("L1", ReaderMode.PRODUCTION));
        when(activityRepository.findById(activity.getId())).thenReturn(Optional.of(activity));

        activityService.deleteActivity(activity.getId());

        assertThat(activity.getLines()).isEmpty();
        verify(activityRepository).delete(activity);
    }

    // --- associations (FR-005 to FR-007) ---

    @Test
    void setReaderActivities_registrationReader_returns400() {
        ReaderEntity reader = reader("Enregistreur", ReaderMode.ENREGISTREMENT);
        when(readerRepository.findWithLockById(reader.getId())).thenReturn(Optional.of(reader));

        assertStatus(HttpStatus.BAD_REQUEST, () -> activityService.setReaderActivities(reader.getId(),
                new SetReaderActivities(Set.of())));
    }

    @Test
    void setReaderActivities_unknownActivity_returns400() {
        ReaderEntity reader = reader("L1", ReaderMode.PRODUCTION);
        when(readerRepository.findWithLockById(reader.getId())).thenReturn(Optional.of(reader));
        when(activityRepository.findAllById(any())).thenReturn(List.of());

        assertStatus(HttpStatus.BAD_REQUEST, () -> activityService.setReaderActivities(reader.getId(),
                new SetReaderActivities(Set.of(UUID.randomUUID()))));
    }

    @Test
    void setReaderActivities_removingCurrentWithoutConfirmation_returns409() {
        ActivityEntity fraise = activity("Fraise");
        ReaderEntity reader = reader("L1", ReaderMode.PRODUCTION);
        fraise.getLines().add(reader);
        when(readerRepository.findWithLockById(reader.getId())).thenReturn(Optional.of(reader));
        when(activityRepository.findAllById(any())).thenReturn(List.of());
        when(lineActivityService.effectiveActivity(reader)).thenReturn(fraise);

        LinesLosingActivityException exception = assertThrows(LinesLosingActivityException.class,
                () -> activityService.setReaderActivities(reader.getId(), new SetReaderActivities(Set.of())));

        assertThat(exception.getReaderUids()).containsExactly("L1");
        assertThat(fraise.getLines()).contains(reader);
    }

    @Test
    void setReaderActivities_replacesTheLinesActivities() {
        ActivityEntity fraise = activity("Fraise");
        ActivityEntity framboise = activity("Framboise");
        ReaderEntity reader = reader("L1", ReaderMode.PRODUCTION);
        fraise.getLines().add(reader);
        when(readerRepository.findWithLockById(reader.getId())).thenReturn(Optional.of(reader));
        when(activityRepository.findAllById(any())).thenReturn(List.of(framboise));
        when(activityRepository.findAllByLine(reader.getId())).thenReturn(List.of(fraise));

        activityService.setReaderActivities(reader.getId(), new SetReaderActivities(Set.of(framboise.getId())));

        assertThat(fraise.getLines()).isEmpty();
        assertThat(framboise.getLines()).containsExactly(reader);
        verify(lineActivityService).describe(reader);
        verify(lineActivityService, never()).clear(any(), any());
    }

    private ReaderEntity currentOn(ActivityEntity activity, String name) {
        ReaderEntity line = reader(name, ReaderMode.PRODUCTION);
        line.setCurrentActivity(activity);
        when(activityRepository.findById(activity.getId())).thenReturn(Optional.of(activity));
        when(readerRepository.findIdsByCurrentActivity(activity)).thenReturn(List.of(line.getId()));
        when(readerRepository.findWithLockById(line.getId())).thenReturn(Optional.of(line));
        when(lineActivityService.effectiveActivity(line)).thenReturn(activity);
        return line;
    }

    private static ActivityEntity activity(String name) {
        return ActivityEntity.builder().id(UUID.randomUUID()).name(name).build();
    }

    private static ReaderEntity reader(String name, ReaderMode mode) {
        return ReaderEntity.builder().id(UUID.randomUUID()).name(name).mode(mode).build();
    }

    private static void assertStatus(HttpStatus expected, Runnable call) {
        ResponseStatusException exception = assertThrows(ResponseStatusException.class, call::run);
        assertEquals(expected, exception.getStatusCode());
    }
}
