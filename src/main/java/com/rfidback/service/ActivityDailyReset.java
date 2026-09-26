package com.rfidback.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Every line starts the day without activity: at midnight, station time, and at startup in case the server was down
 * then (spec 012, FR-008a, research R3). Both entry points call the transactional
 * {@link LineActivityService#resetStaleActivities()} in another bean; never call one method of this class from the
 * other, the transaction the row locks need would be skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActivityDailyReset {

    private final LineActivityService lineActivityService;

    @Scheduled(cron = "0 0 0 * * *", zone = "${app.station.time-zone}")
    public void resetAtMidnight() {
        report(lineActivityService.resetStaleActivities());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        report(lineActivityService.resetStaleActivities());
    }

    private static void report(int reset) {
        if (reset > 0) {
            log.info("Current activity reset on {} line(s) at the start of the day", reset);
        }
    }
}
