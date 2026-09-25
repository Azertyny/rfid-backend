package com.rfidback.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.ZoneId;

import org.junit.jupiter.api.Test;

/** The station time zone check (spec 007, research R4). */
class StationPropertiesTest {

    @Test
    void acceptsEuropeParisDespiteItsHistoricalLocalMeanTime() {
        assertThat(new StationProperties(ZoneId.of("Europe/Paris")).timeZone()).isEqualTo(ZoneId.of("Europe/Paris"));
    }

    @Test
    void acceptsAZoneWithoutTransitions() {
        assertThat(new StationProperties(ZoneId.of("UTC")).timeZone()).isEqualTo(ZoneId.of("UTC"));
    }

    @Test
    void refusesAZoneWithAHalfHourOffset() {
        assertThrows(IllegalArgumentException.class, () -> new StationProperties(ZoneId.of("Asia/Kolkata")));
    }

    @Test
    void refusesAMissingZone() {
        assertThrows(IllegalArgumentException.class, () -> new StationProperties(null));
    }
}
