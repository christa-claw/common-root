// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import org.religioustext.app.i18n.LocaleUtil;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

import java.util.List;
import java.util.Map;

/**
 * Thin, server-rendered per-source landing pages at {@code /read/:code} —
 * e.g. {@code /read/hi} (Hindi Bible), {@code /read/he} (Hebrew Bible),
 * {@code /read/quran}.
 *
 * <p>The point of this view existing at all, distinct from the single {@code
 * /reader} SPA route: each of these is an independently indexable, independently
 * rankable URL with its own real {@code <title>}/description (see {@link
 * org.religioustext.app.config.LandingSeoListener} for how those land in the
 * actual HTML response, which is what search engines and link-preview
 * scrapers read — Vaadin's client-side navigation alone would not give them
 * one). Before this, {@code /} was the only page competing for every query
 * ("hindi bible online", "quran arabic english", ...).
 *
 * <p>Content lives in {@link LandingPage#PAGES} — this class is purely the
 * rendering shell, so a new language/text is a data entry, not a new class.
 *
 * <p>Deliberately does NOT reuse {@link AboutView}'s heavier chrome (auth
 * state, language switcher, sponsor CTA) — these pages are meant to load
 * fast and stay simple; a visitor who wants the full app clicks through to
 * {@code /} or straight into {@code /reader}.
 */
@Route("read")
@AnonymousAllowed
public class LandingPageView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle {

    private LandingPage page;
    private final BuildInfo buildInfo;

    public LandingPageView(final BuildInfo aBuildInfo) {
        this.buildInfo = aBuildInfo;
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("overflow-y", "auto").set("overflow-x", "hidden");
    }

    @Override
    public void setParameter(final BeforeEvent aEvent, final String aParameter) {
        page = aParameter == null ? null : LandingPage.PAGES.get(aParameter);
        if (page == null) {
            aEvent.rerouteToError(NotFoundException.class);
            return;
        }
        removeAll();
        add(buildNavBar(), buildHero(), buildBody(), buildCrossLinks(), buildFooter());
    }

    /** Per-instance browser-tab title for in-app (client-side) navigation into
     *  this view — e.g. a RouterLink clicked from another page. The tags that
     *  matter for search engines and link previews are injected separately,
     *  server-side, into the raw HTML by LandingSeoListener; this only covers
     *  the SPA-navigation case where no fresh HTTP request happens. */
    @Override
    public String getPageTitle() {
        return page == null ? "Common Root?" : page.title;
    }

    // ── Nav ───────────────────────────────────────────────────────────

    /** The shared header — see {@link SiteHeader} for why this page no
     *  longer builds its own. The trailing link is this page's only
     *  page-specific chrome. */
    private Div buildNavBar() {
        final Anchor allTexts = new Anchor("/", getTranslation("edition.allTexts", LocaleUtil.currentLocale()));
        allTexts.getStyle()
            .set("font-size", "14px").set("color", "var(--lumo-secondary-text-color)")
            .set("text-decoration", "none").set("white-space", "nowrap");
        return new SiteHeader(buildInfo, allTexts);
    }

    // ── Hero ──────────────────────────────────────────────────────────

    private Div buildHero() {
        final Div hero = new Div();
        hero.setWidthFull();
        hero.getStyle()
            .set("background", "linear-gradient(135deg, #1a3a5c 0%, #2e6da4 100%)")
            .set("padding", "56px 10% 48px").set("text-align", "center")
            .set("box-sizing", "border-box");

        final H1 heading = new H1(page.heroHeading);
        heading.getStyle()
            .set("color", "white").set("font-size", "clamp(22px, 3.6vw, 36px)")
            .set("font-weight", "700").set("margin", "0 0 20px 0").set("line-height", "1.25");

        final Div scriptBox = new Div();
        scriptBox.getStyle()
            .set("background", "rgba(255,255,255,0.08)").set("border-radius", "10px")
            .set("padding", "22px 28px").set("margin", "0 auto 24px")
            .set("max-width", "600px").set("border", "1px solid rgba(255,255,255,0.15)");

        final Paragraph sample = new Paragraph(page.scriptSample);
        sample.getStyle()
            .set("color", "white").set("font-size", "clamp(18px, 2.6vw, 26px)")
            .set("margin", "0 0 8px 0").set("line-height", "1.6").set("direction", "auto");

        final Paragraph gloss = new Paragraph(page.scriptSampleGloss);
        gloss.getStyle()
            .set("color", "rgba(255,255,255,0.65)").set("font-size", "13px")
            .set("margin", "0").set("font-style", "italic");

        scriptBox.add(sample, gloss);

        final Anchor openReader = new Anchor(page.readerHref, "Open the Reader →");
        openReader.getStyle()
            .set("display", "inline-block").set("background", "#c9a84c").set("color", "#1a3a5c")
            .set("font-weight", "700").set("font-size", "16px")
            .set("padding", "13px 30px").set("border-radius", "4px").set("text-decoration", "none");

        hero.add(heading, scriptBox, openReader);
        return hero;
    }

    // ── Body ──────────────────────────────────────────────────────────

    private Div buildBody() {
        final Div s = new Div();
        s.getStyle().set("padding", "48px 10%").set("box-sizing", "border-box");

        for (final String para : page.heroBody.split("\n\n")) {
            final Paragraph p = new Paragraph(para.trim());
            p.getStyle().set("color", "#444").set("line-height", "1.8")
             .set("font-size", "16px").set("margin", "0 0 18px 0").set("max-width", "700px");
            s.add(p);
        }
        return s;
    }

    // ── Cross-links to the other landing pages + full library ─────────

    private Div buildCrossLinks() {
        final Div s = new Div();
        s.getStyle().set("padding", "0 10% 48px").set("box-sizing", "border-box");

        final H2 h = new H2("More on Common Root");
        h.getStyle().set("font-size", "16px").set("color", "#1a3a5c").set("margin", "0 0 12px 0");
        s.add(h);

        final Div links = new Div();
        links.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "10px");

        for (final Map.Entry<String, LandingPage> other : LandingPage.PAGES.entrySet()) {
            if (other.getKey().equals(page.slug)) continue;   // don't link to self
            final Anchor a = new Anchor("/read/" + other.getKey(), other.getValue().heroHeading);
            a.getStyle()
                .set("background", "#f0f4f8").set("color", "#1a3a5c")
                .set("padding", "8px 16px").set("border-radius", "20px")
                .set("font-size", "13px").set("text-decoration", "none");
            links.add(a);
        }

        final Anchor browseAll = new Anchor("/", "Browse the full library →");
        browseAll.getStyle()
            .set("background", "#1a3a5c").set("color", "white")
            .set("padding", "8px 16px").set("border-radius", "20px")
            .set("font-size", "13px").set("text-decoration", "none");
        links.add(browseAll);

        s.add(links);
        return s;
    }

    // ── Footer ────────────────────────────────────────────────────────

    private Div buildFooter() {
        final Div footer = new Div();
        footer.setWidthFull();
        footer.getStyle()
            .set("background", "#1a3a5c").set("padding", "20px 10%")
            .set("box-sizing", "border-box");

        final Span copy = new Span(
            getTranslation("about.footer.copyright", LocaleUtil.currentLocale()));
        copy.getStyle().set("color", "rgba(255,255,255,0.6)").set("font-size", "13px");

        footer.add(copy);
        return footer;
    }
}
