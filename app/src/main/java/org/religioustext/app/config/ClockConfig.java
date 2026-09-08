package org.religioustext.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The application clock, UTC. Injected wherever "now" matters (quota month
 * boundaries, health-probe caching) so tests can pin time instead of waiting
 * for it — a month-boundary test that sleeps until the 1st is not a test.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
