// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.religioustext.app.config.BaseXConfig.BaseXProperties;
import org.religioustext.app.config.MeiliConfig.MeiliProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code GET /api/v1/health} — the uptime signal (docs/api-spec.md §3.1).
 *
 * <p>Prerequisite before any external consumer: an API is a promise that
 * someone else's site keeps working, and Meilisearch once sat dead for
 * thirteen days with nobody the wiser. This endpoint exists so a dumb pinger
 * (curl in cron, or an external monitor) catches the next nap on day one:
 * {@code status} is {@code ok} only when every checked subsystem is, otherwise
 * {@code degraded} with HTTP 503.
 *
 * <p>No auth, no quota, and the subsystem probes are cached for
 * {@value #CACHE_SECONDS} seconds so the endpoint cannot itself become load.
 *
 * <p>In the dev environment ({@code religioustext.env=dev}) Meilisearch is
 * reported {@code skipped} and excluded from the overall status — local dev
 * runs only BaseX and MySQL by design, and a health endpoint that
 * cries wolf all day in dev would train everyone to ignore it in prod.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    static final int CACHE_SECONDS = 30;

    private final DataSource      dataSource;
    private final BaseXProperties baseX;
    private final MeiliProperties meili;
    private final RestTemplate    restTemplate;
    private final Clock           clock;
    private final boolean         checkMeili;

    private volatile Snapshot cached;

    public HealthController(
             final DataSource aDataSource
            , final BaseXProperties aBaseXProperties
            , final MeiliProperties aMeiliProperties
            , final RestTemplate aRestTemplate
            , final Clock aClock
            , @Value("${religioustext.env:dev}") final String anEnv) {
        this.dataSource   = aDataSource;
        this.baseX        = aBaseXProperties;
        this.meili        = aMeiliProperties;
        this.restTemplate = aRestTemplate;
        this.clock        = aClock;
        this.checkMeili   = !"dev".equalsIgnoreCase(anEnv);
    }

    /**
     * The health snapshot, re-probed at most every {@value #CACHE_SECONDS} seconds.
     *
     * @return 200 with {@code status:"ok"}, or 503 with {@code status:"degraded"}
     */
    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        final Instant now = clock.instant();
        Snapshot snapshot = cached;
        if (snapshot == null || now.isAfter(snapshot.takenAt.plusSeconds(CACHE_SECONDS))) {
            snapshot = probe(now);
            cached = snapshot;
        }
        final Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", snapshot.ok ? "ok" : "degraded");
        body.put("subsystems", snapshot.subsystems);
        body.put("time", now.toString());
        return ResponseEntity.status(snapshot.ok ? 200 : 503).body(body);
    }

    private Snapshot probe(final Instant aNow) {
        final Map<String, String> subsystems = new LinkedHashMap<>();
        subsystems.put("mysql", probeMysql() ? "ok" : "down");
        subsystems.put("basex", probeBaseX() ? "ok" : "down");
        if (checkMeili) {
            subsystems.put("meilisearch", probeMeili() ? "ok" : "down");
        } else {
            subsystems.put("meilisearch", "skipped"); // dev runs BaseX + MySQL only
        }
        final boolean ok = subsystems.values().stream().noneMatch("down"::equals);
        if (!ok) log.warn("Health degraded: {}", subsystems);
        return new Snapshot(aNow, ok, subsystems);
    }

    private boolean probeMysql() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(2);
        } catch (final Exception e) {
            log.debug("MySQL health probe failed", e);
            return false;
        }
    }

    private boolean probeBaseX() {
        try {
            final HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                (baseX.username() + ":" + baseX.password()).getBytes(StandardCharsets.UTF_8)));
            // A trivial query rather than HEAD: BaseX's REST layer answers HEAD with 501.
            final ResponseEntity<String> response = restTemplate.exchange(
                baseX.uri() + "/" + baseX.database() + "?query=1",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (final Exception e) {
            log.debug("BaseX health probe failed", e);
            return false;
        }
    }

    private boolean probeMeili() {
        try {
            final ResponseEntity<String> response = restTemplate.getForEntity(
                meili.url() + "/health", String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (final Exception e) {
            log.debug("Meilisearch health probe failed", e);
            return false;
        }
    }

    /** One probe pass, cached. */
    private record Snapshot(Instant takenAt, boolean ok, Map<String, String> subsystems) { }
}
