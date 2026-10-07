package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.PickerRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;
import com.rfidback.support.ReferenceTagUids;

/**
 * HTTP-level checks of GET/PUT /api/work-hours on a real database (spec 014). Other classes create pickers in the
 * shared H2 database, so each check looks up its own pickers in the day's list, on days in 2020 nobody else uses.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class WorkHoursApiTest {

    private static final String DAY = "2020-03-10";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PickerRepository pickerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private PickerEntity diallo;
    private PickerEntity moreau;

    @BeforeEach
    void setUp() {
        userRepository.save(appUser("hours-admin", Role.ADMINISTRATEUR));
        diallo = pickerRepository.save(PickerEntity.builder().firstname("Amadou").lastname("Diallo").build());
        moreau = pickerRepository.save(PickerEntity.builder().firstname("Valérie").lastname("Moreau").build());
    }

    @Test
    void savedHoursAreReadBackWithTheirAuthor() throws Exception {
        JsonNode saved = save(DAY, entry(diallo, "7.5"), entry(moreau, "8"));

        assertEquals(DAY, saved.get("day").asText());
        assertThat(saved.get("today").asBoolean()).isFalse();
        JsonNode dialloRow = pickerIn(read(DAY), diallo);
        assertEquals(7.5, dialloRow.get("hours").asDouble());
        assertEquals("hours-admin", dialloRow.get("updatedBy").asText());
        assertThat(dialloRow.get("updatedAt").isNull()).isFalse();
        assertEquals(8.0, pickerIn(read(DAY), moreau).get("hours").asDouble());
    }

    @Test
    void savingAgainReplacesAndNullRemoves() throws Exception {
        save(DAY, entry(diallo, "7.5"), entry(moreau, "8"));

        save(DAY, entry(diallo, "8"), entry(moreau, "null"));

        JsonNode day = read(DAY);
        assertEquals(8.0, pickerIn(day, diallo).get("hours").asDouble());
        assertThat(pickerIn(day, moreau).get("hours").isNull()).isTrue();
        assertThat(pickerIn(day, moreau).get("updatedBy").isNull()).isTrue();
    }

    @Test
    void aPickerWithoutHoursIsListedWithNullHours() throws Exception {
        JsonNode row = pickerIn(read(DAY), diallo);

        assertThat(row.get("hours").isNull()).isTrue();
        assertEquals(0, row.get("records").asLong());
        assertThat(row.get("missingHours").asBoolean()).isFalse();
    }

    @Test
    void anInvalidEntrySavesNothing() throws Exception {
        mockMvc.perform(put("/api/work-hours").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(DAY, entry(diallo, "8"), entry(moreau, "7.3"))))
                .andExpect(status().isBadRequest());

        assertThat(pickerIn(read(DAY), diallo).get("hours").isNull()).isTrue();
    }

    @Test
    void outOfRangeHoursAndEmptyEntriesAreBadRequests() throws Exception {
        for (String content : new String[] { body(DAY, entry(diallo, "0")), body(DAY, entry(diallo, "24.5")),
                body(DAY, entry(diallo, "8"), entry(diallo, "7")), body(DAY) }) {
            mockMvc.perform(put("/api/work-hours").with(asAdmin()).with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(content))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void anUnknownPickerIsNotFound() throws Exception {
        mockMvc.perform(put("/api/work-hours").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"day\":\"" + DAY + "\",\"entries\":[{\"pickerId\":\"" + UUID.randomUUID()
                                + "\",\"hours\":8}]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void aFutureDayIsABadRequest() throws Exception {
        mockMvc.perform(get("/api/work-hours").param("day", "2099-01-01").with(asAdmin()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/work-hours").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body("2099-01-01", entry(diallo, "8"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void withoutDayTheViewIsToday() throws Exception {
        String body = mockMvc.perform(get("/api/work-hours").with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("today").asBoolean()).isTrue();
    }

    @Test
    void aWriteWithoutCsrfTokenIsForbidden() throws Exception {
        mockMvc.perform(put("/api/work-hours").with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON).content(body(DAY, entry(diallo, "8"))))
                .andExpect(status().isForbidden());
    }

    // --- records of the day and missing hours (FR-014, User Story 3) ---

    @Test
    void pickersWithRecordsAndNoHoursAreFlagged() throws Exception {
        PickerEntity bernard = pickerRepository.save(PickerEntity.builder().firstname("Lucie").lastname("Bernard")
                .build());
        ReaderEntity reader = readerRepository.save(ReaderEntity.builder().name("Reader hours test").build());
        record(reader, diallo, "2020-03-10T08:00:00+01:00");
        record(reader, diallo, "2020-03-10T09:00:00+01:00");
        record(reader, moreau, "2020-03-10T10:00:00+01:00");
        record(reader, bernard, "2020-03-10T11:00:00+01:00");
        // 23:30 in Paris the day before: not a record of the day.
        record(reader, moreau, "2020-03-09T23:30:00+01:00");
        save(DAY, entry(diallo, "8"));

        JsonNode day = read(DAY);

        assertEquals(2, pickerIn(day, diallo).get("records").asLong());
        assertThat(pickerIn(day, diallo).get("missingHours").asBoolean()).isFalse();
        assertEquals(1, pickerIn(day, moreau).get("records").asLong());
        assertThat(pickerIn(day, moreau).get("missingHours").asBoolean()).isTrue();
        assertThat(pickerIn(day, bernard).get("missingHours").asBoolean()).isTrue();
    }

    @Test
    void aPickerWithHoursAndNoRecordHasZeroCrates() throws Exception {
        save(DAY, entry(moreau, "7.5"));

        JsonNode row = pickerIn(read(DAY), moreau);

        assertEquals(0, row.get("records").asLong());
        assertThat(row.get("missingHours").asBoolean()).isFalse();
    }

    // @CreationTimestamp overwrites a date given on insert, so the date is set afterwards in SQL.
    private void record(ReaderEntity reader, PickerEntity picker, String createdAt) {
        TagEntity tag = tagRepository.save(TagEntity.builder().uid(ReferenceTagUids.nextInList()).build());
        RecordEntity saved = recordRepository.saveAndFlush(RecordEntity.builder()
                .reader(reader)
                .tag(tag)
                .picker(picker)
                .compliant(true)
                .build());
        jdbcTemplate.update("update record set creation_date = ? where id = ?", OffsetDateTime.parse(createdAt),
                saved.getId());
    }

    private JsonNode save(String day, String... entries) throws Exception {
        String body = mockMvc.perform(put("/api/work-hours").with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(day, entries)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private JsonNode read(String day) throws Exception {
        String body = mockMvc.perform(get("/api/work-hours").param("day", day).with(asAdmin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static JsonNode pickerIn(JsonNode day, PickerEntity picker) {
        for (JsonNode row : day.get("pickers")) {
            if (row.get("pickerId").asText().equals(picker.getId().toString())) {
                return row;
            }
        }
        throw new AssertionError("Picker " + picker.getLastname() + " is not listed");
    }

    private static String entry(PickerEntity picker, String hours) {
        return "{\"pickerId\":\"" + picker.getId() + "\",\"hours\":" + hours + "}";
    }

    private static String body(String day, String... entries) {
        return "{\"day\":\"" + day + "\",\"entries\":[" + String.join(",", entries) + "]}";
    }

    private UserEntity appUser(String username, Role role) {
        return UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode("irrelevant"))
                .role(role)
                .enabled(true)
                .build();
    }

    private static RequestPostProcessor asAdmin() {
        return user("hours-admin").roles("ADMINISTRATEUR");
    }
}
