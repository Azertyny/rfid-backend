package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.PickerWorkDayEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;
import com.rfidback.repository.PickerWorkDayRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;
import com.rfidback.support.ReferenceTagUids;

/** HTTP-level checks of the picker routes (spec 001), run as an Administrateur. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PickerApiTest {

    private static final AtomicInteger NEXT_BUCKET_NUMBER = new AtomicInteger(90000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BucketRepository bucketRepository;

    @Autowired
    private PickerRepository pickerRepository;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private PickerWorkDayRepository pickerWorkDayRepository;

    @Autowired
    private UserRepository userRepository;

    @Test
    void listPickers_asAdmin_returns200() throws Exception {
        mockMvc.perform(get("/api/pickers").with(admin())).andExpect(status().isOk());
    }

    @Test
    void createPicker_returns201WithBody() throws Exception {
        String lastname = unique("Create");
        postPicker(lastname, "Jean")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.creationDate").isNotEmpty())
                .andExpect(jsonPath("$.lastname").value(lastname))
                .andExpect(jsonPath("$.firstname").value("Jean"));
    }

    @Test
    void createPicker_sameNameDifferentCase_returns409() throws Exception {
        String lastname = unique("Martin");
        createPicker(lastname, "Paul");

        postPicker(lastname.toUpperCase(), "paul").andExpect(status().isConflict());
    }

    @Test
    void listPickers_sortFirstnameDesc_ordersDescending() throws Exception {
        String lastname = unique("Sort");
        createPicker(lastname, "Aaa");
        createPicker(lastname, "Zzz");

        String body = mockMvc.perform(get("/api/pickers?size=100&sort=firstname,desc").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<String> firstnames = new ArrayList<>();
        objectMapper.readTree(body).get("content").forEach(picker -> {
            if (lastname.equals(picker.get("lastname").asText())) {
                firstnames.add(picker.get("firstname").asText());
            }
        });
        assertThat(firstnames).containsExactly("Zzz", "Aaa");
    }

    @Test
    void listPickers_defaultSortStillAccepted() throws Exception {
        mockMvc.perform(get("/api/pickers?sort=lastname,asc").with(admin())).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = { "sort=foo,asc", "size=500", "page=-1" })
    void listPickers_invalidQuery_returns400(String query) throws Exception {
        mockMvc.perform(get("/api/pickers?" + query).with(admin())).andExpect(status().isBadRequest());
    }

    @Test
    void createPicker_blankLastname_returns400() throws Exception {
        postPicker("   ", "Jean").andExpect(status().isBadRequest());
    }

    @Test
    void updatePicker_returns200() throws Exception {
        String lastname = unique("Update");
        UUID pickerId = createPicker(lastname, "Jean");

        putPicker(pickerId, Map.of("lastname", lastname, "firstname", "Jean", "comment", "Rang 4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comment").value("Rang 4"));
    }

    @Test
    void updatePicker_toAnotherPickersName_returns409() throws Exception {
        String lastname = unique("Taken");
        createPicker(lastname, "Paul");
        UUID pickerId = createPicker(lastname, "Jean");

        putPicker(pickerId, Map.of("lastname", lastname, "firstname", "Paul")).andExpect(status().isConflict());
    }

    @Test
    void updatePicker_unknownId_returns404() throws Exception {
        putPicker(UUID.randomUUID(), Map.of("lastname", "Nobody", "firstname", "Here"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePicker_withAssignedBucket_returns409ThenUnassignThenDelete204() throws Exception {
        UUID pickerId = createPicker(unique("Delete"), "Jean");
        PickerEntity picker = pickerRepository.findById(pickerId).orElseThrow();
        UUID bucketId = bucketRepository.save(BucketEntity.builder()
                .number(NEXT_BUCKET_NUMBER.getAndIncrement())
                .picker(picker)
                .build()).getId();

        mockMvc.perform(delete("/api/pickers/" + pickerId).with(admin()).with(csrf()))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/api/pickers/" + pickerId).with(admin())).andExpect(status().isOk());

        mockMvc.perform(delete("/api/buckets/" + bucketId + "/picker").with(admin()).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/pickers/" + pickerId).with(admin()).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/pickers/" + pickerId).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void unassignBucket_alreadyUnassigned_returns204() throws Exception {
        UUID bucketId = bucketRepository.save(BucketEntity.builder()
                .number(NEXT_BUCKET_NUMBER.getAndIncrement())
                .build()).getId();

        mockMvc.perform(delete("/api/buckets/" + bucketId + "/picker").with(admin()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    void unassignBucket_unknownBucket_returns404() throws Exception {
        mockMvc.perform(delete("/api/buckets/" + UUID.randomUUID() + "/picker").with(admin()).with(csrf()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletePicker_withRecordAndHours_softDeletesAndKeepsHistory() throws Exception {
        String lastname = unique("Soft");
        UUID pickerId = createPicker(lastname, "Jean");
        PickerEntity picker = pickerRepository.findById(pickerId).orElseThrow();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        ReaderEntity reader = readerRepository.save(ReaderEntity.builder().name("Reader soft " + suffix).build());
        TagEntity tag = tagRepository.save(TagEntity.builder().uid(ReferenceTagUids.nextInList()).build());
        RecordEntity record = recordRepository.save(RecordEntity.builder()
                .tag(tag).reader(reader).picker(picker).compliant(true).build());
        UserEntity author = userRepository.save(UserEntity.builder().username("soft-" + suffix)
                .passwordHash("irrelevant").role(Role.ADMINISTRATEUR).enabled(true).build());
        PickerWorkDayEntity hours = pickerWorkDayRepository.save(PickerWorkDayEntity.builder().picker(picker)
                .workDate(LocalDate.now(ZoneId.of("Europe/Paris"))).minutes(480)
                .updatedAt(OffsetDateTime.now()).updatedBy(author).build());
        String statsBefore = stats();
        assertThat(statsBefore).contains(lastname);

        mockMvc.perform(delete("/api/pickers/" + pickerId).with(admin()).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/pickers/" + pickerId).with(admin())).andExpect(status().isNotFound());
        putPicker(pickerId, Map.of("lastname", lastname, "firstname", "Paul")).andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/pickers/" + pickerId).with(admin()).with(csrf()))
                .andExpect(status().isNotFound());
        assertThat(listedIds()).doesNotContain(pickerId.toString());
        assertThat(stats()).isEqualTo(statsBefore);
        postPicker(lastname.toUpperCase(), "jean").andExpect(status().isConflict());
        assertThat(recordRepository.findById(record.getId())).isPresent();
        assertThat(pickerWorkDayRepository.findById(hours.getId())).isPresent();
    }

    /** Every picker id of GET /api/pickers, walking the pages as the front does. */
    private List<String> listedIds() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int page = 0; ; page++) {
            JsonNode json = objectMapper.readTree(mockMvc.perform(
                    get("/api/pickers?size=100&page=" + page).with(admin()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            json.get("content").forEach(picker -> ids.add(picker.get("id").asText()));
            if (!json.get("metadata").get("hasNext").asBoolean()) {
                return ids;
            }
        }
    }

    private String stats() throws Exception {
        return mockMvc.perform(get("/api/records/stats").with(admin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private ResultActions putPicker(UUID pickerId, Map<String, String> body) throws Exception {
        return mockMvc.perform(put("/api/pickers/" + pickerId).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMINISTRATEUR");
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResultActions postPicker(String lastname, String firstname) throws Exception {
        return mockMvc.perform(post("/api/pickers").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("lastname", lastname, "firstname", firstname))));
    }

    private UUID createPicker(String lastname, String firstname) throws Exception {
        String body = postPicker(lastname, firstname).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(body);
        return UUID.fromString(json.get("id").asText());
    }
}
