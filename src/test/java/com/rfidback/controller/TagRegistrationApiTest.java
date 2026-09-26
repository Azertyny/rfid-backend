package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

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
import com.rfidback.entity.BucketEntity;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.support.ReferenceTagUids;

/**
 * HTTP-level checks of POST /api/tags/buckets/{bucketNumber} (spec 003: add-only, move confirmation; spec 010:
 * off-list confirmation).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TagRegistrationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BucketRepository bucketRepository;

    @Autowired
    private TagRepository tagRepository;

    @Test
    void register_addsToExistingTags_andReturnsBothCounts() throws Exception {
        int bucketNumber = randomBucketNumber();
        String first = uniqueUid();
        String second = uniqueUid();

        register(bucketNumber, Map.of("uids", new String[] { first }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredCount").value(1))
                .andExpect(jsonPath("$.totalCount").value(1));
        register(bucketNumber, Map.of("uids", new String[] { second }))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketNumber").value(bucketNumber))
                .andExpect(jsonPath("$.registeredCount").value(1))
                .andExpect(jsonPath("$.totalCount").value(2));

        BucketEntity bucket = bucketRepository.findByNumber(bucketNumber).orElseThrow();
        mockMvc.perform(get("/api/buckets/" + bucket.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags", containsInAnyOrder(first, second)));
    }

    @Test
    void register_onlyBlankUids_returns400() throws Exception {
        int bucketNumber = randomBucketNumber();

        register(bucketNumber, Map.of("uids", new String[] { "  " })).andExpect(status().isBadRequest());

        assertThat(bucketRepository.findByNumber(bucketNumber)).isEmpty();
    }

    @Test
    void register_tagInOtherBucket_returns409UntilMoveIsConfirmed() throws Exception {
        int firstBucket = randomBucketNumber();
        int secondBucket = firstBucket + 1;
        String uid = uniqueUid();
        register(firstBucket, Map.of("uids", new String[] { uid })).andExpect(status().isOk());

        register(secondBucket, Map.of("uids", new String[] { uid }))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.tags[0].uid").value(uid))
                .andExpect(jsonPath("$.tags[0].bucketNumber").value(firstBucket));
        assertThat(tagRepository.findByUid(uid).orElseThrow().getBucket().getNumber()).isEqualTo(firstBucket);
        assertThat(bucketRepository.findByNumber(secondBucket)).isEmpty();

        register(secondBucket, Map.of("uids", new String[] { uid }, "moveConfirmed", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1));
        assertThat(tagRepository.findByUid(uid).orElseThrow().getBucket().getNumber()).isEqualTo(secondBucket);
    }

    @Test
    void register_exactly100Tags_returns200() throws Exception {
        int bucketNumber = randomBucketNumber();

        register(bucketNumber, Map.of("uids", uniqueUids(100)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredCount").value(100));
    }

    @Test
    void register_101Tags_returns400AndWritesNothing() throws Exception {
        int bucketNumber = randomBucketNumber();
        String[] uids = uniqueUids(101);

        register(bucketNumber, Map.of("uids", uids)).andExpect(status().isBadRequest());

        assertThat(bucketRepository.findByNumber(bucketNumber)).isEmpty();
        assertThat(tagRepository.findByUid(uids[0])).isEmpty();
    }

    @Test
    void register_offListTag_returns409UntilConfirmed() throws Exception {
        int bucketNumber = randomBucketNumber();
        String offList = ReferenceTagUids.offList();

        register(bucketNumber, Map.of("uids", new String[] { offList }))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.offListTags[0]").value(offList))
                .andExpect(jsonPath("$.tags", empty()));
        assertThat(tagRepository.findByUid(offList)).isEmpty();
        assertThat(bucketRepository.findByNumber(bucketNumber)).isEmpty();

        register(bucketNumber, Map.of("uids", new String[] { offList }, "offListConfirmed", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registeredCount").value(1));
        assertThat(tagRepository.findByUid(offList).orElseThrow().getBucket().getNumber()).isEqualTo(bucketNumber);
    }

    @Test
    void register_moveAndOffList_moveConfirmationAloneIsNotEnough() throws Exception {
        int firstBucket = randomBucketNumber();
        int secondBucket = firstBucket + 1;
        String moved = uniqueUid();
        String offList = ReferenceTagUids.offList();
        register(firstBucket, Map.of("uids", new String[] { moved })).andExpect(status().isOk());

        register(secondBucket, Map.of("uids", new String[] { moved, offList }, "moveConfirmed", true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.tags[0].uid").value(moved))
                .andExpect(jsonPath("$.offListTags[0]").value(offList));
        assertThat(tagRepository.findByUid(moved).orElseThrow().getBucket().getNumber()).isEqualTo(firstBucket);

        register(secondBucket,
                Map.of("uids", new String[] { moved, offList }, "moveConfirmed", true, "offListConfirmed", true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(2));
    }

    private ResultActions register(int bucketNumber, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/tags/buckets/" + bucketNumber)
                .with(admin())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return user("admin").roles("ADMINISTRATEUR");
    }

    // Above 100000 so these buckets never meet the ones other test classes create.
    private static int randomBucketNumber() {
        return ThreadLocalRandom.current().nextInt(100_000, 1_000_000_000);
    }

    // In the reference list, so only the tests about the list meet its confirmation (spec 010).
    private static String uniqueUid() {
        return ReferenceTagUids.nextInList();
    }

    private static String[] uniqueUids(int count) {
        return java.util.stream.IntStream.range(0, count).mapToObj(i -> uniqueUid()).toArray(String[]::new);
    }
}
