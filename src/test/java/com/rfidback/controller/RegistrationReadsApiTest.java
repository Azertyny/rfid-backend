package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.rfidback.support.ReferenceTagUids;

/** HTTP-level checks of the registration batches sent by an enregistreur in one call (spec 011). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegistrationReadsApiTest {

    private static final String READS = "/api/tags/registration-reads";

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
    private RegistrationReadRepository readRepository;

    @Autowired
    private TagRepository tagRepository;

    @Autowired
    private BucketRepository bucketRepository;

    @Autowired
    private RecordRepository recordRepository;

    @Test
    void batch_keepsEveryTagInOrder_touchesSession_andCreatesNothingElse() throws Exception {
        ReaderEntity reader = registrationReader();
        UserEntity admin = admin();
        RegistrationSessionEntity session = openSession(reader, admin);
        OffsetDateTime activityBefore = session.getLastActivityAt();
        List<String> uids = inListUids(4);
        List<String> sent = new ArrayList<>(uids);
        sent.add(ReferenceTagUids.offList());
        long tagsBefore = tagRepository.count();
        long bucketsBefore = bucketRepository.count();
        long recordsBefore = recordRepository.count();

        // The off-list uid counts as received but is never kept (spec 010 révision, FR-003).
        send(reader, sent)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionOpen").value(true))
                .andExpect(jsonPath("$.receivedCount").value(5))
                .andExpect(jsonPath("$.addedCount").value(4))
                .andExpect(jsonPath("$.processedAt").exists())
                .andExpect(jsonPath("$.message").value("Registration reads kept"));

        assertThat(readUids(session)).containsExactlyElementsOf(uids);
        assertThat(sessionRepository.findById(session.getId()).orElseThrow().getLastActivityAt())
                .isAfter(activityBefore);
        assertThat(tagRepository.count()).isEqualTo(tagsBefore);
        assertThat(bucketRepository.count()).isEqualTo(bucketsBefore);
        assertThat(recordRepository.count()).isEqualTo(recordsBefore);

        // What the registration page polls (SC-002): every in-list tag, in the reader's order.
        mockMvc.perform(get("/api/tags/registration-sessions/" + session.getId()).with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reads[*].uid", contains(uids.toArray())));
    }

    @Test
    void batch_ofOffListUidsOnly_keepsNothing() throws Exception {
        ReaderEntity reader = registrationReader();
        RegistrationSessionEntity session = openSession(reader, admin());

        send(reader, List.of(ReferenceTagUids.offList(), ReferenceTagUids.offList()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionOpen").value(true))
                .andExpect(jsonPath("$.receivedCount").value(2))
                .andExpect(jsonPath("$.addedCount").value(0));

        assertThat(readUids(session)).isEmpty();
    }

    @Test
    void batch_countsRepeatsAndKnownReadsOnce() throws Exception {
        ReaderEntity reader = registrationReader();
        RegistrationSessionEntity session = openSession(reader, admin());
        String a = ReferenceTagUids.nextInList();
        String b = ReferenceTagUids.nextInList();
        send(reader, List.of(a)).andExpect(status().isOk());

        send(reader, List.of(a, b, b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(2))
                .andExpect(jsonPath("$.addedCount").value(1));

        assertThat(readUids(session)).containsExactly(a, b);
    }

    @Test
    void batch_trimsUidsAndIgnoresBlankElements() throws Exception {
        ReaderEntity reader = registrationReader();
        RegistrationSessionEntity session = openSession(reader, admin());
        String a = ReferenceTagUids.nextInList();
        String b = ReferenceTagUids.nextInList();

        send(reader, List.of(" " + a + " ", "", "   ", b))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(2))
                .andExpect(jsonPath("$.addedCount").value(2));

        assertThat(readUids(session)).containsExactly(a, b);
    }

    @Test
    void sameBatchTwice_addsNothingTheSecondTime() throws Exception {
        ReaderEntity reader = registrationReader();
        RegistrationSessionEntity session = openSession(reader, admin());
        List<String> uids = inListUids(3);
        send(reader, uids).andExpect(jsonPath("$.addedCount").value(3));

        send(reader, uids)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receivedCount").value(3))
                .andExpect(jsonPath("$.addedCount").value(0));

        assertThat(readUids(session)).containsExactlyElementsOf(uids);
    }

    @Test
    void batchWithoutSession_isIgnored() throws Exception {
        ReaderEntity reader = registrationReader();

        send(reader, inListUids(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionOpen").value(false))
                .andExpect(jsonPath("$.receivedCount").value(2))
                .andExpect(jsonPath("$.addedCount").value(0))
                .andExpect(jsonPath("$.message").value("Registration reads ignored: no session started"));

        assertThat(sessionRepository.findByReader(reader)).isEmpty();
    }

    @Test
    void batchOnExpiredSession_isIgnored_andSessionIsDeleted() throws Exception {
        ReaderEntity reader = registrationReader();
        OffsetDateTime tenMinutesAgo = OffsetDateTime.now().minusMinutes(10);
        RegistrationSessionEntity session = sessionRepository.save(RegistrationSessionEntity.builder()
                .reader(reader).startedBy(admin()).startedAt(tenMinutesAgo).lastActivityAt(tenMinutesAgo).build());
        readRepository.save(RegistrationReadEntity.builder().session(session).uid(ReferenceTagUids.nextInList())
                .firstReadAt(tenMinutesAgo).build());

        send(reader, inListUids(2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionOpen").value(false))
                .andExpect(jsonPath("$.addedCount").value(0));

        assertThat(sessionRepository.findById(session.getId())).isEmpty();
        assertThat(readRepository.findAll()).noneMatch(read -> read.getSession().getId().equals(session.getId()));
    }

    @Test
    void emptyOrBlankLists_return400_andLeaveTheSessionUntouched() throws Exception {
        ReaderEntity reader = registrationReader();
        RegistrationSessionEntity session = openSession(reader, admin());
        OffsetDateTime activityBefore = session.getLastActivityAt();

        sendRaw(reader, "{\"uids\":[]}").andExpect(status().isBadRequest());
        sendRaw(reader, "{}").andExpect(status().isBadRequest());
        send(reader, List.of("", " ")).andExpect(status().isBadRequest());

        assertThat(readUids(session)).isEmpty();
        assertThat(sessionRepository.findById(session.getId()).orElseThrow().getLastActivityAt())
                .isEqualTo(activityBefore);
    }

    @Test
    void hundredDistinctUids_areAccepted_hundredAndOne_areRefused() throws Exception {
        ReaderEntity reader = registrationReader();
        RegistrationSessionEntity session = openSession(reader, admin());
        List<String> hundredAndOne = inListUids(101);

        send(reader, hundredAndOne).andExpect(status().isBadRequest());
        assertThat(readUids(session)).isEmpty();

        send(reader, hundredAndOne.subList(0, 100))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.addedCount").value(100));
    }

    @Test
    void moreThanAThousandElements_orAnOverlongUid_return400() throws Exception {
        ReaderEntity reader = registrationReader();
        RegistrationSessionEntity session = openSession(reader, admin());

        send(reader, Collections.nCopies(1001, ReferenceTagUids.nextInList())).andExpect(status().isBadRequest());
        send(reader, List.of(ReferenceTagUids.nextInList(), "A".repeat(51))).andExpect(status().isBadRequest());

        assertThat(readUids(session)).isEmpty();
    }

    @Test
    void savingTheSessionAfterABatch_registersTheTagsOnTheBucket() throws Exception {
        ReaderEntity reader = registrationReader();
        UserEntity admin = admin();
        RegistrationSessionEntity session = openSession(reader, admin);
        List<String> uids = inListUids(3);
        send(reader, uids).andExpect(status().isOk());
        int bucketNumber = ThreadLocalRandom.current().nextInt(100_000, 1_000_000_000);

        mockMvc.perform(post("/api/tags/registration-sessions/" + session.getId() + "/save").with(as(admin))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bucketNumber", bucketNumber))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredCount").value(3));

        for (String uid : uids) {
            assertThat(tagRepository.findByUid(uid).orElseThrow().getBucket().getNumber()).isEqualTo(bucketNumber);
        }
    }

    /**
     * Outside the test transaction: each call must commit on its own, and the second one must wait for the first's
     * lock on the session (research R5).
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void twoConcurrentBatches_keepEachTagOnce() throws Exception {
        ReaderEntity reader = registrationReader();
        UserEntity owner = admin();
        RegistrationSessionEntity session = openSession(reader, owner);
        List<String> uids = inListUids(10);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<MvcResult>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(executor.submit(() -> {
                    go.await();
                    return send(reader, uids).andReturn();
                }));
            }
            go.countDown();

            int added = 0;
            for (Future<MvcResult> call : calls) {
                MvcResult result = call.get(30, TimeUnit.SECONDS);
                assertThat(result.getResponse().getStatus()).isEqualTo(200);
                JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
                added += body.get("addedCount").asInt();
            }
            assertThat(added).isEqualTo(10);
            assertThat(readUids(session)).containsExactlyInAnyOrderElementsOf(uids);
        } finally {
            executor.shutdownNow();
            readRepository.deleteBySession(session);
            sessionRepository.deleteById(session.getId());
            readerRepository.delete(reader);
            userRepository.delete(owner);
        }
    }

    // --- helpers ---

    private ResultActions send(ReaderEntity reader, List<String> uids) throws Exception {
        return sendRaw(reader, objectMapper.writeValueAsString(Map.of("uids", uids)));
    }

    private ResultActions sendRaw(ReaderEntity reader, String body) throws Exception {
        return mockMvc.perform(post(READS).header("x-api-token", reader.getApitoken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private List<String> readUids(RegistrationSessionEntity session) {
        return readRepository.findAllBySessionOrderByFirstReadAtAsc(session).stream()
                .map(RegistrationReadEntity::getUid)
                .toList();
    }

    private RegistrationSessionEntity openSession(ReaderEntity reader, UserEntity owner) {
        OffsetDateTime aMinuteAgo = OffsetDateTime.now().minusMinutes(1);
        return sessionRepository.save(RegistrationSessionEntity.builder().reader(reader).startedBy(owner)
                .startedAt(aMinuteAgo).lastActivityAt(aMinuteAgo).build());
    }

    /** Sessions record who started them, so every Administrateur needs a row in app_user. */
    private UserEntity admin() {
        String username = "admin-" + UUID.randomUUID().toString().substring(0, 8);
        return userRepository.save(UserEntity.builder().username(username)
                .passwordHash("hash").role(Role.ADMINISTRATEUR).enabled(true).build());
    }

    private ReaderEntity registrationReader() {
        return readerRepository.save(ReaderEntity.builder()
                .name("Poste enregistrement " + UUID.randomUUID().toString().substring(0, 8))
                .mode(ReaderMode.ENREGISTREMENT).build());
    }

    private static RequestPostProcessor as(UserEntity admin) {
        return user(admin.getUsername()).roles("ADMINISTRATEUR");
    }

    // In the reference list, so the reads are kept (spec 010).
    private static List<String> inListUids(int count) {
        List<String> uids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            uids.add(ReferenceTagUids.nextInList());
        }
        return uids;
    }
}
