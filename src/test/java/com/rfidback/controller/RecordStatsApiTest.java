package com.rfidback.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.BucketEntity;
import com.rfidback.entity.PickerEntity;
import com.rfidback.entity.PickerWorkDayEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.entity.RecordEntity;
import com.rfidback.entity.Role;
import com.rfidback.entity.TagEntity;
import com.rfidback.entity.UserEntity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.BucketRepository;
import com.rfidback.repository.PickerRepository;
import com.rfidback.repository.PickerWorkDayRepository;
import com.rfidback.repository.ReaderRepository;
import com.rfidback.repository.RecordRepository;
import com.rfidback.repository.TagRepository;
import com.rfidback.repository.UserRepository;
import com.rfidback.support.ReferenceTagUids;

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

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private PickerWorkDayRepository workDayRepository;

    private UserEntity admin;

    private ReaderEntity reader;
    private ReaderEntity otherReader;
    private PickerEntity diallo;
    private PickerEntity moreau;
    private TagEntity dialloTag;
    private TagEntity moreauTag;
    private TagEntity looseTag;

    @BeforeEach
    void setUp() {
        admin = userRepository.save(appUser("stats-admin", Role.ADMINISTRATEUR));
        userRepository.save(appUser("stats-op", Role.OPERATEUR));
        reader = readerRepository.save(ReaderEntity.builder().name("Reader stats test").build());
        otherReader = readerRepository.save(ReaderEntity.builder().name("Reader stats test 2").build());
        diallo = pickerRepository.save(PickerEntity.builder().firstname("Amadou").lastname("Diallo").build());
        moreau = pickerRepository.save(PickerEntity.builder().firstname("Valérie").lastname("Moreau").build());
        dialloTag = tagInBucket(ReferenceTagUids.nextInList(), 97001, diallo);
        moreauTag = tagInBucket(ReferenceTagUids.nextInList(), 97002, moreau);
        looseTag = tagRepository.save(TagEntity.builder().uid(ReferenceTagUids.nextInList()).build());
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

    // --- deleted reader (spec 002, FR-008) ---

    @Test
    void deletedReadersRecordsStillCountAndStayFilterable() throws Exception {
        record(reader, diallo, dialloTag, true, "2031-04-02T08:00:00Z");
        record(otherReader, moreau, moreauTag, false, "2031-04-02T09:00:00Z");
        String day = "period=CUSTOM&from=2031-04-02&to=2031-04-02";
        JsonNode before = stats(day, asOperator());

        mockMvc.perform(patch("/api/readers/" + otherReader.getId()).with(asAdmin()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/readers/" + otherReader.getId()).with(asAdmin()).with(csrf()))
                .andExpect(status().isNoContent());

        JsonNode after = stats(day, asOperator());
        assertEquals(before.get("summary"), after.get("summary"));
        assertCounts(after.get("summary"), 2, 1);
        assertCounts(stats(day + "&readerId=" + otherReader.getId(), asOperator()).get("summary"), 1, 1);
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
                .content("{\"uid\":\"%s\",\"isCompliant\":true}".formatted(dialloTag.getUid())))
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

    // --- activity columns and work hours (spec 014) ---

    @Test
    void pickerRowsCarryTheirHoursAndCratesPerActivity() throws Exception {
        ActivityEntity fraise = activityRepository.save(ActivityEntity.builder().name("Fraise stats 014").build());
        ActivityEntity framboise = activityRepository.save(
                ActivityEntity.builder().name("Framboise stats 014").build());
        PickerEntity bernard = pickerRepository.save(PickerEntity.builder().firstname("Lucie").lastname("Bernard")
                .build());
        record(reader, diallo, dialloTag, true, "2031-08-04T07:00:00Z", fraise);
        record(reader, diallo, dialloTag, false, "2031-08-04T08:00:00Z", fraise);
        record(otherReader, diallo, dialloTag, true, "2031-08-05T08:00:00Z", framboise);
        record(reader, moreau, moreauTag, true, "2031-08-05T09:00:00Z", null);
        record(reader, null, looseTag, true, "2031-08-05T10:00:00Z", fraise);
        hours(diallo, "2031-08-04", 240);
        hours(diallo, "2031-08-05", 480);
        hours(bernard, "2031-08-05", 450);
        hours(diallo, "2031-08-06", 600); // outside the period

        JsonNode stats = stats("period=CUSTOM&from=2031-08-04&to=2031-08-05", asOperator());

        JsonNode columns = stats.get("activities");
        assertEquals(3, columns.size());
        assertEquals(fraise.getId().toString(), columns.get(0).get("activityId").asText());
        assertEquals(framboise.getId().toString(), columns.get(1).get("activityId").asText());
        assertThat(columns.get(2).get("activityId").isNull()).isTrue();
        assertEquals(19.5, stats.get("workHours").asDouble());
        assertThat(stats.get("includesToday").asBoolean()).isFalse();

        JsonNode pickers = stats.get("pickers");
        assertEquals(List.of("Bernard", "Diallo", "Moreau"), List.of(pickers.get(0).get("lastname").asText(),
                pickers.get(1).get("lastname").asText(), pickers.get(2).get("lastname").asText()));
        assertCounts(pickers.get(0), 0, 0);
        assertEquals(7.5, pickers.get(0).get("workHours").asDouble());
        assertEquals(0, pickers.get(0).get("activities").size());
        assertEquals(12.0, pickers.get(1).get("workHours").asDouble());
        assertEquals(2, pickers.get(1).get("activities").get(0).get("total").asLong());
        assertEquals(1, pickers.get(1).get("activities").get(1).get("total").asLong());
        assertThat(pickers.get(2).get("workHours").isNull()).isTrue();
        assertThat(pickers.get(2).get("activities").get(0).get("activityId").isNull()).isTrue();
        assertThat(pickers.get(3).get("pickerId").isNull()).isTrue();
        assertThat(pickers.get(3).get("workHours").isNull()).isTrue();
        assertInvariants(stats);

        // The same figures for an Opérateur and an Administrateur (spec 014, FR-006).
        assertEquals(stats, stats("period=CUSTOM&from=2031-08-04&to=2031-08-05", asAdmin()));
    }

    @Test
    void aReaderGivesNoHoursAndItsOwnActivityColumns() throws Exception {
        ActivityEntity fraise = activityRepository.save(ActivityEntity.builder().name("Fraise stats 014 b").build());
        ActivityEntity framboise = activityRepository.save(
                ActivityEntity.builder().name("Framboise stats 014 b").build());
        record(reader, diallo, dialloTag, true, "2031-08-11T07:00:00Z", fraise);
        record(otherReader, diallo, dialloTag, true, "2031-08-11T08:00:00Z", framboise);
        hours(diallo, "2031-08-11", 480);
        hours(moreau, "2031-08-11", 480);

        JsonNode stats = stats("period=CUSTOM&from=2031-08-11&to=2031-08-11&readerId=" + reader.getId(),
                asOperator());

        assertThat(stats.get("workHours").isNull()).isTrue();
        assertEquals(1, stats.get("activities").size());
        assertEquals(fraise.getId().toString(), stats.get("activities").get(0).get("activityId").asText());
        // Moreau has hours but no record on this reader: no row for hours alone.
        assertEquals(1, stats.get("pickers").size());
        assertThat(stats.get("pickers").get(0).get("workHours").isNull()).isTrue();
        assertInvariants(stats);
    }

    @Test
    void todayIsFlagged() throws Exception {
        assertThat(stats("", asOperator()).get("includesToday").asBoolean()).isTrue();
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
        double pickerHours = 0;
        long[] columnTotals = new long[stats.get("activities").size()];
        for (JsonNode picker : stats.get("pickers")) {
            // A row without record only exists for a picker with hours (spec 014, FR-009).
            if (picker.get("total").asLong() == 0) {
                assertThat(picker.get("workHours").isNull()).isFalse();
            }
            pickerTotal += picker.get("total").asLong();
            pickerNonCompliant += picker.get("nonCompliant").asLong();
            long activityTotal = 0;
            for (JsonNode count : picker.get("activities")) {
                activityTotal += count.get("total").asLong();
                columnTotals[columnIndex(stats, count.get("activityId"))] += count.get("total").asLong();
            }
            assertEquals(picker.get("total").asLong(), activityTotal, "activities add up to the picker's total");
            if (picker.hasNonNull("workHours")) {
                pickerHours += picker.get("workHours").asDouble();
            }
        }
        if (stats.hasNonNull("workHours")) {
            assertEquals(stats.get("workHours").asDouble(), pickerHours, 1e-9);
        }
        for (long columnTotal : columnTotals) {
            assertThat(columnTotal).isPositive();
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

    private static int columnIndex(JsonNode stats, JsonNode activityId) {
        JsonNode columns = stats.get("activities");
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).get("activityId").equals(activityId)) {
                return i;
            }
        }
        throw new AssertionError("No column for activity " + activityId);
    }

    private void hours(PickerEntity picker, String day, int minutes) {
        workDayRepository.save(PickerWorkDayEntity.builder()
                .picker(picker)
                .workDate(LocalDate.parse(day))
                .minutes(minutes)
                .updatedAt(OffsetDateTime.parse("2031-01-01T06:00:00Z"))
                .updatedBy(admin)
                .build());
    }

    private TagEntity tagInBucket(String uid, int bucketNumber, PickerEntity picker) {
        BucketEntity bucket = bucketRepository.save(BucketEntity.builder().number(bucketNumber).picker(picker).build());
        return tagRepository.save(TagEntity.builder().uid(uid).bucket(bucket).build());
    }

    // @CreationTimestamp overwrites a date given on insert, so the date is set afterwards in SQL.
    private RecordEntity record(ReaderEntity from, PickerEntity picker, TagEntity tag, boolean compliant,
            String createdAt) {
        return record(from, picker, tag, compliant, createdAt, null);
    }

    private RecordEntity record(ReaderEntity from, PickerEntity picker, TagEntity tag, boolean compliant,
            String createdAt, ActivityEntity activity) {
        RecordEntity saved = recordRepository.saveAndFlush(RecordEntity.builder()
                .reader(from)
                .picker(picker)
                .tag(tag)
                .compliant(compliant)
                .activity(activity)
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
