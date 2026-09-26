package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.ActivityChangeAuthorType;
import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.LineActivityChangeEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.LineActivityChangeRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;

/** Choosing a line's current activity at its kiosk or when logged in (spec 012, User Story 2). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class LineActivityApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private LineActivityChangeRepository lineActivityChangeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ReaderEntity lineOne;
    private ReaderEntity lineTwo;
    private ActivityEntity fraise;
    private ActivityEntity framboise;
    private ActivityEntity cerise;

    @BeforeEach
    void setUp() {
        userRepository.save(appUser("line-admin", Role.ADMINISTRATEUR));
        userRepository.save(appUser("line-op", Role.OPERATEUR));
        lineOne = readerRepository.save(ReaderEntity.builder().name("Line-L1-" + UUID.randomUUID()).build());
        lineTwo = readerRepository.save(ReaderEntity.builder().name("Line-L2-" + UUID.randomUUID()).build());
        // Sorted by name, fraise comes before framboise; cerise is only on L2.
        framboise = activity("Line framboise", lineOne);
        fraise = activity("Line fraise", lineOne);
        cerise = activity("Line cerise", lineTwo);
    }

    // --- choosing at the kiosk (US2 scenarios 1, 2, 4) ---

    @Test
    void kiosk_choosesAnAssociatedActivity_creditedToItsReader() throws Exception {
        asKiosk(lineOne, put(path(lineOne)), fraise.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentActivity.name").value("Line fraise"))
                .andExpect(jsonPath("$.setAt").isNotEmpty());

        List<LineActivityChangeEntity> changes = changesOf(lineOne);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).getAuthorType()).isEqualTo(ActivityChangeAuthorType.READER);
        assertThat(changes.get(0).getAuthorReader().getId()).isEqualTo(lineOne.getId());
        assertThat(changes.get(0).getNewActivity().getId()).isEqualTo(fraise.getId());
    }

    @Test
    void kiosk_choosingNone_clearsTheActivity() throws Exception {
        asKiosk(lineOne, put(path(lineOne)), fraise.getId()).andExpect(status().isOk());

        asKiosk(lineOne, put(path(lineOne)), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentActivity").isEmpty())
                .andExpect(jsonPath("$.setAt").isEmpty());
        assertThat(changesOf(lineOne)).hasSize(2);
    }

    @Test
    void choosingTheCurrentActivityAgain_writesNothing() throws Exception {
        asKiosk(lineOne, put(path(lineOne)), fraise.getId()).andExpect(status().isOk());
        asKiosk(lineOne, put(path(lineOne)), fraise.getId()).andExpect(status().isOk());

        assertThat(changesOf(lineOne)).hasSize(1);
    }

    @Test
    void notAssociatedDisabledOrUnknownActivity_returns400AndChangesNothing() throws Exception {
        framboise.setActive(false);
        asKiosk(lineOne, put(path(lineOne)), cerise.getId()).andExpect(status().isBadRequest());
        asKiosk(lineOne, put(path(lineOne)), framboise.getId()).andExpect(status().isBadRequest());
        asKiosk(lineOne, put(path(lineOne)), UUID.randomUUID()).andExpect(status().isBadRequest());

        assertThat(changesOf(lineOne)).isEmpty();
    }

    @Test
    void kiosk_otherLine_returns403() throws Exception {
        asKiosk(lineOne, put(path(lineTwo)), cerise.getId()).andExpect(status().isForbidden());

        assertThat(changesOf(lineTwo)).isEmpty();
    }

    // --- logged-in users (US2 scenario 5, FR-010) ---

    @Test
    void operator_choosesOnAnyLine_creditedToTheUser() throws Exception {
        mockMvc.perform(put(path(lineTwo)).with(asOperator()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":\"" + cerise.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentActivity.name").value("Line cerise"));

        List<LineActivityChangeEntity> changes = changesOf(lineTwo);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).getAuthorType()).isEqualTo(ActivityChangeAuthorType.USER);
        assertThat(changes.get(0).getAuthorUser().getUsername()).isEqualTo("line-op");
    }

    @Test
    void unknownLine_returns404() throws Exception {
        mockMvc.perform(get("/api/lines/{readerUid}/current-activity", "No such line " + UUID.randomUUID())
                        .with(asOperator()))
                .andExpect(status().isNotFound());
    }

    // --- registration readers (US2 scenario 6, FR-013) ---

    @Test
    void registrationReader_returns400() throws Exception {
        ReaderEntity enregistreur = readerRepository.save(ReaderEntity.builder()
                .name("Line-enregistreur-" + UUID.randomUUID()).mode(ReaderMode.ENREGISTREMENT).build());

        mockMvc.perform(get(path(enregistreur)).with(asOperator())).andExpect(status().isBadRequest());
        mockMvc.perform(put(path(enregistreur)).with(asOperator()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":null}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void switchToRegistration_clearsTheActivityAndKeepsAssociations() throws Exception {
        asKiosk(lineOne, put(path(lineOne)), fraise.getId()).andExpect(status().isOk());

        mockMvc.perform(patch("/api/readers/{readerId}", lineOne.getId()).with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"mode\":\"ENREGISTREMENT\"}"))
                .andExpect(status().isOk());

        assertThat(readerRepository.findWithCurrentActivityById(lineOne.getId()).orElseThrow().getCurrentActivity())
                .isNull();
        List<LineActivityChangeEntity> changes = changesOf(lineOne);
        assertThat(changes).hasSize(2);
        assertThat(changes.get(1).getAuthorUser().getUsername()).isEqualTo("line-admin");
        assertThat(activityRepository.findAllByLine(lineOne.getId())).hasSize(2);
    }

    // --- what the kiosk shows (FR-009a, R10) ---

    @Test
    void read_listsActiveAssociatedActivitiesSortedByName_andCountsTodaysRecordsWithoutActivity() throws Exception {
        activity("Line disabled", lineOne).setActive(false);
        saveRecord(lineOne, "LINE-1", null);
        saveRecord(lineOne, "LINE-2", null);
        saveRecord(lineOne, "LINE-3", fraise);
        saveRecord(lineTwo, "LINE-4", null);

        asKiosk(lineOne, get(path(lineOne)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readerUid").value(lineOne.getName()))
                .andExpect(jsonPath("$.currentActivity").isEmpty())
                .andExpect(jsonPath("$.availableActivities.length()").value(2))
                .andExpect(jsonPath("$.availableActivities[0].name").value("Line fraise"))
                .andExpect(jsonPath("$.availableActivities[1].name").value("Line framboise"))
                .andExpect(jsonPath("$.recordsWithoutActivityToday").value(2));
    }

    @Test
    void choiceFromAPreviousDay_readsAsNone() throws Exception {
        lineOne.setCurrentActivity(fraise);
        lineOne.setCurrentActivitySetAt(OffsetDateTime.now().minusDays(2));
        readerRepository.saveAndFlush(lineOne);

        asKiosk(lineOne, get(path(lineOne)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentActivity").isEmpty());
    }

    private ActivityEntity activity(String name, ReaderEntity line) {
        ActivityEntity activity = ActivityEntity.builder().name(name).build();
        activity.getLines().add(line);
        return activityRepository.saveAndFlush(activity);
    }

    private void saveRecord(ReaderEntity reader, String tagUid, ActivityEntity activity) {
        TagEntity tag = tagRepository.save(TagEntity.builder().uid(tagUid + "-" + UUID.randomUUID()).build());
        recordRepository.saveAndFlush(RecordEntity.builder().tag(tag).reader(reader).activity(activity)
                .compliant(true).build());
    }

    private ResultActions asKiosk(ReaderEntity reader, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header("x-api-token", reader.getApitoken()));
    }

    private ResultActions asKiosk(ReaderEntity reader, MockHttpServletRequestBuilder request, UUID activityId)
            throws Exception {
        String value = activityId == null ? "null" : "\"" + activityId + "\"";
        return asKiosk(reader, request.contentType(MediaType.APPLICATION_JSON).content("{\"activityId\":" + value + "}"));
    }

    private List<LineActivityChangeEntity> changesOf(ReaderEntity reader) {
        return lineActivityChangeRepository.findAllByReaderOrderByChangedAtAsc(reader);
    }

    private static String path(ReaderEntity reader) {
        return "/api/lines/" + reader.getName() + "/current-activity";
    }

    private UserEntity appUser(String username, Role role) {
        return UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode("irrelevant"))
                .role(role)
                .enabled(true)
                .build();
    }

    // The usernames match saved users: an activity change looks its author up by name.
    private static RequestPostProcessor asAdmin() {
        return user("line-admin").roles("ADMINISTRATEUR");
    }

    private static RequestPostProcessor asOperator() {
        return user("line-op").roles("OPERATEUR");
    }
}
