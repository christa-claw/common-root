// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for {@link DocsRedirectController}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DocsRedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void api_without_index_redirects_to_index_html() throws Exception {
        mockMvc.perform(get("/docs/api"))
            .andExpect(status().isOk())
            .andExpect(forwardedUrl("/docs/api/index.html"));
    }
}
