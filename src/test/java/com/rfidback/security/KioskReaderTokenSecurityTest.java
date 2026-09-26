package com.rfidback.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

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

import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordConformityChangeEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.TagEntity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordConformityChangeRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;

/**
 * The line kiosk uses its reader's token on its own records only (spec 008 FR-005a, US2 scenarios 6-7; spec 005 FR-004).
 * No request here carries a CSRF token: the kiosk never sends one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class KioskReaderTokenSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private RecordConformityChangeRepository recordConformityChangeRepository;

    private ReaderEntity lineOne;
    private RecordEntity lineOneRecord;
    private RecordEntity lineTwoRecord;

    @BeforeEach
    void setUp() {
        lineOne = readerRepository.save(ReaderEntity.builder().name("Kiosk L1").build());
        ReaderEntity lineTwo = readerRepository.save(ReaderEntity.builder().name("Kiosk L2").build());
        lineOneRecord = saveRecord(lineOne, "KIOSK-1");
        lineTwoRecord = saveRecord(lineTwo, "KIOSK-2");
    }

    @Test
    void list_ownReader_returns200WithoutSession() throws Exception {
        mockMvc.perform(get("/api/records/readers/{readerId}", "Kiosk L1").header("x-api-token", lineOne.getApitoken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.records.length()").value(1))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void list_otherReader_returns403() throws Exception {
        mockMvc.perform(get("/api/records/readers/{readerId}", "Kiosk L2").header("x-api-token", lineOne.getApitoken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void list_unknownReaderName_returns403() throws Exception {
        mockMvc.perform(get("/api/records/readers/{readerId}", "No such reader")
                        .header("x-api-token", lineOne.getApitoken()))
                .andExpect(status().isForbidden());
    }

    @Test
    void patch_ownRecord_returns204AndCreditsReader() throws Exception {
        patchAsKiosk(lineOneRecord.getId(), false).andExpect(status().isNoContent());

        List<RecordConformityChangeEntity> changes =
                recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(lineOneRecord);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).getAuthorReader().getId()).isEqualTo(lineOne.getId());
        assertThat(changes.get(0).getAuthor()).isNull();

        mockMvc.perform(get("/api/records/{recordId}/conformity-history", lineOneRecord.getId())
                        .with(user("admin").roles("ADMINISTRATEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changes[0].authorType").value("READER"))
                .andExpect(jsonPath("$.changes[0].authorReaderUid").value("Kiosk L1"))
                .andExpect(content().string(not(containsString("authorUsername"))));
    }

    @Test
    void patch_otherReadersRecord_returns403AndChangesNothing() throws Exception {
        patchAsKiosk(lineTwoRecord.getId(), false).andExpect(status().isForbidden());

        assertThat(recordRepository.findById(lineTwoRecord.getId()).orElseThrow().isCompliant()).isTrue();
        assertThat(recordConformityChangeRepository.findAllByRecordOrderByChangedAtAsc(lineTwoRecord)).isEmpty();
    }

    @Test
    void patch_unknownRecord_returns404() throws Exception {
        patchAsKiosk(UUID.randomUUID(), false).andExpect(status().isNotFound());
    }

    @Test
    void disabledReader_returns401() throws Exception {
        lineOne.setActive(false);
        readerRepository.saveAndFlush(lineOne);

        mockMvc.perform(get("/api/records/readers/{readerId}", "Kiosk L1").header("x-api-token", lineOne.getApitoken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operatorSession_stillWorks_withoutToken() throws Exception {
        mockMvc.perform(get("/api/records/readers/{readerId}", "Kiosk L2").with(user("op").roles("OPERATEUR")))
                .andExpect(status().isOk());
    }

    // --- the line's current activity (spec 012, FR-009) ---

    @Test
    void lineActivity_ownLine_returns200() throws Exception {
        ActivityEntity fraise = activityRepository.save(ActivityEntity.builder().name("Kiosk fraise").build());
        fraise.getLines().add(lineOne);

        mockMvc.perform(get("/api/lines/{readerUid}/current-activity", "Kiosk L1")
                        .header("x-api-token", lineOne.getApitoken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentActivity").isEmpty());
        mockMvc.perform(put("/api/lines/{readerUid}/current-activity", "Kiosk L1")
                        .header("x-api-token", lineOne.getApitoken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":\"" + fraise.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentActivity.name").value("Kiosk fraise"));
    }

    @Test
    void lineActivity_otherLine_returns403() throws Exception {
        mockMvc.perform(get("/api/lines/{readerUid}/current-activity", "Kiosk L2")
                        .header("x-api-token", lineOne.getApitoken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/lines/{readerUid}/current-activity", "Kiosk L2")
                        .header("x-api-token", lineOne.getApitoken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"activityId\":null}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void lineActivity_disabledReader_returns401() throws Exception {
        lineOne.setActive(false);
        readerRepository.saveAndFlush(lineOne);

        mockMvc.perform(get("/api/lines/{readerUid}/current-activity", "Kiosk L1")
                        .header("x-api-token", lineOne.getApitoken()))
                .andExpect(status().isUnauthorized());
    }

    private ResultActions patchAsKiosk(UUID recordId, boolean isCompliant) throws Exception {
        return mockMvc.perform(patch("/api/records/{recordId}/conformity", recordId)
                .header("x-api-token", lineOne.getApitoken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isCompliant\":" + isCompliant + "}"));
    }

    private RecordEntity saveRecord(ReaderEntity reader, String tagUid) {
        TagEntity tag = tagRepository.save(TagEntity.builder().uid(tagUid).build());
        return recordRepository.saveAndFlush(RecordEntity.builder().tag(tag).reader(reader).compliant(true).build());
    }
}
