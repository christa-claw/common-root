// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.spring.security.AuthenticationContext;
import org.religioustext.app.service.UserService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The build identity every page shows: the version pill ({@code v0.3.7-SNAPSHOT}) plus the
 * {@link EnvBadge} environment chip. One Spring bean holds the {@code religioustext.version} /
 * {@code religioustext.env} values (both Maven-filtered at build time) and every view asks it for
 * a fresh {@link #badge()} — Vaadin components cannot be shared between UIs, so each call builds
 * a new pair, but from a single implementation so the pages cannot drift.
 *
 * @author Christa Claw
 * @version 0.3.7-SNAPSHOT
 * @since 0.3.7
 */
@Component
public class BuildInfo {

    private final String version;
    private final String env;
    private final AuthenticationContext authContext;
    private final UserService userService;

    public BuildInfo(@Value("${religioustext.version:dev}") final String aVersion,
                     @Value("${religioustext.env:dev}") final String anEnvTag,
                     final AuthenticationContext anAuthContext,
                     final UserService aUserService) {
        this.version = aVersion;
        this.env = anEnvTag;
        this.authContext = anAuthContext;
        this.userService = aUserService;
    }

    /** The raw version string (no {@code v} prefix). */
    public String version() { return version; }

    /** The raw environment tag ({@code dev}, {@code prod}, …). */
    public String env() { return env; }

    /**
     * A fresh version-pill + environment-chip pair.
     *
     * @return a new {@link Div} (inline-flex) holding the pill and the chip
     */
    /**
     * The badge pinned to the top-right corner of the viewport — stays put however far the page
     * scrolls, so every screenshot carries the build identity. For pages without a permanent
     * header (Profile, Preferences, the auth pages, the admin tools). Gets an opaque backdrop so
     * it stays legible over scrolled content.
     *
     * @return a new fixed-position badge {@link Div}
     */
    public Div pinned() {
        final Div stamp = badge();
        // Signed-in user's name beside the stamp (a Profile link) — screenshots then carry
        // WHO was signed in alongside WHICH build. Absent for anonymous visitors.
        if (authContext.isAuthenticated()) {
            final String label = userService.displayLabel(authContext.getPrincipalName().orElse(""));
            if (label != null && !label.isBlank()) {
                final Anchor who = new Anchor("/profile", label);
                who.getStyle()
                    .set("font-size", "12px")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("text-decoration", "none")
                    .set("white-space", "nowrap");
                stamp.add(who);
            }
        }
        stamp.getStyle()
            .set("position", "fixed")
            .set("top", "10px")
            .set("right", "14px")
            .set("z-index", "1000")
            .set("background", "var(--lumo-base-color)")
            .set("padding", "4px 6px")
            .set("border-radius", "6px");
        return stamp;
    }

    public Div badge() {
        final Span pill = new Span("v" + version);
        pill.getStyle()
            .set("font-size", "11px")
            .set("color", "var(--lumo-tertiary-text-color)")
            .set("padding", "2px 6px")
            .set("border", "1px solid var(--lumo-contrast-20pct)")
            .set("border-radius", "3px")
            .set("font-family", "monospace")
            .set("white-space", "nowrap");
        final Div wrap = new Div(pill, EnvBadge.of(env));
        wrap.getStyle()
            .set("display", "inline-flex")
            .set("align-items", "center")
            .set("gap", "6px")
            .set("flex-shrink", "0");
        return wrap;
    }
}
