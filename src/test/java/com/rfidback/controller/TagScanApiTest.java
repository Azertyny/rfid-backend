package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;

/** HTTP-level checks of POST /api/tags/scan from a PRODUCTION reader (spec 004: blank uid, duplicate reads). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TagScanApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private TagRepository tagRepository;

    private ReaderEntity reader;

    @BeforeEach
    void setUp() {
        reader = readerRepository.save(ReaderEntity.builder().name("Reader tag scan test").build());
    }

    @Test
    void scan_withEmptyUid_returns400_andCreatesNothing() throws Exception {
        long recordsBefore = recordRepository.count();
        long tagsBefore = tagRepository.count();

        scan(reader, "", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("uid")));

        assertThat(recordRepository.count()).isEqualTo(recordsBefore);
        assertThat(tagRepository.count()).isEqualTo(tagsBefore);
    }

    @Test
    void scan_withWhitespaceUid_returns400_andCreatesNothing() throws Exception {
        long recordsBefore = recordRepository.count();
        long tagsBefore = tagRepository.count();

        scan(reader, "   ", true)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("uid")));

        assertThat(recordRepository.count()).isEqualTo(recordsBefore);
        assertThat(tagRepository.count()).isEqualTo(tagsBefore);
    }

    @Test
    void scan_withMissingIsCompliant_returns400() throws Exception {
        scan(reader, "X-1", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("isCompliant")));
    }

    @Test
    void scan_withSurroundingSpaces_isTrimmed() throws Exception {
        scan(reader, "  X-2  ", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value("X-2"));
    }

    @Test
    void scan_sameTagTwiceBySameReader_createsOneRecord() throws Exception {
        long recordsBefore = recordRepository.count();

        String first = scan(reader, "D-1", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String firstProcessedAt = objectMapper.readTree(first).get("processedAt").asText();

        scan(reader, "D-1", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Duplicate read ignored"))
                .andExpect(jsonPath("$.isCompliant").value(true))
                .andExpect(jsonPath("$.processedAt").value(firstProcessedAt));

        assertThat(recordRepository.count()).isEqualTo(recordsBefore + 1);
    }

    @Test
    void scan_nonCompliantRepeat_lowersExistingRecord() throws Exception {
        long recordsBefore = recordRepository.count();

        scan(reader, "D-3", true).andExpect(status().isOk());
        scan(reader, "D-3", false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(false))
                .andExpect(jsonPath("$.message").value("Duplicate read ignored"));
        scan(reader, "D-3", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(false));

        assertThat(recordRepository.count()).isEqualTo(recordsBefore + 1);
        List<RecordEntity> records = recordRepository.findTop10ByReader_NameOrderByCreationDateDesc(reader.getName());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).isCompliant()).isFalse();
    }

    @Test
    void scan_sameTagByTwoReaders_createsTwoRecords() throws Exception {
        ReaderEntity otherReader = readerRepository.save(ReaderEntity.builder().name("Other tag scan test").build());
        long recordsBefore = recordRepository.count();

        scan(reader, "D-2", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist());
        scan(otherReader, "D-2", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist());

        assertThat(recordRepository.count()).isEqualTo(recordsBefore + 2);
    }

    private ResultActions scan(ReaderEntity from, String uid, Boolean isCompliant) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uid", uid);
        if (isCompliant != null) {
            body.put("isCompliant", isCompliant);
        }
        return mockMvc.perform(post("/api/tags/scan")
                .header("x-api-token", from.getApitoken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
