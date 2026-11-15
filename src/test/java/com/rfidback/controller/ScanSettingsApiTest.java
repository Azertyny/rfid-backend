package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.OffsetDateTime;

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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordAnomaly;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;
import com.rfidback.support.ReferenceTagUids;

import jakarta.persistence.EntityManager;

/**
 * GET/PUT /api/scan-settings on a real database (spec 015, US5). Transactional: every change is rolled back, so the
 * other classes keep the installation defaults.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ScanSettingsApiTest {

    @Autowired
    private MockMvc mockMvc;

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
    private BucketRepository bucketRepository;

    @Autowired
    private PickerRepository pickerRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from scan_setting");
        userRepository.save(UserEntity.builder().username("settings-admin")
                .passwordHash(passwordEncoder.encode("irrelevant")).role(Role.ADMINISTRATEUR).enabled(true).build());
    }

    @Test
    void aFreshInstallation_showsTheDefaults() throws Exception {
        mockMvc.perform(get("/api/scan-settings").with(asAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rereadWindowMinutes.value").value(30))
                .andExpect(jsonPath("$.rereadWindowMinutes.updatedBy").doesNotExist())
                .andExpect(jsonPath("$.cheatWindowSeconds.value").value(2));
    }

    @Test
    void aChange_isKeptWithItsAuthor() throws Exception {
        save("{\"rereadWindowMinutes\":10,\"cheatWindowSeconds\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rereadWindowMinutes.value").value(10))
                .andExpect(jsonPath("$.rereadWindowMinutes.updatedBy").value("settings-admin"))
                .andExpect(jsonPath("$.cheatWindowSeconds.updatedBy").doesNotExist());

        mockMvc.perform(get("/api/scan-settings").with(asAdmin()))
                .andExpect(jsonPath("$.rereadWindowMinutes.value").value(10))
                .andExpect(jsonPath("$.rereadWindowMinutes.updatedAt").exists());
    }

    @Test
    void outOfRangeOrMissingValues_areRefused_andNothingChanges() throws Exception {
        for (String body : new String[] { "{\"rereadWindowMinutes\":-1,\"cheatWindowSeconds\":2}",
                "{\"rereadWindowMinutes\":1441,\"cheatWindowSeconds\":2}",
                "{\"rereadWindowMinutes\":30,\"cheatWindowSeconds\":61}",
                "{\"rereadWindowMinutes\":2.5,\"cheatWindowSeconds\":2}",
                "{\"rereadWindowMinutes\":30}" }) {
            save(body).andExpect(status().isBadRequest());
        }

        mockMvc.perform(get("/api/scan-settings").with(asAdmin()))
                .andExpect(jsonPath("$.rereadWindowMinutes.value").value(30))
                .andExpect(jsonPath("$.cheatWindowSeconds.value").value(2));
    }

    @Test
    void aNewDelay_appliesToTheNextScan_andChangesNoStoredRecord() throws Exception {
        ReaderEntity reader = readerRepository.save(ReaderEntity.builder().name("Settings scan reader").build());
        PickerEntity picker = pickerRepository.save(PickerEntity.builder().firstname("Set").lastname("Ting").build());
        BucketEntity bucket = bucketRepository.save(BucketEntity.builder().number(4501).picker(picker).build());
        String uid = ReferenceTagUids.nextInList();
        tagRepository.save(TagEntity.builder().uid(uid).bucket(bucket).build());
        scan(reader, uid).andExpect(status().isOk());
        // Counted 15 minutes ago: within the default 30-minute delay, a new read would be ignored.
        entityManager.flush();
        OffsetDateTime fifteenMinutesAgo = OffsetDateTime.now().minus(Duration.ofMinutes(15));
        jdbcTemplate.update("update record set creation_date = ?, last_read_at = ? where tag_id = "
                + "(select id from tag where uid = ?)", fifteenMinutesAgo, fifteenMinutesAgo, uid);
        jdbcTemplate.update("update tag set last_read_at = ? where uid = ?", fifteenMinutesAgo, uid);
        entityManager.clear();

        save("{\"rereadWindowMinutes\":10,\"cheatWindowSeconds\":2}").andExpect(status().isOk());

        scan(reader, uid)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(false))
                .andExpect(jsonPath("$.message").value("Tag already scanned today"));
        RecordEntity first = recordRepository.findTop10ByReader_NameOrderByCreationDateDesc(reader.getName()).get(1);
        assertThat(first.getAnomaly()).isNull();
        assertThat(first.isCompliant()).isTrue();
        assertThat(recordRepository.findTop10ByReader_NameOrderByCreationDateDesc(reader.getName()).get(0)
                .getAnomaly()).isEqualTo(RecordAnomaly.DOUBLON_PERDU);
    }

    private ResultActions save(String body) throws Exception {
        return mockMvc.perform(put("/api/scan-settings").with(asAdmin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions scan(ReaderEntity reader, String uid) throws Exception {
        return mockMvc.perform(post("/api/tags/scan").header("x-api-token", reader.getApitoken())
                .contentType(MediaType.APPLICATION_JSON).content("{\"uid\":\"" + uid + "\",\"isCompliant\":true}"));
    }

    private static RequestPostProcessor asAdmin() {
        return user("settings-admin").roles("ADMINISTRATEUR");
    }
}
