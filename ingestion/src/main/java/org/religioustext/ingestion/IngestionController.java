// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.ingestion;

import org.religioustext.ingestion.config.BibleSourcesConfig;
import org.religioustext.ingestion.model.IngestionRequest;
import org.religioustext.ingestion.LocalIngestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * REST endpoints for triggering ingestion jobs.
 *
 * curl http://localhost:8091/ingest/status
 * curl -X POST http://localhost:8091/ingest/NIV
 * curl -X POST http://localhost:8091/ingest/all
 */
@RestController
@RequestMapping("/ingest")
public class IngestionController {

    private static final Logger log = LoggerFactory.getLogger(IngestionController.class);

    private final IngestionService      ingestionService;
    private final LocalIngestionService localIngestionService;
    private final BibleSourcesConfig    sourcesConfig;
    private final ExecutorService       executor =
        Executors.newSingleThreadExecutor();

    public IngestionController(
             final IngestionService      anIngestionService
            , final LocalIngestionService aLocalIngestionService
            , final BibleSourcesConfig    aSourcesConfig) {
        this.ingestionService      = anIngestionService;
        this.localIngestionService = aLocalIngestionService;
        this.sourcesConfig         = aSourcesConfig;
    }

    @GetMapping("/status")
    public ResponseEntity<List<String>> status() {
        final List<String> configured = sourcesConfig.toIngestionRequests()
            .stream()
            .map(r -> r.getAbbreviation() + " — " + r.getTranslation()
                + " (" + r.getBcp47Language() + ")")
            .toList();
        return ResponseEntity.ok(configured);
    }

    @PostMapping("/{abbreviation}")
    public ResponseEntity<String> ingestOne(
            @PathVariable("abbreviation") final String anAbbreviation) {

        final IngestionRequest request =
            sourcesConfig.findByAbbreviation(anAbbreviation);

        if (request == null) {
            return ResponseEntity.badRequest()
                .body("Unknown translation: " + anAbbreviation
                    + ". Check /ingest/status for configured translations.");
        }

        log.info("Ingestion triggered for: {}", anAbbreviation);
        try {
            final String documentId = request.getSourceUrl().startsWith("local:")
                ? localIngestionService.ingest(request)
                : ingestionService.ingest(request);
            return ResponseEntity.ok("Ingestion complete. Document ID: " + documentId);
        } catch (final Exception e) {
            log.error("Ingestion failed for {}", anAbbreviation, e);
            return ResponseEntity.internalServerError()
                .body("Ingestion failed: " + e.getMessage());
        }
    }

    @PostMapping("/all")
    public ResponseEntity<String> ingestAll() {
        final List<IngestionRequest> requests = sourcesConfig.toIngestionRequests();
        if (requests.isEmpty()) {
            return ResponseEntity.badRequest()
                .body("No translations configured in bible-sources.yml");
        }

        CompletableFuture.runAsync(() -> {
            for (final IngestionRequest request : requests) {
                try {
                    log.info("Background: starting {}", request.getAbbreviation());
                    final String docId = ingestionService.ingest(request);
                    log.info("Background: completed {} -> {}"
                        , request.getAbbreviation(), docId);
                } catch (final Exception e) {
                    log.error("Background: failed {} — {}"
                        , request.getAbbreviation(), e.getMessage());
                }
            }
            log.info("Background ingestion complete");
        }, executor);

        final String list = requests.stream()
            .map(IngestionRequest::getAbbreviation)
            .reduce((a, b) -> a + ", " + b)
            .orElse("");

        return ResponseEntity.accepted()
            .body("Background ingestion started for: " + list);
    }
}
