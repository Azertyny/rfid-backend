package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.ReaderMode;
import com.rfidback.entity.RegistrationReadEntity;
import com.rfidback.entity.RegistrationSessionEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.RegistrationReadRepository;
import com.rfidback.repository.RegistrationSessionRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;

/** HTTP-level checks of the tag-registration sessions, including scans sent with a real reader token (spec 003). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegistrationApiTest {

    private static final String SESSIONS = "/api/tags/registration-sessions";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RegistrationSessionRepository sessionRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private BucketRepository bucketRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Autowired
    private RegistrationReadRepository readRepository;

    @Test
    void fullFlow_readsGoToTheSessionThenSaveRegistersThemWithoutRecords() throws Exception {
        UserEntity admin = admin();
        ReaderEntity reader = registrationReader();
        String first = uniqueUid();
        String second = uniqueUid();
        long recordsBefore = recordRepository.count();

        String sessionId = start(admin, reader)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startedBy").value(admin.getUsername()))
                .andExpect(jsonPath("$.readerUid").value(reader.getName()))
                .andExpect(jsonPath("$.reads", hasSize(0)))
                .andReturn().getResponse().getContentAsString();
        sessionId = objectMapper.readTree(sessionId).get("id").asText();

        scan(reader, first)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(true))
                .andExpect(jsonPath("$.message").value("Registration read"));
        scan(reader, first).andExpect(status().isOk());
        scan(reader, second).andExpect(status().isOk());
        assertThat(tagRepository.findByUid(first)).isEmpty();

        mockMvc.perform(get(SESSIONS + "/" + sessionId).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reads[*].uid", containsInAnyOrder(first, second)));

        int bucketNumber = randomBucketNumber();
        save(admin, sessionId, Map.of("bucketNumber", bucketNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredCount").value(2))
                .andExpect(jsonPath("$.totalCount").value(2));

        assertThat(tagRepository.findByUid(first).orElseThrow().getBucket().getNumber()).isEqualTo(bucketNumber);
        assertThat(recordRepository.count()).isEqualTo(recordsBefore);
        mockMvc.perform(get(SESSIONS + "/" + sessionId).with(as(admin))).andExpect(status().isNotFound());
    }

    @Test
    void scanWithoutSession_isIgnoredAndCreatesNothing() throws Exception {
        ReaderEntity reader = registrationReader();
        String uid = uniqueUid();
        long recordsBefore = recordRepository.count();

        scan(reader, uid)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isCompliant").value(true))
                .andExpect(jsonPath("$.message").value("Registration read ignored: no session started"));

        assertThat(tagRepository.findByUid(uid)).isEmpty();
        assertThat(recordRepository.count()).isEqualTo(recordsBefore);
    }

    @Test
    void start_onProductionReader_returns400() throws Exception {
        ReaderEntity reader = readerRepository.save(ReaderEntity.builder().name(unique("Prod")).build());

        start(admin(), reader).andExpect(status().isBadRequest());
    }

    @Test
    void start_onBusyReader_returns409WithStartedBy() throws Exception {
        UserEntity first = admin();
        ReaderEntity reader = registrationReader();
        start(first, reader).andExpect(status().isCreated());

        start(admin(), reader)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.startedBy").value(first.getUsername()))
                .andExpect(jsonPath("$.startedAt").exists());
    }

    @Test
    void get_bySecondAdministrator_returns403() throws Exception {
        String sessionId = startedSessionId(admin(), registrationReader());

        mockMvc.perform(get(SESSIONS + "/" + sessionId).with(as(admin()))).andExpect(status().isForbidden());
    }

    @Test
    void cancel_thenGet_returns404() throws Exception {
        UserEntity admin = admin();
        String sessionId = startedSessionId(admin, registrationReader());

        mockMvc.perform(delete(SESSIONS + "/" + sessionId).with(as(admin)).with(csrf()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(SESSIONS + "/" + sessionId).with(as(admin))).andExpect(status().isNotFound());
    }

    @Test
    void save_withTagInOtherBucket_returns409ThenConfirmedReturns200AndClosesSession() throws Exception {
        UserEntity admin = admin();
        ReaderEntity reader = registrationReader();
        String uid = uniqueUid();
        int firstBucket = randomBucketNumber();
        int secondBucket = firstBucket + 1;
        mockMvc.perform(post("/api/tags/buckets/" + firstBucket).with(as(admin)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("uids", new String[] { uid }))))
                .andExpect(status().isOk());
        String sessionId = startedSessionId(admin, reader);
        scan(reader, uid).andExpect(status().isOk());

        mockMvc.perform(get(SESSIONS + "/" + sessionId).with(as(admin)))
                .andExpect(jsonPath("$.reads[0].bucketNumber").value(firstBucket));
        save(admin, sessionId, Map.of("bucketNumber", secondBucket))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.tags[0].uid").value(uid))
                .andExpect(jsonPath("$.tags[0].bucketNumber").value(firstBucket));
        mockMvc.perform(get(SESSIONS + "/" + sessionId).with(as(admin))).andExpect(status().isOk());

        save(admin, sessionId, Map.of("bucketNumber", secondBucket, "moveConfirmed", true))
                .andExpect(status().isOk());

        assertThat(tagRepository.findByUid(uid).orElseThrow().getBucket().getNumber()).isEqualTo(secondBucket);
        mockMvc.perform(get(SESSIONS + "/" + sessionId).with(as(admin))).andExpect(status().isNotFound());
    }

    @Test
    void save_withoutReads_returns400() throws Exception {
        UserEntity admin = admin();
        String sessionId = startedSessionId(admin, registrationReader());
        int bucketNumber = randomBucketNumber();

        save(admin, sessionId, Map.of("bucketNumber", bucketNumber)).andExpect(status().isBadRequest());

        assertThat(bucketRepository.findByNumber(bucketNumber)).isEmpty();
    }

    @Test
    void save_withMoreThan100Reads_returns400AndKeepsSession() throws Exception {
        UserEntity admin = admin();
        String sessionId = startedSessionId(admin, registrationReader());
        RegistrationSessionEntity session = sessionRepository.findById(UUID.fromString(sessionId)).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now();
        for (int i = 0; i < 101; i++) {
            readRepository.save(RegistrationReadEntity.builder().session(session).uid(uniqueUid())
                    .firstReadAt(now).build());
        }
        int bucketNumber = randomBucketNumber();

        save(admin, sessionId, Map.of("bucketNumber", bucketNumber)).andExpect(status().isBadRequest());

        assertThat(bucketRepository.findByNumber(bucketNumber)).isEmpty();
        mockMvc.perform(get(SESSIONS + "/" + sessionId).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reads", hasSize(101)));
    }

    @Test
    void expiredSession_canBeReplaced() throws Exception {
        ReaderEntity reader = registrationReader();
        expiredSession(reader, admin());

        start(admin(), reader).andExpect(status().isCreated());
    }

    /**
     * Runs outside the class's test transaction: inside it, the test would read its own uncommitted changes and
     * could not tell whether the service's transaction committed the deletion or rolled it back with the 404.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void get_expiredSession_returns404AndDeletionIsCommitted() throws Exception {
        ReaderEntity reader = registrationReader();
        UserEntity owner = admin();
        RegistrationSessionEntity session = expiredSession(reader, owner);
        try {
            mockMvc.perform(get(SESSIONS + "/" + session.getId()).with(as(owner))).andExpect(status().isNotFound());

            assertThat(sessionRepository.findById(session.getId())).isEmpty();
        } finally {
            sessionRepository.findById(session.getId()).ifPresent(sessionRepository::delete);
            readerRepository.delete(reader);
            userRepository.delete(owner);
        }
    }

    // --- helpers ---

    private ResultActions start(UserEntity admin, ReaderEntity reader) throws Exception {
        return mockMvc.perform(post(SESSIONS).with(as(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("readerId", reader.getId()))));
    }

    private String startedSessionId(UserEntity admin, ReaderEntity reader) throws Exception {
        String body = start(admin, reader).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private ResultActions save(UserEntity admin, String sessionId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(SESSIONS + "/" + sessionId + "/save").with(as(admin)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions scan(ReaderEntity reader, String uid) throws Exception {
        return mockMvc.perform(post("/api/tags/scan").header("x-api-token", reader.getApitoken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("uid", uid, "isCompliant", false))));
    }

    private RegistrationSessionEntity expiredSession(ReaderEntity reader, UserEntity owner) {
        OffsetDateTime tenMinutesAgo = OffsetDateTime.now().minusMinutes(10);
        return sessionRepository.save(RegistrationSessionEntity.builder().reader(reader).startedBy(owner)
                .startedAt(tenMinutesAgo).lastActivityAt(tenMinutesAgo).build());
    }

    /** Sessions record who started them, so every Administrateur needs a row in app_user. */
    private UserEntity admin() {
        return userRepository.save(UserEntity.builder().username(unique("admin").replace(' ', '-'))
                .passwordHash("hash").role(Role.ADMINISTRATEUR).enabled(true).build());
    }

    private ReaderEntity registrationReader() {
        return readerRepository.save(ReaderEntity.builder().name(unique("Poste enregistrement"))
                .mode(ReaderMode.ENREGISTREMENT).build());
    }

    private static RequestPostProcessor as(UserEntity admin) {
        return user(admin.getUsername()).roles("ADMINISTRATEUR");
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String uniqueUid() {
        return "TAG-" + UUID.randomUUID();
    }

    private static int randomBucketNumber() {
        return ThreadLocalRandom.current().nextInt(100_000, 1_000_000_000);
    }
}
