package com.rfidback.configuration;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The station time zone: days and hours of the dashboard statistics are computed in it (spec 007, FR-007).
 *
 * <p>The statistics group records by UTC hour and map each one to a local hour, which is exact only when the zone's
 * offsets are whole hours (spec 007, research R4). Only the current offset and the next transitions are checked:
 * historical ones would reject most zones, Europe/Paris used local mean time (+00:09:21) until 1911.
 */
@ConfigurationProperties("app.station")
public record StationProperties(ZoneId timeZone) {

    private static final int CHECKED_TRANSITIONS = 2;

    public StationProperties {
        if (timeZone == null) {
            throw new IllegalArgumentException("app.station.time-zone must be set");
        }
        ZoneRules rules = timeZone.getRules();
        Instant now = Instant.now();
        checkWholeHours(timeZone, rules.getOffset(now));
        ZoneOffsetTransition transition = rules.nextTransition(now);
        for (int i = 0; i < CHECKED_TRANSITIONS && transition != null; i++) {
            checkWholeHours(timeZone, transition.getOffsetBefore());
            checkWholeHours(timeZone, transition.getOffsetAfter());
            transition = rules.nextTransition(transition.getInstant());
        }
    }

    private static void checkWholeHours(ZoneId zone, ZoneOffset offset) {
        if (offset.getTotalSeconds() % 3600 != 0) {
            throw new IllegalArgumentException("app.station.time-zone must have whole-hour offsets: " + zone.getId());
        }
    }
}
