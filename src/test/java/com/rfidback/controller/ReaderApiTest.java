package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
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
import com.rfidback.entity.ReaderEntity;
import com.rfidback.repository.ReaderRepository;

/** HTTP-level checks of the reader routes (spec 002). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReaderApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReaderRepository readerRepository;

    @Test
    void list_asAdmin_returns200() throws Exception {
        mockMvc.perform(get("/api/readers").with(admin())).andExpect(status().isOk());
    }

    @Test
    void create_returns201WithTrimmedUidTokenIdAndActive() throws Exception {
        String uid = unique("Create");
        postReader("  " + uid + " ")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.apitoken").isNotEmpty());
    }

    @Test
    void create_blankUid_returns400() throws Exception {
        postReader("   ").andExpect(status().isBadRequest());
    }

    @Test
    void create_uidOver50Chars_returns400() throws Exception {
        postReader("a".repeat(51)).andExpect(status().isBadRequest());
    }

    @Test
    void create_duplicateIgnoringCase_returns409() throws Exception {
        String uid = unique("Dup");
        createReader(uid);

        postReader("  " + uid.toUpperCase() + " ").andExpect(status().isConflict());
    }

    @Test
    void list_asOperator_includesInactiveReaderWithIdAndActiveButNoToken() throws Exception {
        ReaderEntity disabled = readerRepository.save(ReaderEntity.builder().name(unique("Off")).active(false).build());
        String match = "$.readers[?(@.uid == '" + disabled.getName() + "')]";

        mockMvc.perform(get("/api/readers").with(operator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(match + ".id", contains(disabled.getId().toString())))
                .andExpect(jsonPath(match + ".active", contains(false)))
                .andExpect(jsonPath(match + ".apitoken", empty()));
    }

    @Test
    void list_asAdmin_includesActiveFlagAndToken() throws Exception {
        JsonNode created = createReader(unique("On"));
        String match = "$.readers[?(@.id == '" + created.get("id").asText() + "')]";

        mockMvc.perform(get("/api/readers").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(match + ".active", contains(true)))
                .andExpect(jsonPath(match + ".apitoken", contains(created.get("apitoken").asText())))
                .andExpect(jsonPath(match, hasSize(1)));
    }

    @Test
    void patch_disablesThenReenables() throws Exception {
        String readerId = createReader(unique("Toggle")).get("id").asText();

        patchReader(readerId, "{\"active\":false}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        patchReader(readerId, "{\"active\":true}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void patch_emptyBody_returns400() throws Exception {
        String readerId = createReader(unique("Empty")).get("id").asText();

        patchReader(readerId, "{}").andExpect(status().isBadRequest());
    }

    @Test
    void patch_unknownId_returns404() throws Exception {
        patchReader(UUID.randomUUID().toString(), "{\"active\":false}").andExpect(status().isNotFound());
    }

    @Test
    void disabledReader_recordsStillReadable() throws Exception {
        String uid = unique("History");
        String readerId = createReader(uid).get("id").asText();
        patchReader(readerId, "{\"active\":false}").andExpect(status().isOk());

        mockMvc.perform(get("/api/records/readers/" + uid).with(operator())).andExpect(status().isOk());
    }

    @Test
    void rotate_returns200WithNewToken() throws Exception {
        JsonNode created = createReader(unique("Rotate"));
        String oldToken = created.get("apitoken").asText();

        String body = rotate(created.get("id").asText())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String newToken = objectMapper.readTree(body).get("apitoken").asText();
        assertThat(newToken).matches("[0-9a-f]{32}").isNotEqualTo(oldToken);
    }

    @Test
    void rotate_unknownId_returns404() throws Exception {
        rotate(UUID.randomUUID().toString()).andExpect(status().isNotFound());
    }

    @Test
    void rotate_worksOnDisabledReader() throws Exception {
        String readerId = createReader(unique("RotateOff")).get("id").asText();
        patchReader(readerId, "{\"active\":false}").andExpect(status().isOk());

        rotate(readerId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }

    private ResultActions rotate(String readerId) throws Exception {
        return mockMvc.perform(post("/api/readers/" + readerId + "/token").with(admin()).with(csrf()));
    }

    private ResultActions patchReader(String readerId, String body) throws Exception {
        return mockMvc.perform(patch("/api/readers/" + readerId).with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private JsonNode createReader(String uid) throws Exception {
        String body = postReader(uid).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private ResultActions postReader(String uid) throws Exception {
        return mockMvc.perform(post("/api/readers").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("uid", uid))));
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMINISTRATEUR");
    }

    private static RequestPostProcessor operator() {
        return user("op").roles("OPERATEUR");
    }

    /** Readers live in an H2 context shared by all test classes, so every test needs its own uid. */
    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
