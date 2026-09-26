package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.support.ReferenceTagUids;

/** HTTP-level checks of POST /api/tags/scan from a PRODUCTION reader (spec 004: blank uid, duplicate reads; spec 010: uid not in the reference list). */
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

    @Autowired
    private ActivityRepository activityRepository;

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
        String uid = ReferenceTagUids.nextInList();
        scan(reader, "  " + uid + "  ", true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid));
    }

    @Test
    void scan_sameTagTwiceBySameReader_createsOneRecord() throws Exception {
        String uid = ReferenceTagUids.nextInList();
        long recordsBefore = recordRepository.count();

        String first = scan(reader, uid, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String firstProcessedAt = objectMapper.readTree(first).get("processedAt").asText();

        scan(reader, uid, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Duplicate read ignored"))
                .andExpect(jsonPath("$.isCompliant").value(true))
                .andExpect(jsonPath("$.processedAt").value(firstProcessedAt));

        assertThat(recordRepository.count()).isEqualTo(recordsBefore + 1);
    }

    @Test
    void scan_nonCompliantRepeat_lowersExistingRecord() throws Exception {
        String uid = ReferenceTagUids.nextInList();
        long recordsBefore = recordRepository.count();

        scan(reader, uid, true).andExpect(status().isOk());
        scan(reader, uid, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(false))
                .andExpect(jsonPath("$.message").value("Duplicate read ignored"));
        scan(reader, uid, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(false));

        assertThat(recordRepository.count()).isEqualTo(recordsBefore + 1);
        List<RecordEntity> records = recordRepository.findTop10ByReader_NameOrderByCreationDateDesc(reader.getName());
        assertThat(records).hasSize(1);
        assertThat(records.get(0).isCompliant()).isFalse();
    }

    @Test
    void scan_sameTagByTwoReaders_createsTwoRecords() throws Exception {
        String uid = ReferenceTagUids.nextInList();
        ReaderEntity otherReader = readerRepository.save(ReaderEntity.builder().name("Other tag scan test").build());
        long recordsBefore = recordRepository.count();

        scan(reader, uid, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist());
        scan(otherReader, uid, true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist());

        assertThat(recordRepository.count()).isEqualTo(recordsBefore + 2);
    }

    @Test
    void scan_offListUid_returns200Ignored_andCreatesNothing() throws Exception {
        String uid = ReferenceTagUids.offList();
        long tagsBefore = tagRepository.count();
        long recordsBefore = recordRepository.count();

        // isCompliant true whatever the reader sent: the line must not raise an alert for an ignored tag.
        scan(reader, uid, false)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.uid").value(uid))
                .andExpect(jsonPath("$.isCompliant").value(true))
                .andExpect(jsonPath("$.processedAt").exists())
                .andExpect(jsonPath("$.message").value("Tag not in reference list, ignored"));

        assertThat(tagRepository.findByUid(uid)).isEmpty();
        assertThat(tagRepository.count()).isEqualTo(tagsBefore);
        assertThat(recordRepository.count()).isEqualTo(recordsBefore);
    }

    // --- the record carries the line's activity at scan time (spec 012, User Story 3) ---

    @Test
    void scan_carriesTheCurrentActivity_andTheResponseIsUnchanged() throws Exception {
        ActivityEntity fraise = activity("Scan fraise");
        makeCurrent(fraise, OffsetDateTime.now());

        scan(reader, ReferenceTagUids.nextInList(), true)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").doesNotExist())
                .andExpect(jsonPath("$.activityId").doesNotExist());

        assertThat(latestRecord().getActivity().getId()).isEqualTo(fraise.getId());
    }

    @Test
    void scan_withoutActivity_createsARecordWithoutActivity() throws Exception {
        scan(reader, ReferenceTagUids.nextInList(), true).andExpect(status().isOk());

        assertThat(latestRecord().getActivity()).isNull();
    }

    @Test
    void scan_withAChoiceFromYesterday_createsARecordWithoutActivity() throws Exception {
        makeCurrent(activity("Scan fraise"), OffsetDateTime.now().minusDays(1).minusHours(1));

        scan(reader, ReferenceTagUids.nextInList(), true).andExpect(status().isOk());

        assertThat(latestRecord().getActivity()).isNull();
    }

    @Test
    void activityChange_newRecordsCarryTheNewOne_earlierOnesKeepTheirs() throws Exception {
        ActivityEntity fraise = activity("Scan fraise");
        ActivityEntity framboise = activity("Scan framboise");
        String uid = ReferenceTagUids.nextInList();
        makeCurrent(fraise, OffsetDateTime.now());
        scan(reader, uid, true).andExpect(status().isOk());
        RecordEntity first = latestRecord();

        makeCurrent(framboise, OffsetDateTime.now());
        // Within the duplicate window: ignored, and the first record keeps its activity.
        scan(reader, uid, false).andExpect(jsonPath("$.message").value("Duplicate read ignored"));
        assertThat(first.getActivity().getId()).isEqualTo(fraise.getId());

        scan(reader, ReferenceTagUids.nextInList(), true).andExpect(status().isOk());
        assertThat(latestRecord().getActivity().getId()).isEqualTo(framboise.getId());

        // Disabling and dissociating the activity afterwards leaves the records as they were.
        fraise.setActive(false);
        fraise.getLines().clear();
        activityRepository.saveAndFlush(fraise);
        assertThat(recordRepository.findById(first.getId()).orElseThrow().getActivity().getId())
                .isEqualTo(fraise.getId());
    }

    private ActivityEntity activity(String name) {
        ActivityEntity activity = ActivityEntity.builder().name(name).build();
        activity.getLines().add(reader);
        return activityRepository.saveAndFlush(activity);
    }

    private void makeCurrent(ActivityEntity activity, OffsetDateTime setAt) {
        reader.setCurrentActivity(activity);
        reader.setCurrentActivitySetAt(setAt);
        readerRepository.saveAndFlush(reader);
    }

    private RecordEntity latestRecord() {
        return recordRepository.findTop10ByReader_NameOrderByCreationDateDesc(reader.getName()).get(0);
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
