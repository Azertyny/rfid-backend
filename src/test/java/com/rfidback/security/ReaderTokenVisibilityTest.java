package com.rfidback.security;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.ReaderEntity;
import com.rfidback.repository.ReaderRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReaderTokenVisibilityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ReaderRepository readerRepository;

    private String readerToken;

    @BeforeEach
    void setUp() {
        readerToken = readerRepository.save(ReaderEntity.builder().name("Reader visibility test").build())
                .getApitoken();
    }

    @Test
    void operator_seesReadersWithoutTokens() throws Exception {
        mockMvc.perform(get("/api/readers").with(user("op").roles("OPERATEUR")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readers[?(@.uid == 'Reader visibility test')]").exists())
                .andExpect(content().string(not(containsString(readerToken))))
                .andExpect(content().string(not(containsString("\"apitoken\""))));
    }

    @Test
    void administrator_seesReaderTokens() throws Exception {
        mockMvc.perform(get("/api/readers").with(user("admin").roles("ADMINISTRATEUR")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(readerToken)));
    }
}
