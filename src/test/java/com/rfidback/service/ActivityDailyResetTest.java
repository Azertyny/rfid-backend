package com.rfidback.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.rfidback.entity.ActivityChangeAuthorType;
import com.rfidback.entity.ActivityEntity;
import com.rfidback.entity.LineActivityChangeEntity;
import com.rfidback.entity.ReaderEntity;
import com.rfidback.repository.ActivityRepository;
import com.rfidback.repository.LineActivityChangeRepository;
import com.rfidback.repository.ReaderRepository;

/**
 * The midnight reset and its startup catch-up (spec 012, FR-008a). Deliberately not @Transactional: the reset must
 * open its own transaction, which a test transaction would hide (analysis C2). The data is removed afterwards.
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityDailyResetTest {

    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    @Autowired
    private ActivityDailyReset activityDailyReset;

    @Autowired
    private ReaderRepository readerRepository;

    @Autowired
    private ActivityRepository activityRepository;

    @Autowired
    private LineActivityChangeRepository lineActivityChangeRepository;

    private ActivityEntity fraise;
    private ReaderEntity staleLine;
    private ReaderEntity todaysLine;

    @BeforeEach
    void setUp() {
        fraise = activityRepository.save(ActivityEntity.builder().name("Reset fraise " + UUID.randomUUID()).build());
        OffsetDateTime today = startOfToday();
        staleLine = saveLine("Reset stale", today.minusHours(1));
        todaysLine = saveLine("Reset today", OffsetDateTime.now());
    }

    @AfterEach
    void tearDown() {
        for (ReaderEntity line : List.of(staleLine, todaysLine)) {
            lineActivityChangeRepository.deleteAll(lineActivityChangeRepository.findAllByReaderOrderByChangedAtAsc(line));
            readerRepository.deleteById(line.getId());
        }
        activityRepository.deleteById(fraise.getId());
    }

    @Test
    void resetAtMidnight_clearsYesterdaysChoiceOnly_andLogsItAtMidnight() {
        activityDailyReset.resetAtMidnight();

        assertStaleLineReset();
        assertThat(readerRepository.findWithCurrentActivityById(todaysLine.getId()).orElseThrow()
                .getCurrentActivity()).isNotNull();
        assertThat(lineActivityChangeRepository.findAllByReaderOrderByChangedAtAsc(todaysLine)).isEmpty();

        activityDailyReset.resetAtMidnight();
        assertThat(lineActivityChangeRepository.findAllByReaderOrderByChangedAtAsc(staleLine)).hasSize(1);
    }

    @Test
    void catchUpOnStartup_runsInATransaction() {
        activityDailyReset.catchUpOnStartup();

        assertStaleLineReset();
    }

    private void assertStaleLineReset() {
        assertThat(readerRepository.findWithCurrentActivityById(staleLine.getId()).orElseThrow()
                .getCurrentActivity()).isNull();
        List<LineActivityChangeEntity> changes = lineActivityChangeRepository.findAllByReaderOrderByChangedAtAsc(
                staleLine);
        assertThat(changes).hasSize(1);
        assertThat(changes.get(0).getAuthorType()).isEqualTo(ActivityChangeAuthorType.SYSTEM);
        assertThat(changes.get(0).getChangedAt().toInstant()).isEqualTo(startOfToday().toInstant());
    }

    private ReaderEntity saveLine(String name, OffsetDateTime setAt) {
        ReaderEntity line = ReaderEntity.builder().name(name + " " + UUID.randomUUID()).build();
        line.setCurrentActivity(fraise);
        line.setCurrentActivitySetAt(setAt);
        return readerRepository.save(line);
    }

    private static OffsetDateTime startOfToday() {
        return LocalDate.now(PARIS).atStartOfDay(PARIS).toOffsetDateTime();
    }
}
