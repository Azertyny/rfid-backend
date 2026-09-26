package com.rfidback.configuration;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// StationProperties: the station time zone of the dashboard statistics (spec 007) and of the midnight reset of line
// activities (spec 012, service/ActivityDailyReset), which needs scheduling.
@Configuration
@EnableScheduling
@EnableConfigurationProperties(StationProperties.class)
public class ClockConfig {

    // Injected where the current time drives a rule (registration session expiry), so tests can fix it.
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
