package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.ReaderEntity;
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

/**
 * HTTP-level checks of GET /api/records/stats on a real database (spec 007, SC-001). Other test classes create records
 * dated "now" in the shared H2 database, so every check here uses a custom period in 2031, where nobody else writes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RecordStatsApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private ReaderEntity reader;
    private ReaderEntity otherReader;
    private PickerEntity diallo;
    private PickerEntity moreau;
    private TagEntity dialloTag;
    private TagEntity moreauTag;
    private TagEntity looseTag;

    @BeforeEach
    void setUp() {
        userRepository.save(appUser("stats-admin", Role.ADMINISTRATEUR));
        userRepository.save(appUser("stats-op", Role.OPERATEUR));
        reader = readerRepository.save(ReaderEntity.builder().name("Reader stats test").build());
        otherReader = readerRepository.save(ReaderEntity.builder().name("Reader stats test 2").build());
        diallo = pickerRepository.save(PickerEntity.builder().firstname("Amadou").lastname("Diallo").build());
        moreau = pickerRepository.save(PickerEntity.builder().firstname("Valérie").lastname("Moreau").build());
        dialloTag = tagInBucket("STATS-DIALLO", 97001, diallo);
        moreauTag = tagInBucket("STATS-MOREAU", 97002, moreau);
        looseTag = tagRepository.save(TagEntity.builder().uid("STATS-LOOSE").build());
    }

    // --- totals (SC-001, FR-009) ---

    @Test
    void totalsPerPickerAndHourAddUpToTheSummary() throws Exception {
        record(reader, diallo, dialloTag, true, "2031-01-10T07:05:00Z");
        record(reader, diallo, dialloTag, false, "2031-01-10T07:40:00Z");
        record(reader, diallo, dialloTag, true, "2031-01-10T09:10:00Z");
        record(reader, moreau, moreauTag, true, "2031-01-10T09:20:00Z");
        record(otherReader, moreau, moreauTag, true, "2031-01-10T15:00:00Z");
        record(reader, null, looseTag, false, "2031-01-10T15:30:00Z");

        JsonNode stats = stats("period=CUSTOM&from=2031-01-10&to=2031-01-10", asOperator());

        assertEquals("2031-01-10", stats.get("from").asText());
        assertEquals("2031-01-10", stats.get("to").asText());
        assertEquals("Europe/Paris", stats.get("timeZone").asText());
        assertCounts(stats.get("summary"), 6, 2);
        JsonNode pickers = stats.get("pickers");
        assertEquals(3, pickers.size());
        assertEquals(diallo.getId().toString(), pickers.get(0).get("pickerId").asText());
        assertEquals("Diallo", pickers.get(0).get("lastname").asText());
        assertCounts(pickers.get(0), 3, 1);
        assertEquals(moreau.getId().toString(), pickers.get(1).get("pickerId").asText());
        assertCounts(pickers.get(1), 2, 0);
        assertThat(pickers.get(2).get("pickerId").isNull()).isTrue();
        assertCounts(pickers.get(2), 1, 1);
        // 07:05Z is 08h in Paris in winter.
        assertCounts(stats.get("hours").get(8), 2, 1);
        assertCounts(stats.get("hours").get(10), 2, 0);
        assertCounts(stats.get("hours").get(16), 2, 1);
        assertInvariants(stats);
    }

    // --- station time zone (FR-007) ---

    @Test
    void halfPastMidnightInParisBelongsToTheParisDay() throws Exception {
        record(reader, diallo, dialloTag, true, "2031-03-11T23:30:00Z");

        assertCounts(stats("period=CUSTOM&from=2031-03-12&to=2031-03-12", asOperator()).get("summary"), 1, 0);
        assertCounts(stats("period=CUSTOM&from=2031-03-11&to=2031-03-11", asOperator()).get("summary"), 0, 0);
    }

    @Test
    void quarterPastSevenInParisIsInTheSevenOClockHourInWinterAndSummer() throws Exception {
        record(reader, diallo, dialloTag, true, "2031-03-12T06:15:00Z");
        record(reader, diallo, dialloTag, true, "2031-07-15T05:15:00Z");

        JsonNode winter = stats("period=CUSTOM&from=2031-03-12&to=2031-03-12", asOperator());
        JsonNode summer = stats("period=CUSTOM&from=2031-07-15&to=2031-07-15", asOperator());

        assertCounts(winter.get("hours").get(7), 1, 0);
        assertCounts(summer.get("hours").get(7), 1, 0);
        assertInvariants(winter);
        assertInvariants(summer);
    }

    // --- reader filter ---

    @Test
    void readerFilterKeepsOnlyThatReadersRecords() throws Exception {
        record(reader, diallo, dialloTag, true, "2031-02-01T08:00:00Z");
        record(reader, moreau, moreauTag, false, "2031-02-01T08:30:00Z");
        record(otherReader, moreau, moreauTag, false, "2031-02-01T09:00:00Z");

        JsonNode stats = stats("period=CUSTOM&from=2031-02-01&to=2031-02-01&readerId=" + otherReader.getId(),
                asOperator());

        assertEquals(otherReader.getId().toString(), stats.get("readerId").asText());
        assertCounts(stats.get("summary"), 1, 1);
        assertEquals(1, stats.get("pickers").size());
        assertEquals(moreau.getId().toString(), stats.get("pickers").get(0).get("pickerId").asText());
        assertInvariants(stats);
    }

    @Test
    void unknownReaderIsNotFound() throws Exception {
        mockMvc.perform(get("/api/records/stats").param("readerId", UUID.randomUUID().toString()).with(asOperator()))
                .andExpect(status().isNotFound());
    }

    // --- conformity and attribution (FR-008, FR-009) ---

    @Test
    void aCorrectedConformityIsCountedWithItsCurrentValue() throws Exception {
        RecordEntity scan = record(reader, diallo, dialloTag, true, "2031-04-01T08:00:00Z");

        mockMvc.perform(patch("/api/records/{recordId}/conformity", scan.getId())
                .with(asOperator()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"isCompliant\":false}"))
                .andExpect(status().isNoContent());

        assertCounts(stats("period=CUSTOM&from=2031-04-01&to=2031-04-01", asOperator()).get("summary"), 1, 1);
    }

    @Test
    void aScanStaysWithThePickerOfItsBucketAtScanTime() throws Exception {
        ReaderEntity scanner = readerRepository.save(ReaderEntity.builder().name("Reader stats scan").build());
        mockMvc.perform(post("/api/tags/scan")
                .header("x-api-token", scanner.getApitoken())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"uid\":\"STATS-DIALLO\",\"isCompliant\":true}"))
                .andExpect(status().is2xxSuccessful());
        RecordEntity scan = recordRepository.findTop10ByReader_NameOrderByCreationDateDesc("Reader stats scan").get(0);
        setCreationDate(scan, "2031-05-01T08:00:00Z");

        BucketEntity bucket = dialloTag.getBucket();
        bucket.setPicker(moreau);
        bucketRepository.saveAndFlush(bucket);

        JsonNode stats = stats("period=CUSTOM&from=2031-05-01&to=2031-05-01", asOperator());

        assertEquals(1, stats.get("pickers").size());
        assertEquals(diallo.getId().toString(), stats.get("pickers").get(0).get("pickerId").asText());
    }

    // --- validation and access ---

    @Test
    void invalidPeriodsAreBadRequests() throws Exception {
        for (String query : new String[] { "period=CUSTOM&from=2031-01-01&to=2031-02-01", "period=CUSTOM",
                "period=CUSTOM&from=2031-01-02&to=2031-01-01", "period=TODAY&from=2031-01-01", "period=FOO",
                "period=CUSTOM&from=2031-13-01&to=2031-13-02", "readerId=not-a-uuid" }) {
            mockMvc.perform(get("/api/records/stats?" + query).with(asOperator()))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void aBadRequestExplainsWhy() throws Exception {
        mockMvc.perform(get("/api/records/stats?period=CUSTOM&from=2031-01-01&to=2031-02-01").with(asOperator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("The period must not exceed 31 days"));
    }

    @Test
    void thirtyOneDaysAreAccepted() throws Exception {
        mockMvc.perform(get("/api/records/stats?period=CUSTOM&from=2031-01-01&to=2031-01-31").with(asOperator()))
                .andExpect(status().isOk());
    }

    @Test
    void anAdministrateurSeesAnEmptyPeriodAsZeros() throws Exception {
        JsonNode stats = stats("period=CUSTOM&from=2031-06-01&to=2031-06-07", asAdmin());

        assertCounts(stats.get("summary"), 0, 0);
        assertEquals(0, stats.get("pickers").size());
        assertEquals(24, stats.get("hours").size());
        assertInvariants(stats);
    }

    @Test
    void todayIsTheDefaultPeriod() throws Exception {
        JsonNode stats = stats("", asOperator());

        assertEquals(stats.get("from").asText(), stats.get("to").asText());
        assertEquals(24, stats.get("hours").size());
    }

    private JsonNode stats(String query, RequestPostProcessor caller) throws Exception {
        String body = mockMvc.perform(get("/api/records/stats?" + query).with(caller))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static void assertCounts(JsonNode counts, long total, long nonCompliant) {
        assertEquals(total, counts.get("total").asLong(), "total");
        assertEquals(nonCompliant, counts.get("nonCompliant").asLong(), "nonCompliant");
    }

    // The contract's invariants: picker rows and hours each add up to the summary.
    private static void assertInvariants(JsonNode stats) {
        long total = stats.get("summary").get("total").asLong();
        long nonCompliant = stats.get("summary").get("nonCompliant").asLong();
        long pickerTotal = 0;
        long pickerNonCompliant = 0;
        for (JsonNode picker : stats.get("pickers")) {
            assertThat(picker.get("total").asLong()).isPositive();
            pickerTotal += picker.get("total").asLong();
            pickerNonCompliant += picker.get("nonCompliant").asLong();
        }
        long hourTotal = 0;
        long hourNonCompliant = 0;
        for (int hour = 0; hour < 24; hour++) {
            JsonNode entry = stats.get("hours").get(hour);
            assertEquals(hour, entry.get("hour").asInt());
            hourTotal += entry.get("total").asLong();
            hourNonCompliant += entry.get("nonCompliant").asLong();
        }
        assertEquals(total, pickerTotal);
        assertEquals(nonCompliant, pickerNonCompliant);
        assertEquals(total, hourTotal);
        assertEquals(nonCompliant, hourNonCompliant);
    }

    private TagEntity tagInBucket(String uid, int bucketNumber, PickerEntity picker) {
        BucketEntity bucket = bucketRepository.save(BucketEntity.builder().number(bucketNumber).picker(picker).build());
        return tagRepository.save(TagEntity.builder().uid(uid).bucket(bucket).build());
    }

    // @CreationTimestamp overwrites a date given on insert, so the date is set afterwards in SQL.
    private RecordEntity record(ReaderEntity from, PickerEntity picker, TagEntity tag, boolean compliant,
            String createdAt) {
        RecordEntity saved = recordRepository.saveAndFlush(RecordEntity.builder()
                .reader(from)
                .picker(picker)
                .tag(tag)
                .compliant(compliant)
                .build());
        setCreationDate(saved, createdAt);
        return saved;
    }

    private void setCreationDate(RecordEntity record, String createdAt) {
        jdbcTemplate.update("update record set creation_date = ? where id = ?", OffsetDateTime.parse(createdAt),
                record.getId());
    }

    private UserEntity appUser(String username, Role role) {
        return UserEntity.builder()
                .username(username)
                .passwordHash(passwordEncoder.encode("irrelevant"))
                .role(role)
                .enabled(true)
                .build();
    }

    private static RequestPostProcessor asAdmin() {
        return user("stats-admin").roles("ADMINISTRATEUR");
    }

    private static RequestPostProcessor asOperator() {
        return user("stats-op").roles("OPERATEUR");
    }
}
