package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.support.ReferenceTagUids;

/** HTTP-level checks of GET /api/tags/off-list (spec 010, FR-009). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TagOffListApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReaderRepository readerRepository;

    @Test
    void list_showsOffListTagsWithBucketAndReads_andNoInListTag() throws Exception {
        ReaderEntity lineOne = productionReader();
        ReaderEntity lineTwo = productionReader();
        int bucketNumber = ThreadLocalRandom.current().nextInt(100_000, 1_000_000_000);
        String inList = ReferenceTagUids.nextInList();
        String registered = ReferenceTagUids.offList();
        String scannedOnly = ReferenceTagUids.offList();

        register(bucketNumber, inList, false);
        register(bucketNumber, registered, true);
        // Two readers, so the duplicate window of one reader doesn't merge the two reads.
        scan(lineOne, registered);
        scan(lineTwo, registered);
        scan(lineOne, scannedOnly);
        scan(lineOne, inList);

        String body = mockMvc.perform(get("/api/tags/off-list").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceListSize").value(5008))
                .andReturn().getResponse().getContentAsString();

        Map<String, JsonNode> byUid = new HashMap<>();
        for (JsonNode tag : objectMapper.readTree(body).get("tags")) {
            byUid.put(tag.get("uid").asText(), tag);
        }
        assertThat(byUid).doesNotContainKey(inList);

        JsonNode registeredTag = byUid.get(registered);
        assertThat(registeredTag.get("bucketNumber").asInt()).isEqualTo(bucketNumber);
        assertThat(registeredTag.get("recordCount").asLong()).isEqualTo(2);
        assertThat(registeredTag.get("lastRecordAt").isNull()).isFalse();
        assertThat(registeredTag.get("createdAt").isNull()).isFalse();

        JsonNode scannedTag = byUid.get(scannedOnly);
        assertThat(scannedTag.path("bucketNumber").isMissingNode() || scannedTag.get("bucketNumber").isNull())
                .isTrue();
        assertThat(scannedTag.get("recordCount").asLong()).isEqualTo(1);
    }

    @Test
    void list_asOperator_returns403() throws Exception {
        mockMvc.perform(get("/api/tags/off-list").with(user("op").roles("OPERATEUR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_withReaderToken_returns403() throws Exception {
        mockMvc.perform(get("/api/tags/off-list").header("x-api-token", productionReader().getApitoken()))
                .andExpect(status().isForbidden());
    }

    private void register(int bucketNumber, String uid, boolean offListConfirmed) throws Exception {
        mockMvc.perform(post("/api/tags/buckets/" + bucketNumber).with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("uids", new String[] { uid }, "offListConfirmed", offListConfirmed))))
                .andExpect(status().isOk());
    }

    private void scan(ReaderEntity reader, String uid) throws Exception {
        mockMvc.perform(post("/api/tags/scan").header("x-api-token", reader.getApitoken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("uid", uid, "isCompliant", true))))
                .andExpect(status().isOk());
    }

    private ReaderEntity productionReader() {
        return readerRepository.save(ReaderEntity.builder().name("Off-list line " + UUID.randomUUID()).build());
    }

    private static RequestPostProcessor asAdmin() {
        return user("admin").roles("ADMINISTRATEUR");
    }
}
