package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;

/** HTTP-level checks of the bucket routes (spec 006), run as an Administrateur. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BucketApiTest {

    private static final AtomicInteger NEXT_BUCKET_NUMBER = new AtomicInteger(91000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BucketRepository bucketRepository;

    @Autowired
    private PickerRepository pickerRepository;

    @Test
    void listBuckets_returnsCreatedBucketsInNumberOrder() throws Exception {
        int first = createBucket().getNumber();
        int second = createBucket().getNumber();

        JsonNode buckets = readJson(mockMvc.perform(get("/api/buckets").with(admin()))
                .andExpect(status().isOk())).get("buckets");

        List<Integer> numbers = new ArrayList<>();
        buckets.forEach(bucket -> numbers.add(bucket.get("number").asInt()));
        assertThat(numbers).isSorted();
        assertThat(numbers).contains(first, second);
    }

    @Test
    void getBucket_returns200WithEmptyTagsAndNullPicker() throws Exception {
        BucketEntity bucket = createBucket();

        JsonNode json = readJson(mockMvc.perform(get("/api/buckets/" + bucket.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(bucket.getNumber()))
                .andExpect(jsonPath("$.tags").isEmpty()));
        assertNoPicker(json);
    }

    @Test
    void getBucket_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/buckets/" + UUID.randomUUID()).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void assign_returns204AndDetailShowsPicker() throws Exception {
        PickerEntity picker = createPicker("Assign");
        BucketEntity bucket = createBucket();

        assign(bucket.getId(), picker.getId()).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/buckets/" + bucket.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.picker.lastname").value(picker.getLastname()))
                .andExpect(jsonPath("$.picker.firstname").value(picker.getFirstname()));
    }

    @Test
    void reassign_replacesPicker() throws Exception {
        PickerEntity previous = createPicker("Previous");
        PickerEntity next = createPicker("Next");
        BucketEntity bucket = createBucket();
        assign(bucket.getId(), previous.getId()).andExpect(status().isNoContent());

        assign(bucket.getId(), next.getId()).andExpect(status().isNoContent());

        mockMvc.perform(get("/api/buckets/" + bucket.getId()).with(admin()))
                .andExpect(jsonPath("$.picker.lastname").value(next.getLastname()));
        mockMvc.perform(get("/api/pickers/" + previous.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketNumbers").isEmpty());
        mockMvc.perform(get("/api/pickers/" + next.getId()).with(admin()))
                .andExpect(jsonPath("$.bucketNumbers[0]").value(bucket.getNumber()));
    }

    @Test
    void assign_unknownBucket_returns404() throws Exception {
        PickerEntity picker = createPicker("NoBucket");

        assign(UUID.randomUUID(), picker.getId()).andExpect(status().isNotFound());
    }

    @Test
    void assign_unknownPicker_returns404() throws Exception {
        BucketEntity bucket = createBucket();

        assign(bucket.getId(), UUID.randomUUID()).andExpect(status().isNotFound());

        assertNoPicker(readJson(mockMvc.perform(get("/api/buckets/" + bucket.getId()).with(admin()))));
    }

    @Test
    void assign_missingPickerId_returns400() throws Exception {
        BucketEntity bucket = createBucket();

        mockMvc.perform(put("/api/buckets/" + bucket.getId() + "/picker").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pickerWithTwoBuckets_listAndDetailReturn200WithBothNumbers() throws Exception {
        PickerEntity picker = createPicker("TwoBuckets");
        BucketEntity second = createBucket();
        BucketEntity first = createBucket();
        // Assign the higher number first, so the API has to sort them.
        assign(first.getId(), picker.getId()).andExpect(status().isNoContent());
        assign(second.getId(), picker.getId()).andExpect(status().isNoContent());
        List<Integer> expected = List.of(second.getNumber(), first.getNumber());

        JsonNode detail = readJson(mockMvc.perform(get("/api/pickers/" + picker.getId()).with(admin()))
                .andExpect(status().isOk()));
        assertThat(bucketNumbers(detail)).isEqualTo(expected);

        assertThat(bucketNumbers(findInPickerList(picker.getId()))).isEqualTo(expected);
    }

    @Test
    void unassign_returns204AndDetailShowsNoPicker() throws Exception {
        PickerEntity picker = createPicker("Unassign");
        BucketEntity bucket = createBucket();
        assign(bucket.getId(), picker.getId()).andExpect(status().isNoContent());

        unassign(bucket.getId()).andExpect(status().isNoContent());

        assertNoPicker(readJson(mockMvc.perform(get("/api/buckets/" + bucket.getId()).with(admin()))
                .andExpect(status().isOk())));
    }

    @Test
    void unassign_bucketWithoutPicker_returns204() throws Exception {
        unassign(createBucket().getId()).andExpect(status().isNoContent());
    }

    @Test
    void unassign_unknownBucket_returns404() throws Exception {
        unassign(UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void unassignThenDeletePicker_returns204() throws Exception {
        PickerEntity picker = createPicker("Free");
        BucketEntity bucket = createBucket();
        assign(bucket.getId(), picker.getId()).andExpect(status().isNoContent());
        unassign(bucket.getId()).andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/pickers/" + picker.getId()).with(admin()).with(csrf()))
                .andExpect(status().isNoContent());
    }

    private ResultActions assign(UUID bucketId, UUID pickerId) throws Exception {
        return mockMvc.perform(put("/api/buckets/" + bucketId + "/picker").with(admin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("pickerId", pickerId.toString()))));
    }

    private ResultActions unassign(UUID bucketId) throws Exception {
        return mockMvc.perform(delete("/api/buckets/" + bucketId + "/picker").with(admin()).with(csrf()));
    }

    /** Walks GET /api/pickers page by page, like the front does, until the picker is found. */
    private JsonNode findInPickerList(UUID pickerId) throws Exception {
        for (int page = 0; ; page++) {
            JsonNode json = readJson(mockMvc.perform(
                    get("/api/pickers?size=100&sort=lastname,asc&page=" + page).with(admin()))
                    .andExpect(status().isOk()));
            for (JsonNode picker : json.get("content")) {
                if (pickerId.toString().equals(picker.get("id").asText())) {
                    return picker;
                }
            }
            assertThat(json.get("metadata").get("hasNext").asBoolean())
                    .as("picker %s not found in the list", pickerId).isTrue();
        }
    }

    private List<Integer> bucketNumbers(JsonNode picker) {
        List<Integer> numbers = new ArrayList<>();
        picker.get("bucketNumbers").forEach(number -> numbers.add(number.asInt()));
        return numbers;
    }

    private void assertNoPicker(JsonNode bucket) {
        assertThat(bucket.path("picker").isNull() || bucket.path("picker").isMissingNode()).isTrue();
    }

    private JsonNode readJson(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
    }

    private PickerEntity createPicker(String prefix) {
        return pickerRepository.save(PickerEntity.builder()
                .lastname(prefix + "-" + UUID.randomUUID().toString().substring(0, 8))
                .firstname("Jean")
                .build());
    }

    private BucketEntity createBucket() {
        return bucketRepository.save(BucketEntity.builder().number(NEXT_BUCKET_NUMBER.getAndIncrement()).build());
    }

    private static RequestPostProcessor admin() {
        return user("admin").roles("ADMINISTRATEUR");
    }
}
