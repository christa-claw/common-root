// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;
import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.religioustext.app.config.MeiliConfig.MeiliProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The uptime signal: degraded means 503, dev skips Meilisearch, and the probe
 * cache keeps the endpoint from becoming load (docs/api-test-cases.md H1–H4).
 */
class HealthControllerTest {

    private final DataSource   dataSource   = mock(DataSource.class);
    private final RestTemplate restTemplate = mock(RestTemplate.class);
    private final BaseXProperties baseX =
        new BaseXProperties("http://basex:8984/rest", "admin", "admin", "religioustext");
    private final MeiliProperties meili =
        new MeiliProperties("http://meili:7700", "key", "search");

    private HealthController controller(final String anEnv, final Clock aClock) {
        return new HealthController(dataSource, baseX, meili, restTemplate, aClock, anEnv);
    }

    private void mysqlUp() throws Exception {
        final Connection connection = mock(Connection.class);
        when(connection.isValid(anyInt())).thenReturn(true);
        when(dataSource.getConnection()).thenReturn(connection);
    }

    private void baseXUp() {
        when(restTemplate.exchange(contains("basex"), eq(HttpMethod.GET), any(),
                                   eq(String.class)))
            .thenReturn(ResponseEntity.ok(""));
    }

    // H1 — everything up (prod: Meili checked too)
    @Test
    void allSubsystemsUpIsOk() throws Exception {
        mysqlUp();
        baseXUp();
        when(restTemplate.getForEntity(contains("meili"), eq(String.class)))
            .thenReturn(ResponseEntity.ok(""));

        final ResponseEntity<Map<String, Object>> response =
            controller("prod", Clock.systemUTC()).health();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status")).isEqualTo("ok");
        assertThat(subsystems(response))
            .containsEntry("mysql", "ok")
            .containsEntry("basex", "ok")
            .containsEntry("meilisearch", "ok");
    }

    // H2 — the thirteen-day Meilisearch nap becomes a 503 on day one
    @Test
    void meilisearchDownIsDegraded503() throws Exception {
        mysqlUp();
        baseXUp();
        when(restTemplate.getForEntity(contains("meili"), eq(String.class)))
            .thenThrow(new RuntimeException("connection refused"));

        final ResponseEntity<Map<String, Object>> response =
            controller("prod", Clock.systemUTC()).health();

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().get("status")).isEqualTo("degraded");
        assertThat(subsystems(response))
            .containsEntry("meilisearch", "down");
    }

    // dev runs BaseX + MySQL only: Meili is skipped, not cried over
    @Test
    void devSkipsMeilisearch() throws Exception {
        mysqlUp();
        baseXUp();

        final ResponseEntity<Map<String, Object>> response =
            controller("dev", Clock.systemUTC()).health();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(subsystems(response))
            .containsEntry("meilisearch", "skipped");
        verify(restTemplate, times(0)).getForEntity(anyString(), eq(String.class));
    }

    // H4 — inside the cache window there is one probe pass, not one per call;
    // past the window, the same instance re-probes.
    @Test
    void probesAreCached() throws Exception {
        mysqlUp();
        baseXUp();
        final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T12:00:00Z"));
        final HealthController controller = controller("dev", clock);

        controller.health();
        clock.advance(Duration.ofSeconds(5));
        controller.health();
        controller.health();
        verify(dataSource, times(1)).getConnection();

        clock.advance(Duration.ofSeconds(HealthController.CACHE_SECONDS));
        controller.health();
        verify(dataSource, times(2)).getConnection();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> subsystems(final ResponseEntity<Map<String, Object>> aResponse) {
        return (Map<String, String>) aResponse.getBody().get("subsystems");
    }

    /** A clock a test can move by hand. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(final Instant aStart) { this.now = aStart; }
        void advance(final Duration aStep) { now = now.plus(aStep); }
        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(final java.time.ZoneId aZone) { return this; }
    }
}
