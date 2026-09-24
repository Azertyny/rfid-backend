package com.rfidback.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.rfidback.repository.ReaderRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReaderScanSecurityTest {

    private static final String SCAN_BODY = "{\"uid\":\"E2000017221101891400A23G\",\"isCompliant\":true}";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReaderRepository readerRepository;

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

    private static MockHttpServletRequestBuilder scan() {
        return post("/api/tags/scan").contentType(MediaType.APPLICATION_JSON).content(SCAN_BODY);
    }
}
