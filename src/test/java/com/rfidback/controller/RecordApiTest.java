package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordConformityChangeEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;

/** HTTP-level checks of the /api/records routes (spec 005). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RecordApiTest {

    private static final String READER_NAME = "Reader record test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private RecordConformityChangeRepository recordConformityChangeRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ReaderEntity reader;

    @BeforeEach
    void setUp() {
        reader = readerRepository.save(ReaderEntity.builder().name(READER_NAME).build());
        userRepository.save(appUser("record-admin", Role.ADMINISTRATEUR));
        userRepository.save(appUser("record-op", Role.OPERATEUR));
    }

    // --- last 10 records of a reader (US1, SC-001) ---

    @Test
    void listLatest_returnsAtMostTenNewestFirst() throws Exception {
        List<String> uids = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            uids.add("LIST-" + i);
            saveRecord("LIST-" + i, true);
        }

        MvcResult result = mockMvc.perform(get("/api/records/readers/{readerId}", READER_NAME).with(asOperator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(10))
                .andReturn();

        JsonNode records = objectMapper.readTree(result.getResponse().getContentAsString()).get("records");
        OffsetDateTime previous = null;
        for (JsonNode record : records) {
            assertThat(uids).contains(record.get("tagUid").asText());
            OffsetDateTime creationDate = OffsetDateTime.parse(record.get("creationDate").asText());
            if (previous != null) {
                assertThat(creationDate).isBeforeOrEqualTo(previous);
            }
            previous = creationDate;
        }
    }

    @Test
    void listLatest_unknownReader_returns404() throws Exception {
        mockMvc.perform(get("/api/records/readers/unknown-reader").with(asOperator()))
                .andExpect(status().isNotFound());
    }

    // --- compliance change and its history (US2, FR-003, FR-006) ---

    @Test
    void patchConformity_differentValue_returns204_andRecordsChange() throws Exception {
        RecordEntity record = saveRecord("PATCH-1", true);

        patchConformity(record, false, asOperator()).andExpect(status().isNoContent());

        assertThat(recordRepository.findById(record.getId()).orElseThrow().isCompliant()).isFalse();
        List<RecordConformityChangeEntity> changes = changesOf(record);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).isPreviousCompliant()).isTrue();
        assertThat(changes.get(0).isNewCompliant()).isFalse();
        assertThat(changes.get(0).getAuthor().getUsername()).isEqualTo("record-op");
    }

    @Test
    void patchConformity_sameValueTwice_recordsOneChange() throws Exception {
        RecordEntity record = saveRecord("PATCH-2", true);

        patchConformity(record, false, asOperator()).andExpect(status().isNoContent());
        patchConformity(record, false, asOperator()).andExpect(status().isNoContent());

        assertThat(changesOf(record)).hasSize(1);
    }

    @Test
    void patchConformity_backAndForth_recordsTwoChangesInOrder() throws Exception {
        RecordEntity record = saveRecord("PATCH-3", true);

        patchConformity(record, false, asOperator()).andExpect(status().isNoContent());
        patchConformity(record, true, asOperator()).andExpect(status().isNoContent());

        List<RecordConformityChangeEntity> changes = changesOf(record);
        assertThat(changes).hasSize(2);
        assertThat(changes.get(0).isPreviousCompliant()).isTrue();
        assertThat(changes.get(0).isNewCompliant()).isFalse();
        assertThat(changes.get(1).isPreviousCompliant()).isFalse();
        assertThat(changes.get(1).isNewCompliant()).isTrue();
    }

    @Test
    void patchConformity_unknownRecord_returns404() throws Exception {
        mockMvc.perform(patch("/api/records/{recordId}/conformity", UUID.randomUUID())
                .with(asOperator()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isCompliant\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void patchConformity_missingValue_returns400() throws Exception {
        RecordEntity record = saveRecord("PATCH-4", true);

        patchConformity(record, null, asOperator()).andExpect(status().isBadRequest());

        assertThat(changesOf(record)).isEmpty();
    }

    @Test
    void duplicateScanAfterChange_doesNotLowerRecord() throws Exception {
        scan("L-1", true).andExpect(status().isOk());
        RecordEntity record = latestRecord();
        patchConformity(record, false, asOperator()).andExpect(status().isNoContent());
        patchConformity(record, true, asOperator()).andExpect(status().isNoContent());

        scan("L-1", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Duplicate read ignored"))
                .andExpect(jsonPath("$.isCompliant").value(true));

        assertThat(latestRecord().getId()).isEqualTo(record.getId());
        assertThat(recordRepository.findById(record.getId()).orElseThrow().isCompliant()).isTrue();
        assertThat(changesOf(record)).hasSize(2);
    }

    @Test
    void duplicateScanWithoutChange_stillLowersRecord() throws Exception {
        scan("L-2", true).andExpect(status().isOk());
        RecordEntity record = latestRecord();

        scan("L-2", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(false));

        assertThat(recordRepository.findById(record.getId()).orElseThrow().isCompliant()).isFalse();
        assertThat(changesOf(record)).isEmpty();
    }

    // --- compliance history (US3, FR-007) ---

    @Test
    void history_afterTwoChanges_returnsThemOldestFirst() throws Exception {
        RecordEntity record = saveRecord("HIST-1", true);
        patchConformity(record, false, asOperator()).andExpect(status().isNoContent());
        patchConformity(record, true, asOperator()).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/records/{recordId}/conformity-history", record.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(2))
                .andExpect(jsonPath("$.changes[0].previousIsCompliant").value(true))
                .andExpect(jsonPath("$.changes[0].newIsCompliant").value(false))
                .andExpect(jsonPath("$.changes[0].authorUsername").value("record-op"))
                .andExpect(jsonPath("$.changes[0].changedAt").isNotEmpty())
                .andExpect(jsonPath("$.changes[1].previousIsCompliant").value(false))
                .andExpect(jsonPath("$.changes[1].newIsCompliant").value(true));
    }

    @Test
    void history_neverChanged_returnsEmptyList() throws Exception {
        RecordEntity record = saveRecord("HIST-2", true);

        mockMvc.perform(get("/api/records/{recordId}/conformity-history", record.getId()).with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes.length()").value(0));
    }

    @Test
    void history_unknownRecord_returns404() throws Exception {
        mockMvc.perform(get("/api/records/{recordId}/conformity-history", UUID.randomUUID()).with(asAdmin()))
                .andExpect(status().isNotFound());
    }

    @Test
    void history_asOperator_returns403() throws Exception {
        RecordEntity record = saveRecord("HIST-3", true);

        mockMvc.perform(get("/api/records/{recordId}/conformity-history", record.getId()).with(asOperator()))
                .andExpect(status().isForbidden());
    }

    private ResultActions patchConformity(RecordEntity record, Boolean isCompliant, RequestPostProcessor who)
            throws Exception {
        String body = isCompliant == null ? "{}" : objectMapper.writeValueAsString(Map.of("isCompliant", isCompliant));
        return mockMvc.perform(patch("/api/records/{recordId}/conformity", record.getId())
                .with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions scan(String uid, boolean isCompliant) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uid", uid);
        body.put("isCompliant", isCompliant);
        return mockMvc.perform(post("/api/tags/scan")
                .header("x-api-token", reader.getApitoken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private RecordEntity latestRecord() {
        return recordRepository.findTop10ByReader_NameOrderByCreationDateDesc(READER_NAME).get(0);
    }

    private List<RecordConformityChangeEntity> changesOf(RecordEntity record) {
        return recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(record);
    }

    private RecordEntity saveRecord(String tagUid, boolean compliant) {
        TagEntity tag = tagRepository.save(TagEntity.builder().uid(tagUid).build());
        return recordRepository.saveAndFlush(RecordEntity.builder().tag(tag).reader(reader).compliant(compliant).build());
    }

    private UserEntity appUser(String username, Role role) {
        return UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode("irrelevant"))
                .role(role)
                .enabled(true)
                .build();
    }

    // The usernames match saved users: a compliance change looks its author up by name.
    private static RequestPostProcessor asAdmin() {
        return user("record-admin").roles("ADMINISTRATEUR");
    }

    private static RequestPostProcessor asOperator() {
        return user("record-op").roles("OPERATEUR");
    }
}
