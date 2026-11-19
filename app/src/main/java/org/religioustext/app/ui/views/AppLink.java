// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;

/**
 * The links that lead to {@link AppDownloadView}: a quiet one for a page
 * header, and a button for a hero beside "Open the Reader".
 *
 * <p>Kept apart from the pages that carry them so {@link AboutView}, which is
 * over the class-size ceiling and may not grow, gains the links by changing a
 * line rather than adding a block.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
final class AppLink {

    private static final String HREF = "/app";

    private AppLink() { }

    /**
     * @param aLabel the translated label, {@code app.nav}
     * @return a header link to the app page
     */
    static Anchor nav(final String aLabel) {
        final Anchor a = new Anchor(HREF, aLabel);
        a.getElement().setAttribute("data-umami-event", "app-page");
        a.getElement().setAttribute("data-umami-event-from", "header");
        a.getStyle()
            .set("font-size", "14px").set("color", "var(--lumo-primary-color)")
            .set("font-weight", "600").set("text-decoration", "none")
            .set("margin-right", "16px").set("white-space", "nowrap");
        return a;
    }

    /**
     * @param aLabel the translated label, {@code app.cta}
     * @return an outlined hero button to the app page, for a dark background
     */
    static Anchor hero(final String aLabel) {
        final Anchor a = new Anchor(HREF, "📱 " + aLabel);
        a.getElement().setAttribute("data-umami-event", "app-page");
        a.getElement().setAttribute("data-umami-event-from", "hero");
        a.getStyle()
            .set("display", "inline-block").set("color", "white")
            .set("border", "2px solid rgba(255,255,255,0.7)")
            .set("font-weight", "700").set("font-size", "16px")
            .set("padding", "10px 24px").set("border-radius", "4px").set("text-decoration", "none")
            .set("margin", "0 0 0 12px");
        return a;
    }
}
