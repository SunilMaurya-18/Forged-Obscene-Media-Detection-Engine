package com.forgeddetect.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class TimeConfig {

    /** Injected everywhere SLA time is read, so tests can substitute a controllable clock. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
