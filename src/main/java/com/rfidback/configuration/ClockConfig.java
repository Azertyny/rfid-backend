package com.rfidback.configuration;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ClockConfig {

    // Injected where the current time drives a rule (registration session expiry), so tests can fix it.
    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }
}
