package org.religioustext.app.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * {@code GET /api/docs} — the consumer-facing API documentation: one static
 * page, no key, no quota. It is the page every error body's {@code docs} link
 * points at, and the only place a consumer is told how to follow a token
 * ({@code "text": "web"}) to the edition record, its contents, and a passage —
 * and how to recompute an attestation.
 *
 * <p>Served from the classpath ({@code api-docs.html}) rather than as a Vaadin
 * view so it is plain HTML a developer can read with {@code curl}, and so it
 * stays outside the session machinery like the rest of {@code /api}.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 */
@RestController
public class ApiDocsController {

    private volatile String cached;

    /**
     * The documentation page.
     *
     * @return the HTML
     * @throws IOException if the classpath resource is missing — a packaging error, never a runtime one
     */
    @GetMapping(value = "/api/docs", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> docs() throws IOException {
        String html = cached;
        if (html == null) {
            html = new String(new ClassPathResource("api-docs.html").getInputStream().readAllBytes(),
                              StandardCharsets.UTF_8);
            cached = html;
        }
        return ResponseEntity.ok()
            .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
            .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePublic())
            .body(html);
    }
}
