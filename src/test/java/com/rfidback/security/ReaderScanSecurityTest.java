package com.rfidback.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RegistrationReadRepository;
import com.rfidback.support.ReferenceTagUids;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReaderScanSecurityTest {

    // In the reference list, so the scans these cases let through are really stored (spec 010).
    private static final String UID = ReferenceTagUids.nextInList();
    private static final String SCAN_BODY = "{\"uid\":\"%s\",\"isCompliant\":true}".formatted(UID);
    private static final String READS_BODY = "{\"uids\":[\"%s\"]}".formatted(UID);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private RegistrationReadRepository registrationReadRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private ReaderEntity reader;
    private String readerToken;

    @BeforeEach
    void setUp() {
        reader = readerRepository.save(ReaderEntity.builder().name("Reader scan test").build());
        readerToken = reader.getApitoken();
    }

    @Test
    void scan_withValidReaderToken_succeedsWithoutSessionOrCsrf() throws Exception {
        mockMvc.perform(scan().header("x-api-token", readerToken))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void scan_withoutToken_returns401() throws Exception {
        mockMvc.perform(scan()).andExpect(status().isUnauthorized());
    }

    @Test
    void scan_withUnknownToken_returns401() throws Exception {
        mockMvc.perform(scan().header("x-api-token", "unknown-token")).andExpect(status().isUnauthorized());
    }

    @Test
    void scan_withDisabledReader_returns401_thenWorksAfterReactivation() throws Exception {
        reader.setActive(false);
        readerRepository.saveAndFlush(reader);
        mockMvc.perform(scan().header("x-api-token", readerToken)).andExpect(status().isUnauthorized());

        reader.setActive(true);
        readerRepository.saveAndFlush(reader);
        mockMvc.perform(scan().header("x-api-token", readerToken)).andExpect(status().isOk());
    }

    @Test
    void scan_withTokenReplacedByRotation_returns401_newTokenWorks() throws Exception {
        String body = mockMvc.perform(post("/api/readers/" + reader.getId() + "/token")
                        .with(user("admin").roles("ADMINISTRATEUR")).with(csrf()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String newToken = objectMapper.readTree(body).get("apitoken").asText();

        mockMvc.perform(scan().header("x-api-token", readerToken)).andExpect(status().isUnauthorized());
        mockMvc.perform(scan().header("x-api-token", newToken)).andExpect(status().isOk());
    }

    @Test
    void scan_fromEnregistrementReader_returns200WithoutRecord() throws Exception {
        ReaderEntity registrationReader = readerRepository.save(
                ReaderEntity.builder().name("Reader registration scan test").mode(ReaderMode.ENREGISTREMENT).build());
        long recordsBefore = recordRepository.count();

        mockMvc.perform(post("/api/tags/scan").header("x-api-token", registrationReader.getApitoken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"%s\",\"isCompliant\":false}".formatted(UID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(true))
                .andExpect(header().doesNotExist("Set-Cookie"));

        assertThat(recordRepository.count()).isEqualTo(recordsBefore);
    }

    @Test
    void scan_fromEnregistrementReader_withBlankUid_returns400() throws Exception {
        ReaderEntity registrationReader = readerRepository.save(ReaderEntity.builder()
                .name("Reader registration blank scan test").mode(ReaderMode.ENREGISTREMENT).build());

        mockMvc.perform(post("/api/tags/scan").header("x-api-token", registrationReader.getApitoken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uid\":\"   \",\"isCompliant\":true}"))
                .andExpect(status().isBadRequest());
    }

    // --- POST /api/tags/registration-reads (spec 011): same token chain as the scan ---

    @Test
    void registrationReads_fromEnregistrementReader_succeedsWithoutSessionOrCsrf() throws Exception {
        ReaderEntity registrationReader = readerRepository.save(ReaderEntity.builder()
                .name("Reader registration batch test").mode(ReaderMode.ENREGISTREMENT).build());

        mockMvc.perform(registrationReads().header("x-api-token", registrationReader.getApitoken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionOpen").value(false))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void registrationReads_withoutToken_returns401() throws Exception {
        mockMvc.perform(registrationReads()).andExpect(status().isUnauthorized());
    }

    @Test
    void registrationReads_withUnknownToken_returns401() throws Exception {
        mockMvc.perform(registrationReads().header("x-api-token", "unknown-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationReads_withDisabledReader_returns401() throws Exception {
        ReaderEntity registrationReader = readerRepository.save(ReaderEntity.builder()
                .name("Reader disabled batch test").mode(ReaderMode.ENREGISTREMENT).active(false).build());

        mockMvc.perform(registrationReads().header("x-api-token", registrationReader.getApitoken()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationReads_withUserSessionAndNoToken_returns401() throws Exception {
        mockMvc.perform(registrationReads().with(user("admin").roles("ADMINISTRATEUR")).with(csrf()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void registrationReads_fromProductionReader_returns403AndWritesNothing() throws Exception {
        long recordsBefore = recordRepository.count();
        long readsBefore = registrationReadRepository.count();

        mockMvc.perform(registrationReads().header("x-api-token", readerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("ENREGISTREMENT")));

        assertThat(recordRepository.count()).isEqualTo(recordsBefore);
        assertThat(registrationReadRepository.count()).isEqualTo(readsBefore);
    }

    private static MockHttpServletRequestBuilder registrationReads() {
        return post("/api/tags/registration-reads").contentType(MediaType.APPLICATION_JSON).content(READS_BODY);
    }

    private static MockHttpServletRequestBuilder scan() {
        return post("/api/tags/scan").contentType(MediaType.APPLICATION_JSON).content(SCAN_BODY);
    }
}
