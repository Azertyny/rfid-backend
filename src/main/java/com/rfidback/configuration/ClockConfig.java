package com.rfidback.configuration;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// StationProperties: the station time zone of the dashboard statistics (spec 007).
@Configuration
@EnableConfigurationProperties(StationProperties.class)
public class ClockConfig {

    // Injected where the current time drives a rule (registration session expiry), so tests can fix it.
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
