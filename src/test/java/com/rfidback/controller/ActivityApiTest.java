package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/** Activities and their lines, managed by the Administrateur (spec 012, User Story 1). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ActivityApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    @BeforeEach
    void setUp() {
        userRepository.save(appUser("activity-admin", Role.ADMINISTRATEUR));
        userRepository.save(appUser("activity-op", Role.OPERATEUR));
        lineOne = readerRepository.save(ReaderEntity.builder().name("Activity L1 " + UUID.randomUUID()).build());
        lineTwo = readerRepository.save(ReaderEntity.builder().name("Activity L2 " + UUID.randomUUID()).build());
    }

    // --- catalogue (US1 scenarios 1, 2, 5) ---

    @Test
    void create_returnsActiveActivityWithoutLines() throws Exception {
        createActivity("Framboise")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Framboise"))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.readerIds.length()").value(0));
    }

    @Test
    void create_sameNameIgnoringCaseAndSpaces_returns409() throws Exception {
        createActivity("Framboise").andExpect(status().isCreated());

        createActivity("framboise ").andExpect(status().isConflict());
    }

    @Test
    void operator_canListButNotChange() throws Exception {
        mockMvc.perform(get("/api/activities").with(asOperator())).andExpect(status().isOk());
        mockMvc.perform(post("/api/activities").with(asOperator()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Cerise\"}"))
                .andExpect(status().isForbidden());
    }

    // --- associations (US1 scenario 3, FR-006) ---

    @Test
    void setReaderActivities_showsInTheList() throws Exception {
        UUID fraise = idOf(createActivity("Fraise"));
        UUID framboise = idOf(createActivity("Framboise"));

        setActivities(lineOne, false, fraise).andExpect(status().isOk())
                .andExpect(jsonPath("$.availableActivities.length()").value(1));
        setActivities(lineTwo, false, fraise, framboise).andExpect(status().isOk());

        mockMvc.perform(get("/api/activities").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activities[?(@.name == 'Fraise')].readerIds.length()").value(2))
                .andExpect(jsonPath("$.activities[?(@.name == 'Framboise')].readerIds[0]")
                        .value(lineTwo.getId().toString()));
    }

    @Test
    void setReaderActivities_registrationReader_returns400() throws Exception {
        ReaderEntity enregistreur = readerRepository.save(ReaderEntity.builder()
                .name("Activity enregistreur " + UUID.randomUUID()).mode(ReaderMode.ENREGISTREMENT).build());
        UUID fraise = idOf(createActivity("Fraise"));

        setActivities(enregistreur, false, fraise).andExpect(status().isBadRequest());
    }

    // --- rename shows on past records (US1 scenario 4) ---

    @Test
    void rename_showsOnPastRecords() throws Exception {
        UUID fraise = idOf(createActivity("Fraise"));
        ActivityEntity activity = activityRepository.findById(fraise).orElseThrow();
        TagEntity tag = tagRepository.save(TagEntity.builder().uid("ACTIVITY-RENAME").build());
        recordRepository.saveAndFlush(RecordEntity.builder().tag(tag).reader(lineOne).activity(activity)
                .compliant(true).build());

        mockMvc.perform(patch("/api/activities/{id}", fraise).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Fraise gariguette\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/records/readers/{readerId}", lineOne.getName()).with(asOperator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records[0].activityId").value(fraise.toString()))
                .andExpect(jsonPath("$.records[0].activityName").value("Fraise gariguette"));
    }

    // --- clearing a current activity needs confirmation (FR-007) ---

    @Test
    void disable_currentOnALine_needsConfirmation() throws Exception {
        UUID fraise = idOf(createActivity("Fraise"));
        setActivities(lineOne, false, fraise).andExpect(status().isOk());
        makeCurrent(lineOne, fraise);

        mockMvc.perform(patch("/api/activities/{id}", fraise).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.readerUids[0]").value(lineOne.getName()));
        assertThat(readerRepository.findWithCurrentActivityById(lineOne.getId()).orElseThrow()
                .getCurrentActivity()).isNotNull();

        mockMvc.perform(patch("/api/activities/{id}", fraise).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false,\"confirmed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        assertThat(readerRepository.findWithCurrentActivityById(lineOne.getId()).orElseThrow()
                .getCurrentActivity()).isNull();
        List<LineActivityChangeEntity> changes = lineActivityChangeRepository.findAllByReaderOrderByChangedAtAsc(
                lineOne);
        assertThat(changes).hasSize(2);
        assertThat(changes.get(1).getAuthorType()).isEqualTo(ActivityChangeAuthorType.USER);
        assertThat(changes.get(1).getAuthorUser().getUsername()).isEqualTo("activity-admin");
        assertThat(changes.get(1).getNewActivity()).isNull();
    }

    @Test
    void untick_currentActivity_needsConfirmation() throws Exception {
        UUID fraise = idOf(createActivity("Fraise"));
        UUID framboise = idOf(createActivity("Framboise"));
        setActivities(lineTwo, false, fraise, framboise).andExpect(status().isOk());
        makeCurrent(lineTwo, framboise);

        setActivities(lineTwo, false, fraise)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.readerUids[0]").value(lineTwo.getName()));

        setActivities(lineTwo, true, fraise)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentActivity").isEmpty())
                .andExpect(jsonPath("$.availableActivities.length()").value(1));
    }

    // --- delete (FR-003) ---

    @Test
    void delete_usedActivity_returns409_unusedOne_returns204() throws Exception {
        UUID used = idOf(createActivity("Fraise"));
        setActivities(lineOne, false, used).andExpect(status().isOk());
        makeCurrent(lineOne, used);
        UUID unused = idOf(createActivity("Test"));

        mockMvc.perform(delete("/api/activities/{id}", used).with(asAdmin()).with(csrf()))
                .andExpect(status().isConflict());
        mockMvc.perform(delete("/api/activities/{id}", unused).with(asAdmin()).with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(activityRepository.findById(unused)).isEmpty();
    }

    private void makeCurrent(ReaderEntity line, UUID activityId) throws Exception {
        mockMvc.perform(put("/api/lines/{readerUid}/current-activity", line.getName()).with(asOperator())
                .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"activityId\":\"" + activityId + "\"}"))
                .andExpect(status().isOk());
    }

    private ResultActions createActivity(String name) throws Exception {
        return mockMvc.perform(post("/api/activities").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("name", name))));
    }

    private ResultActions setActivities(ReaderEntity line, boolean confirmed, UUID... activityIds) throws Exception {
        return mockMvc.perform(put("/api/readers/{readerId}/activities", line.getId()).with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("activityIds", List.of(activityIds),
                        "confirmed", confirmed))));
    }

    private UUID idOf(ResultActions created) throws Exception {
        String body = created.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("id").asText());
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
        return user("activity-admin").roles("ADMINISTRATEUR");
    }

    private static RequestPostProcessor asOperator() {
        return user("activity-op").roles("OPERATEUR");
    }
}
