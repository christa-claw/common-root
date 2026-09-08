// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import org.religioustext.app.ui.components.LanguageSelect;

import java.util.Locale;
import java.util.function.Function;

/**
 * The site header every content page carries: wordmark, version pill and
 * environment chip, language selector, and whatever trailing links the page
 * wants.
 *
 * <p><b>Why this exists.</b> Four views had grown their own nav bar —
 * {@link ReaderView}, {@link AboutView}, {@link LandingPageView} and
 * {@link EditionInfoView} — and they had drifted. The landing and edition pages
 * carried neither the version pill nor the language selector, which contradicts
 * {@link BuildInfo}'s own contract ("the build identity every page shows") and,
 * worse, left a reader on a translated page with no way to switch language.
 * Copy-pasted chrome drifts silently because nothing fails when it does.
 *
 * <p>Deliberately a plain component rather than a Spring bean: Vaadin
 * components cannot be shared between UIs, so each page builds its own from the
 * injected {@link BuildInfo} — the same pattern {@code BuildInfo.badge()}
 * already uses, and for the same reason.
 *
 * <p>{@link ReaderView} keeps its own toolbar. It is not a content page: its
 * header carries the reference field, search, comments and copy-link controls
 * that only make sense there, and forcing it through this component would mean
 * parameterising away everything this class is for.
 */
public class SiteHeader extends Div {

    /**
     * @param aBuildInfo the injected build-identity bean
     * @param theTrailing links or controls to place after the language selector,
     *                    in order; may be empty
     */
    public SiteHeader(final BuildInfo aBuildInfo, final Component... theTrailing) {
        this(aBuildInfo, (Function<Locale, String>) null, theTrailing);
    }

    /**
     * The same header for a page whose CONTENT language is decided by its URL
     * rather than by the session — {@code EditionInfoView} — and which therefore
     * has to tell the switcher where each language lives instead of letting it
     * guess from the address. See {@link LanguageSelect}.
     *
     * @param aBuildInfo     the injected build-identity bean
     * @param aLanguageTarget given a chosen locale, the path to navigate to;
     *                        null to let the switcher rewrite the URL itself
     * @param theTrailing    links or controls to place after the language
     *                       selector, in order; may be empty
     */
    public SiteHeader(final BuildInfo aBuildInfo
                      , final Function<Locale, String> aLanguageTarget
                      , final Component... theTrailing) {
        getStyle()
            .set("display", "flex").set("align-items", "center").set("gap", "12px")
            .set("padding", "12px 32px")
            .set("background", "var(--lumo-base-color)")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
            .set("position", "sticky").set("top", "0").set("z-index", "100")
            .set("box-sizing", "border-box").set("width", "100%");

        final Anchor logo = new Anchor("/", "✦ Common Root?");
        logo.getStyle()
            .set("font-weight", "700").set("font-size", "16px")
            .set("color", "var(--lumo-primary-color)").set("text-decoration", "none")
            .set("white-space", "nowrap");
        add(logo);

        if (aBuildInfo != null) {
            add(aBuildInfo.badge());
        }

        final Span spacer = new Span();
        spacer.getStyle().set("flex-grow", "1");
        add(spacer);

        final LanguageSelect langSelect = new LanguageSelect(aLanguageTarget);
        langSelect.getStyle().set("margin-right", "4px");
        add(langSelect);

        for (final Component c : theTrailing) {
            if (c != null) add(c);
        }
    }
}
