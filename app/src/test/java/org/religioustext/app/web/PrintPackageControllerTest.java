// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.junit.jupiter.api.Test;
import org.religioustext.app.web.PrintBuilderService.Job;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PrintPackageControllerTest {

    private static final Authentication ADMIN = new UsernamePasswordAuthenticationToken(
        "admin", "x", List.of(new SimpleGrantedAuthority("ROLE_CONSUMER"), new SimpleGrantedAuthority("ROLE_ADMIN")));
    private static final Authentication READER = new UsernamePasswordAuthenticationToken(
        "reader", "x", List.of(new SimpleGrantedAuthority("ROLE_CONSUMER")));
    private static final Authentication ANONYMOUS = new AnonymousAuthenticationToken(
        "key", "anonymous", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

    private static PrintPackageRequest web() {
        return new PrintPackageRequest("WEB", "chronological", "66", "standard", null, null, null, null,
            null, null, null, null);
    }

    private final PrintBuilderService builder = mock(PrintBuilderService.class);
    private final PrintPackageController controller = new PrintPackageController(builder);

    @Test
    void thePageCanAskWhetherItMayBuildWithoutLoggingIn() {
        when(builder.enabled()).thenReturn(true);
        assertEquals(Map.of("enabled", true, "admin", false), controller.capabilities(ANONYMOUS).getBody());
        assertEquals(Map.of("enabled", true, "admin", false), controller.capabilities(READER).getBody());
        assertEquals(Map.of("enabled", true, "admin", true), controller.capabilities(ADMIN).getBody());
        assertEquals(Map.of("enabled", true, "admin", false), controller.capabilities(null).getBody());
        when(builder.enabled()).thenReturn(false);
        assertEquals(Map.of("enabled", false, "admin", true), controller.capabilities(ADMIN).getBody());
    }

    @Test
    void onlyAnAdministratorStartsABuild() {
        when(builder.enabled()).thenReturn(true);
        for (final Authentication who : new Authentication[] {ANONYMOUS, READER, null}) {
            assertEquals(HttpStatus.FORBIDDEN, controller.start(who, "designer", web()).getStatusCode());
        }
        verify(builder, never()).submit(any());
    }

    @Test
    void aRequestNotSentFromTheOrderPageIsRefused() {
        when(builder.enabled()).thenReturn(true);
        assertEquals(HttpStatus.FORBIDDEN, controller.start(ADMIN, null, web()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.start(ADMIN, "fetch", web()).getStatusCode());
        verify(builder, never()).submit(any());
    }

    @Test
    void aServerWithNoBuilderSaysSo() {
        when(builder.enabled()).thenReturn(false);
        final ResponseEntity<Map<String, Object>> r = controller.start(ADMIN, "designer", web());
        assertEquals(HttpStatus.NOT_FOUND, r.getStatusCode());
        assertTrue(r.getBody().get("error").toString().contains("no print builder"));
    }

    @Test
    void anInvalidRequestIsRefusedWithTheReason() {
        when(builder.enabled()).thenReturn(true);
        final ResponseEntity<Map<String, Object>> r = controller.start(ADMIN, "designer",
            new PrintPackageRequest("WEB", "chronological", "66", "standard", null, null, null, null, null,
                null, null, "ORIGINAL"));
        assertEquals(HttpStatus.BAD_REQUEST, r.getStatusCode());
        assertTrue(r.getBody().get("error").toString().contains("verse-numbered"));
        verify(builder, never()).submit(any());
    }

    @Test
    void aValidRequestIsAcceptedWithTheBuildsId() {
        when(builder.enabled()).thenReturn(true);
        final Job job = new Job("x.zip");
        when(builder.submit(any())).thenReturn(job);
        final ResponseEntity<Map<String, Object>> r = controller.start(ADMIN, "designer", web());
        assertEquals(HttpStatus.ACCEPTED, r.getStatusCode());
        assertEquals(job.id(), r.getBody().get("id"));
    }

    @Test
    void aBuildAlreadyRunningIsReportedWithItsId() {
        when(builder.enabled()).thenReturn(true);
        when(builder.submit(any())).thenThrow(new PrintBuilderService.BusyException("running-1"));
        final ResponseEntity<Map<String, Object>> r = controller.start(ADMIN, "designer", web());
        assertEquals(HttpStatus.CONFLICT, r.getStatusCode());
        assertEquals("running-1", r.getBody().get("id"));
    }

    @Test
    void aBuildsProgressAndPackageAreForAdministratorsOnly() {
        final Job job = new Job("x.zip");
        when(builder.job(job.id())).thenReturn(job);
        assertEquals(HttpStatus.FORBIDDEN, controller.status(READER, job.id()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.status(ANONYMOUS, job.id()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.download(READER, job.id()).getStatusCode());
        assertEquals(HttpStatus.FORBIDDEN, controller.download(null, job.id()).getStatusCode());
    }

    @Test
    void aRunningBuildReportsItsStatusAndAnUnknownOneIsNotFound() {
        final Job job = new Job("x.zip");
        when(builder.job(job.id())).thenReturn(job);
        final ResponseEntity<Map<String, Object>> r = controller.status(ADMIN, job.id());
        assertEquals(HttpStatus.OK, r.getStatusCode());
        assertEquals("running", r.getBody().get("status"));
        assertEquals(HttpStatus.NOT_FOUND, controller.status(ADMIN, "nope").getStatusCode());
    }

    @Test
    void aPackageThatIsNotReadyCannotBeDownloaded() {
        final Job job = new Job("x.zip");
        when(builder.job(job.id())).thenReturn(job);
        assertEquals(HttpStatus.NOT_FOUND, controller.download(ADMIN, job.id()).getStatusCode());
        assertEquals(HttpStatus.NOT_FOUND, controller.download(ADMIN, "nope").getStatusCode());
    }
}
