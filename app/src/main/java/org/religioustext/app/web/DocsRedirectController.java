// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Redirects directory-style URLs to their index.html files so that the
 * Vaadin SPA router does not intercept them.
 *
 * <p>The Javadoc lives at {@code /docs/api/index.html} and the application
 * documentation at {@code /docs/api-spec.html}. When a browser visits the
 * directory path without a filename (e.g. {@code /docs/api}), Vaadin's
 * client-side router catches the request before the static-resource
 * handler can serve the directory index, and returns a 404.
 *
 * <p>Spring MVC controllers run before Vaadin's router, so a simple
 * forward on the controller path sends the request back to the resource
 * handler, which then serves the correct file.
 *
 * @author Christa Claw
 * @version 0.8.0-SNAPSHOT
 * @since 0.8.0
 * @see <a href="https://github.com/christa-claw/common-root/issues/30">Issue #30</a>
 */
@Controller
@RequestMapping("/docs")
public class DocsRedirectController {

    /**
     * Forwards {@code GET /docs/api} to the static Javadoc index page.
     *
     * @return the forward view name
     */
    @GetMapping("/api")
    public String apiDocsIndex() {
        return "forward:/docs/api/index.html";
    }
}
