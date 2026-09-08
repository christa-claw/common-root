// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEvent;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.HasUrlParameter;
import com.vaadin.flow.router.NotFoundException;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.WildcardParameter;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.religioustext.app.i18n.LocaleUtil;

import java.util.Locale;

/**
 * Thin, server-rendered per-edition bibliographic pages at {@code
 * /edition/:abbr} — "who translated this, when, where, from what" for a
 * single edition, reached via the small "ℹ️" icon on that edition's card in
 * {@link AboutView}'s translation grids.
 *
 * <p>Distinct in PURPOSE from {@link LandingPageView}: that class is built to
 * independently rank for a language-level search query and deliberately
 * covers one page per LANGUAGE, not per edition (see {@link LandingPage}'s
 * javadoc). This class is a plain informational interstitial for someone
 * already on the site — every edition can eventually have one. Same shell
 * shape as {@link LandingPageView} (nav / hero / body / footer), swapping
 * the language pages' script-sample pull-quote for a facts strip, since
 * bibliographic facts are the point here rather than a taste of the text.
 *
 * <p>Content lives in {@link EditionInfo#PAGES} — this class is purely the
 * rendering shell, so a new edition's page is a data entry, not a new class
 * (see that class's javadoc for the intended one-at-a-time, recurring-task
 * workflow for filling it in).
 *
 * <p>Deliberately does NOT reuse {@link AboutView}'s heavier chrome, same
 * rationale as {@link LandingPageView}: loads fast, stays simple, a visitor
 * who wants the full app clicks through to {@code /} or {@code /reader}.
 */
@Route("edition")
@AnonymousAllowed
public class EditionInfoView extends VerticalLayout implements HasUrlParameter<String>, HasDynamicTitle {

    private EditionInfo page;
    private final BuildInfo buildInfo;

    public EditionInfoView(final BuildInfo aBuildInfo) {
        this.buildInfo = aBuildInfo;
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("overflow-y", "auto").set("overflow-x", "hidden");
    }

    /** Wildcard, not a single segment: a translated page is addressed
     *  {@code /edition/fi/WLC}, which a plain {@code HasUrlParameter<String>}
     *  would refuse as two segments. The captured value ("WLC", "fi/WLC") is the
     *  {@link EditionInfo#PAGES} key verbatim, so adding a language stays a
     *  matter of adding a map entry. */
    @Override
    public void setParameter(final BeforeEvent aEvent,
                             @WildcardParameter final String aParameter) {
        page = resolve(aParameter);
        if (page == null) {
            aEvent.rerouteToError(NotFoundException.class);
            return;
        }
        removeAll();
        add(buildNavBar(), buildHero(), buildFacts(), buildBody(), buildFooter());
    }

    /** Resolve the {@link EditionInfo#PAGES} key. An explicit language prefix
     *  ("fi/WLC") is served verbatim. A bare abbreviation ("WLC") prefers the
     *  current UI locale's variant when one exists, falling back to the English
     *  base entry — so a Finnish visitor landing on /edition/AGR1548 reads the
     *  Finnish article, while the per-language URLs (and the sitemap built from
     *  them) keep their one-URL-per-variant shape for crawlers. */
    private static EditionInfo resolve(final String aParameter) {
        if (aParameter == null || aParameter.isBlank()) {
            return null;
        }
        if (!aParameter.contains("/")) {
            final String lang = LocaleUtil.currentLocale().getLanguage();
            if (!"en".equals(lang)) {
                final EditionInfo localized = EditionInfo.PAGES.get(lang + "/" + aParameter);
                if (localized != null) {
                    return localized;
                }
            }
        }
        return EditionInfo.PAGES.get(aParameter);
    }

    /** Same rationale as {@link LandingPageView#getPageTitle}: covers SPA
     *  in-app navigation only; the tags that matter for search engines and
     *  link previews are injected server-side by LandingSeoListener. */
    @Override
    public String getPageTitle() {
        return page == null ? "Common Root?" : page.title;
    }

    /** Chrome only. The ARTICLE is not translated through the bundles — it is
     *  edition-specific prose, so each language is its own EditionInfo entry
     *  under its own path. This just stops a Finnish reader meeting English
     *  navigation on a page whose body may already be Finnish. */
    private String t(final String aKey) {
        return getTranslation(aKey, LocaleUtil.currentLocale());
    }

    // ── Nav ───────────────────────────────────────────────────────────

    /** The shared header — see {@link SiteHeader} for why this page no
     *  longer builds its own. The trailing link is this page's only
     *  page-specific chrome. */
    private Div buildNavBar() {
        final Anchor allTexts = new Anchor("/", t("edition.allTexts"));
        allTexts.getStyle()
            .set("font-size", "14px").set("color", "var(--lumo-secondary-text-color)")
            .set("text-decoration", "none").set("white-space", "nowrap");
        return new SiteHeader(buildInfo, this::languageTarget, allTexts);
    }

    /** Delegates to {@link EditionInfo#targetPathFor}, which owns the reason
     *  this page needs one at all. */
    private String languageTarget(final Locale aLocale) {
        return EditionInfo.targetPathFor(page.abbr, aLocale);
    }

    // ── Hero ──────────────────────────────────────────────────────────

    private Div buildHero() {
        final Div hero = new Div();
        hero.setWidthFull();
        hero.getStyle()
            .set("background", "linear-gradient(135deg, #1a3a5c 0%, #2e6da4 100%)")
            .set("padding", "56px 10% 48px").set("text-align", "center")
            .set("box-sizing", "border-box");

        final Span abbrTag = new Span(page.abbr);
        abbrTag.getStyle()
            .set("display", "inline-block").set("background", "rgba(255,255,255,0.12)")
            .set("color", "white").set("font-weight", "700").set("font-size", "13px")
            .set("padding", "4px 12px").set("border-radius", "20px").set("margin-bottom", "16px");

        final H1 heading = new H1(page.heroHeading);
        heading.getStyle()
            .set("color", "white").set("font-size", "clamp(22px, 3.6vw, 36px)")
            .set("font-weight", "700").set("margin", "0 0 24px 0").set("line-height", "1.25");

        final Anchor openReader = new Anchor(page.readerHref, t("edition.openReader"));
        openReader.getStyle()
            .set("display", "inline-block").set("background", "#c9a84c").set("color", "#1a3a5c")
            .set("font-weight", "700").set("font-size", "16px")
            .set("padding", "13px 30px").set("border-radius", "4px").set("text-decoration", "none");

        hero.add(abbrTag, heading, openReader);
        return hero;
    }

    // ── Facts strip ─────────────────────────────────────────────────

    private Div buildFacts() {
        final Div wrap = new Div();
        wrap.getStyle().set("padding", "40px 10% 0").set("box-sizing", "border-box");

        final Div grid = new Div();
        grid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(220px, 1fr))")
            .set("gap", "16px").set("max-width", "760px")
            .set("background", "#f8fafc").set("border-radius", "8px")
            .set("padding", "24px").set("box-sizing", "border-box");

        for (final var entry : page.facts.entrySet()) {
            final Div cell = new Div();
            final Span label = new Span(entry.getKey());
            label.getStyle()
                .set("display", "block").set("font-size", "11px").set("font-weight", "700")
                .set("color", "#8a9bb0").set("text-transform", "uppercase")
                .set("letter-spacing", "0.5px").set("margin-bottom", "4px");
            // The value may name other editions in [[ABBR|text]] markup; each
            // marked run becomes its own Anchor so the strip stays a lineage a
            // reader can walk rather than a list of names.
            final Span value = new Span();
            value.getStyle()
                .set("display", "block").set("font-size", "14px")
                .set("color", "#1a3a5c").set("line-height", "1.5");
            for (final EditionInfo.FactPart part : EditionInfo.parseFact(entry.getValue(), page.lang)) {
                if (part.href() == null) {
                    value.add(new Span(part.text()));
                } else {
                    final Anchor link = new Anchor(part.href(), part.text());
                    link.getStyle()
                        .set("color", "#2c5f8d").set("text-decoration", "underline")
                        .set("text-underline-offset", "2px");
                    value.add(link);
                }
            }
            cell.add(label, value);
            grid.add(cell);
        }

        wrap.add(grid);

        // A quiet line under the strip on the pages an outside reader improved.
        // Deliberately below the facts rather than inside them: what the page
        // owes someone is not a fact about the edition.
        if (page.thanks != null && !page.thanks.isBlank()) {
            final Div credit = new Div();
            credit.setText(page.thanks);
            credit.getStyle()
                .set("max-width", "760px").set("margin", "10px 0 0 0")
                .set("font-size", "12px").set("font-style", "italic")
                .set("color", "#8a9bb0").set("line-height", "1.5");
            wrap.add(credit);
        }
        return wrap;
    }

    // ── Body ──────────────────────────────────────────────────────────

    private Div buildBody() {
        final Div s = new Div();
        s.getStyle().set("padding", "32px 10% 48px").set("box-sizing", "border-box");

        for (final String para : page.heroBody.split("\n\n")) {
            final Paragraph p = new Paragraph(para.trim());
            p.getStyle().set("color", "#444").set("line-height", "1.8")
             .set("font-size", "16px").set("margin", "0 0 18px 0").set("max-width", "700px");
            s.add(p);
        }
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
            t("about.footer.copyright"));
        copy.getStyle().set("color", "rgba(255,255,255,0.6)").set("font-size", "13px");

        footer.add(copy);
        return footer;
    }
}
