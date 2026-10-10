// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.religioustext.app.web.PrintBuilderService.Job;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Create print package" button of the order page ({@code edition-designer.html}).
 *
 * <p>Whether a build can run here, and whether the caller may start one, are two different
 * questions, and the page asks the first of them without logging in
 * ({@link #capabilities}): it only answers {@code enabled} and {@code admin}, nothing secret. Every
 * other route needs an administrator. The routes are open to the security filter chain
 * ({@code SecurityConfig} permits {@code /designer/package/**} and exempts it from the Vaadin CSRF
 * token) because they do their own check, so an anonymous caller gets a 403 and a JSON reason
 * instead of a redirect to a login page. In place of the CSRF token a POST must carry
 * {@code X-Requested-With: designer} and a JSON body, which a form on another site cannot send and a
 * script on another site cannot send without a cross-origin preflight this app never answers.
 */
@RestController
@RequestMapping("/designer/package")
public class PrintPackageController {

    static final String ADMIN = "ROLE_ADMIN";

    private final PrintBuilderService builder;

    public PrintPackageController(final PrintBuilderService aBuilder) {
        this.builder = aBuilder;
    }

    private static boolean isAdmin(final Authentication anAuth) {
        return anAuth != null && anAuth.isAuthenticated()
            && anAuth.getAuthorities().stream().anyMatch(a -> ADMIN.equals(a.getAuthority()));
    }

    private static ResponseEntity<Map<String, Object>> reply(final HttpStatus aStatus, final String aMessage) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", aMessage);
        return ResponseEntity.status(aStatus).cacheControl(CacheControl.noStore()).body(m);
    }

    /** Can this server build, and may this caller ask it to. */
    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> capabilities(final Authentication anAuth) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", builder.enabled());
        m.put("admin", isAdmin(anAuth));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(m);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> start(
            final Authentication anAuth,
            @RequestHeader(value = "X-Requested-With", required = false) final String aMarker,
            @RequestBody final PrintPackageRequest aRequest) {
        if (!isAdmin(anAuth)) return reply(HttpStatus.FORBIDDEN, "Only an administrator can create a package.");
        if (!"designer".equals(aMarker)) return reply(HttpStatus.FORBIDDEN, "Not sent from the order page.");
        if (!builder.enabled()) {
            return reply(HttpStatus.NOT_FOUND,
                "This server has no print builder. Run the app on the machine that has the project.");
        }
        final PrintPackageRequest.Valid valid;
        try {
            valid = aRequest.validate();
        } catch (final IllegalArgumentException e) {
            return reply(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        try {
            final Job job = builder.submit(valid);
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", job.id());
            return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(m);
        } catch (final PrintBuilderService.BusyException e) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", e.getMessage());
            m.put("id", e.runningId());          // the page can follow the build that is running
            return ResponseEntity.status(HttpStatus.CONFLICT).cacheControl(CacheControl.noStore()).body(m);
        }
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> status(final Authentication anAuth, @PathVariable("id") final String anId) {
        if (!isAdmin(anAuth)) return reply(HttpStatus.FORBIDDEN, "Only an administrator can see a build.");
        final Job job = builder.job(anId);
        if (job == null) return reply(HttpStatus.NOT_FOUND, "No such build.");
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", job.id());
        m.put("status", job.status().name().toLowerCase(java.util.Locale.ROOT));
        m.put("seconds", job.elapsedSeconds());
        final List<String> tail = job.tail(8);
        m.put("log", tail);
        if (job.error() != null) m.put("error", job.error());
        if (job.result() != null) {
            final Map<String, Object> r = new LinkedHashMap<>();
            r.put("pages", job.result().path("pages").asInt());
            r.put("spineMm", job.result().path("spine_mm").asInt());
            r.put("bytes", job.result().path("bytes").asLong());
            r.put("sha256", job.result().path("sha256").asText());
            r.put("rightsStatus", job.result().path("rights_status").asText());
            r.put("filename", job.filename());
            m.put("result", r);
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(m);
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<?> download(final Authentication anAuth, @PathVariable("id") final String anId) {
        if (!isAdmin(anAuth)) return reply(HttpStatus.FORBIDDEN, "Only an administrator can download a package.");
        final Job job = builder.job(anId);
        if (job == null || job.zip() == null || job.status() != PrintBuilderService.Status.DONE) {
            return reply(HttpStatus.NOT_FOUND, "That package is not ready.");
        }
        final Resource file = new FileSystemResource(job.zip());
        if (!file.exists()) return reply(HttpStatus.NOT_FOUND, "That package has been removed.");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(job.filename(), StandardCharsets.UTF_8).build().toString())
            .contentType(MediaType.parseMediaType("application/zip"))
            .cacheControl(CacheControl.noStore())
            .body(file);
    }
}
