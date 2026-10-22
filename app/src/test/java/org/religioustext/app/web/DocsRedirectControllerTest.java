// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 slsgzs-cloud
package org.religioustext.app.web;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link DocsRedirectController}.
 *
 * <p>Standalone MockMvc: no Spring context, so no MySQL/BaseX is needed. The
 * Vaadin router and static-resource handler are not part of this slice, so the
 * tests pin the controller's CONTRACT (what it claims and what it forwards to)
 * and, when the built jar's Javadoc is on the classpath, that the forward
 * target really exists.
 */
class DocsRedirectControllerTest {

    private static final String TARGET = "/docs/api/index.html";

    private final MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new DocsRedirectController())
            // Spring Boot 3 does not match a trailing slash to a slash-less mapping;
            // standalone MockMvc still does by default, which would hide a missing mapping.
            .setUseTrailingSlashPatternMatch(false)
            .build();

    @Test
    void apiWithoutIndexForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/docs/api"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl(TARGET));
    }

    @Test
    void forwardsRatherThanRedirects() throws Exception {
        // A forward keeps the hand-typed URL in the address bar and costs no round trip.
        mockMvc.perform(get("/docs/api"))
            .andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResponse().getRedirectedUrl()).isNull());
    }

    @Test
    void doesNotClaimTheFilesItForwardsTo() throws Exception {
        // /docs/api/index.html must keep being served directly by the resource handler;
        // if the controller matched it, the forward would loop onto itself.
        mockMvc.perform(get(TARGET)).andExpect(status().isNotFound());
    }

    @Test
    void doesNotClaimOtherDocsPaths() throws Exception {
        mockMvc.perform(get("/docs/api-postman-collection.json")).andExpect(status().isNotFound());
        mockMvc.perform(get("/docs")).andExpect(status().isNotFound());
    }

    @Test
    void trailingSlashForwardsToo() throws Exception {
        // Without its own mapping /docs/api/ falls through to Vaadin's router and
        // lands on the login page.
        mockMvc.perform(get("/docs/api/"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl(TARGET));
    }

    @Test
    void onlyGetIsMapped() throws Exception {
        mockMvc.perform(post("/docs/api")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void forwardTargetExistsInTheBuiltJavadoc() {
        // Javadoc is generated at `package` (see pom.xml), so it is absent from a plain
        // `mvn test`; assert against it only when it is there.
        final var resource = getClass().getResource("/META-INF/resources" + TARGET);
        Assumptions.assumeTrue(resource != null, "Javadoc not generated in this build");
        assertThat(resource).isNotNull();
    }
}
