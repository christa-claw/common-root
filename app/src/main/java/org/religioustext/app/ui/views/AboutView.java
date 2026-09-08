// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Location;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouterLink;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.spring.security.AuthenticationContext;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.service.UserService;
import org.religioustext.app.ui.components.LanguageSelect;

@Route("")
@PageTitle("Common Root?")
@AnonymousAllowed
public class AboutView extends VerticalLayout implements BeforeEnterObserver {

    private final BuildInfo buildInfo;
    private final AuthenticationContext authContext;
    private final UserService userService;

    public AboutView(final BuildInfo aBuildInfo,
                     final AuthenticationContext anAuthContext,
                     final UserService aUserService) {
        this.buildInfo = aBuildInfo;
        this.authContext = anAuthContext;
        this.userService = aUserService;
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle()
            .set("overflow-y", "auto")
            .set("overflow-x", "hidden");

        add(
             buildNavBar()
            , buildHero()
            , buildHowToSection()
            , buildDisplayModesSection()
            , buildChapterVerseProblemsSection()
            , buildQuranSection()
            , buildAvailableTextsSection()
            , buildSearchSection()
            , buildSourcesSection()
            , buildCommentsSection()
            , buildAboutSection()
            , buildFooter());
    }

    /**
     * Post-login return-to-reader: the reader stashes its live link under
     * {@link ReaderView#POST_LOGIN_REDIRECT_ATTR} when Sign In is clicked, and Spring Security
     * lands here ({@code /}) after the form login. If the stash is present it is CONSUMED
     * (one-shot — visiting this page anonymous also clears it, so an abandoned sign-in doesn't
     * bounce a later one) and, when the visitor is now authenticated, the navigation is
     * forwarded to the stashed reader view — columns, refs, panel, and all.
     */
    @Override
    public void beforeEnter(final BeforeEnterEvent anEvent) {
        final var session = VaadinSession.getCurrent() == null ? null
            : VaadinSession.getCurrent().getSession();
        if (session == null) return;
        final Object stashed = session.getAttribute(ReaderView.POST_LOGIN_REDIRECT_ATTR);
        if (stashed == null) return;
        // Consume the stash ONLY when we can act on it — i.e. we're authenticated and about to
        // forward. Removing it earlier burned it if About rendered once unauthenticated during
        // the login redirect chain, so the real post-login visit found nothing and stayed here.
        if (!authContext.isAuthenticated()) return;
        final String path = stashed.toString();
        if (!path.startsWith("/reader")) return;   // only ever forward into the reader
        session.removeAttribute(ReaderView.POST_LOGIN_REDIRECT_ATTR);   // one-shot, now that we'll use it
        final Location target = new Location(path.substring(1));   // strip leading '/'
        anEvent.forwardTo(target.getPath(), target.getQueryParameters());
    }

    /** Translate a key using the session's chosen locale (set before the view is
     *  built by LocaleInitListener). Unknown keys fall back to the English base
     *  bundle, so untranslated landing-page keys simply render in English. */
    private String t(final String aKey) {
        return getTranslation(aKey, LocaleUtil.currentLocale());
    }

    /** Join two paragraph keys with the blank-line separator the card/example
     *  renderers split on. */
    private String para2(final String aKey1, final String aKey2) {
        return t(aKey1) + "\n\n" + t(aKey2);
    }

    // ── Nav bar ───────────────────────────────────────────────────────

    private HorizontalLayout buildNavBar() {
        final HorizontalLayout nav = new HorizontalLayout();
        nav.setWidthFull();
        nav.setAlignItems(Alignment.CENTER);
        nav.getStyle()
            .set("padding", "12px 32px")
            .set("background", "var(--lumo-base-color)")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
            .set("position", "sticky").set("top", "0").set("z-index", "100");

        final Span logo = new Span("✦ Common Root?");
        logo.getStyle()
            .set("font-weight", "700").set("font-size", "16px")
            .set("color", "var(--lumo-primary-color)");

        // Version pill + environment chip — the shared BuildInfo pair every page carries.
        final Div version = buildInfo.badge();
        version.getStyle().set("margin-left", "8px");
        final Span navSpacer = new Span();
        navSpacer.getStyle().set("flex-grow", "1");

        final RouterLink readerLink = new RouterLink(t("about.nav.openReader"), ReaderView.class);
        readerLink.getStyle()
            .set("font-size", "14px").set("color", "var(--lumo-primary-color)")
            .set("font-weight", "600").set("text-decoration", "none");

        final Anchor sponsorLink = new Anchor(SPONSOR_URL, t("nav.sponsor"));
        sponsorLink.setTarget("_blank");
        sponsorLink.getElement().setAttribute("rel", "noopener");
        sponsorLink.setTitle(t("about.support.title"));
        sponsorLink.getStyle()
            .set("font-size", "14px").set("color", "#c9a84c")
            .set("font-weight", "600").set("text-decoration", "none")
            .set("margin-right", "16px").set("white-space", "nowrap");

        final LanguageSelect langSelect = new LanguageSelect();
        langSelect.getStyle().set("margin-right", "12px");

        nav.add(logo, version, navSpacer, langSelect);
        if (authContext.isAuthenticated()) {
            // Same display-name resolution as the reader toolbar (UserService.displayLabel).
            final String label = userService.displayLabel(authContext.getPrincipalName().orElse(""));
            final Anchor profile = new Anchor("/profile", label);
            profile.getStyle()
                .set("font-size", "14px").set("color", "var(--lumo-secondary-text-color)")
                .set("text-decoration", "none").set("margin-right", "14px");
            final Button signOut = new Button(t("action.signOut"), e -> authContext.logout());
            signOut.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            signOut.getStyle().set("margin-right", "12px");
            nav.add(profile, signOut);
        } else {
            // A Button so a voluntary sign-in clears any stale Spring Security saved request
            // (same rule as the reader's Sign In) — post-login then lands predictably on "/".
            final Button signIn = new Button(t("action.signIn"), e -> {
                com.vaadin.flow.server.VaadinSession.getCurrent().getSession()
                    .removeAttribute("SPRING_SECURITY_SAVED_REQUEST");
                getUI().ifPresent(ui -> ui.navigate("login"));
            });
            signIn.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE);
            signIn.getStyle()
                .set("font-size", "14px").set("color", "var(--lumo-primary-color)")
                .set("font-weight", "600").set("margin-right", "14px");
            final Anchor createAccount = new Anchor("/register", t("action.createAccount"));
            createAccount.getStyle()
                .set("font-size", "14px").set("background", "#c9a84c").set("color", "#1a3a5c")
                .set("font-weight", "600").set("padding", "6px 14px").set("border-radius", "4px")
                .set("text-decoration", "none").set("margin-right", "16px").set("white-space", "nowrap");
            nav.add(signIn, createAccount);
        }
        nav.add(sponsorLink, readerLink);
        return nav;
    }

    // ── Hero ──────────────────────────────────────────────────────────

    private Div buildHero() {
        final Div hero = new Div();
        hero.setWidthFull();
        hero.getStyle()
            .set("background", "linear-gradient(135deg, #1a3a5c 0%, #2e6da4 100%)")
            .set("padding", "60px 10% 60px").set("text-align", "center")
            .set("box-sizing", "border-box");

        // Logo — transparent PNG served from META-INF/resources/docs via symlink;
        // in Vaadin the /docs path isn't served automatically, so the docs image
        // is copied to static resources alongside the icon set.
        final com.vaadin.flow.component.html.Image logo =
            new com.vaadin.flow.component.html.Image("/icons/logo-about.png", "Common Root? logo");
        logo.getStyle()
            .set("width",  "clamp(120px, 18vw, 200px)")
            .set("height", "auto")
            .set("margin-bottom", "24px");

        final H1 title = new H1(t("about.hero.title"));
        title.getStyle()
            .set("color", "white").set("font-size", "clamp(24px, 4vw, 42px)")
            .set("font-weight", "700").set("margin", "0 0 16px 0").set("line-height", "1.2");

        final Paragraph sub = new Paragraph(t("about.hero.subtitle"));
        sub.getStyle()
            .set("color", "rgba(255,255,255,0.85)").set("font-size", "clamp(14px, 2vw, 18px)")
            .set("max-width", "640px").set("margin", "0 auto 32px").set("line-height", "1.6");

        final RouterLink openReader = new RouterLink(t("about.hero.cta"), ReaderView.class);
        openReader.getStyle()
            .set("display", "inline-block").set("background", "#c9a84c").set("color", "#1a3a5c")
            .set("font-weight", "700").set("font-size", "16px")
            .set("padding", "12px 28px").set("border-radius", "4px").set("text-decoration", "none");

        hero.add(logo, title, sub, openReader);
        return hero;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private Div section(final String aBackground) {
        final Div s = new Div();
        s.setWidthFull();
        s.getStyle().set("padding", "56px 10%").set("background", aBackground)
                    .set("box-sizing", "border-box");
        return s;
    }

    private H2 sectionTitle(final String aText) {
        final H2 h = new H2(aText);
        h.getStyle()
            .set("font-size", "26px").set("font-weight", "700").set("color", "#1a3a5c")
            .set("margin", "0 0 8px 0").set("border-bottom", "3px solid #c9a84c")
            .set("padding-bottom", "8px").set("display", "inline-block");
        return h;
    }

    private Paragraph prose(final String aText) {
        final Paragraph p = new Paragraph(aText);
        p.getStyle().set("color", "#444").set("line-height", "1.8")
         .set("font-size", "15px").set("margin", "0 0 14px 0").set("max-width", "760px");
        return p;
    }

    // ── How to use ────────────────────────────────────────────────────

    private Div buildHowToSection() {
        final Div s = section("white");
        s.getStyle().set("padding", "56px 5%");
        final Div header = new Div();
        header.add(sectionTitle(t("about.howto.title")));
        final Paragraph intro = new Paragraph(t("about.howto.intro"));
        intro.getStyle().set("color", "#555").set("margin", "12px 0 32px")
             .set("max-width", "680px").set("line-height", "1.7");
        header.add(intro);
        s.add(header);

        final Div steps = new Div();
        steps.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(160px, 1fr))")
            .set("gap", "12px")
            .set("width", "100%");

        addStep(steps, "1", t("about.howto.step1.title"), t("about.howto.step1.body"));
        addStep(steps, "2", t("about.howto.step2.title"), t("about.howto.step2.body"));
        addStep(steps, "3", t("about.howto.step3.title"), t("about.howto.step3.body"));
        addStep(steps, "4", t("about.howto.step4.title"), t("about.howto.step4.body"));
        addStep(steps, "5", t("about.howto.step5.title"), t("about.howto.step5.body"));
        addStep(steps, "6", t("about.howto.step6.title"), t("about.howto.step6.body"));
        addStep(steps, "7", t("about.howto.step7.title"), t("about.howto.step7.body"));

        s.add(steps);
        return s;
    }

    private void addStep(final Div aContainer, final String aNum, final String aTitle, final String aBody) {
        final Div step = new Div();
        step.getStyle()
            .set("background", "#f8fafc").set("border-radius", "8px")
            .set("padding", "12px 14px").set("border-left", "4px solid #1a3a5c");

        final Span number = new Span(aNum);
        number.getStyle()
            .set("display", "inline-block").set("background", "#1a3a5c").set("color", "white")
            .set("font-weight", "700").set("font-size", "13px")
            .set("width", "24px").set("height", "24px").set("border-radius", "50%")
            .set("text-align", "center").set("line-height", "24px").set("margin-bottom", "10px");

        final H3 stepTitle = new H3(aTitle);
        stepTitle.getStyle()
            .set("font-size", "13px").set("font-weight", "700")
            .set("color", "#1a3a5c").set("margin", "0 0 4px 0");

        final Paragraph stepBody = new Paragraph(aBody);
        stepBody.getStyle()
            .set("font-size", "12px").set("color", "#666").set("margin", "0").set("line-height", "1.5");

        step.add(number, stepTitle, stepBody);
        aContainer.add(step);
    }

    // ── Display modes ─────────────────────────────────────────────────

    private Div buildDisplayModesSection() {
        final Div s = section("#f0f4f8");
        s.add(sectionTitle(t("about.modes.title")));

        final Paragraph intro = prose(t("about.modes.intro"));
        intro.getStyle().set("margin", "12px 0 32px");
        s.add(intro);

        final Div grid = new Div();
        grid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(300px, 1fr))")
            .set("gap", "24px");

        addModeCard(grid, t("about.modes.original.label"), t("about.modes.original.subtitle"),
            para2("about.modes.original.p1", "about.modes.original.p2"), "#8b4513",
            modeHref("original"));

        addModeCard(grid, t("about.modes.simplified.label"), t("about.modes.simplified.subtitle"),
            para2("about.modes.simplified.p1", "about.modes.simplified.p2"), "#a0522d",
            modeHref("continuous"));

        addModeCard(grid, t("about.modes.chapters.label"), t("about.modes.chapters.subtitle"),
            para2("about.modes.chapters.p1", "about.modes.chapters.p2"), "#1a3a5c",
            modeHref("chapters"));

        addModeCard(grid, t("about.modes.verses.label"), t("about.modes.verses.subtitle"),
            para2("about.modes.verses.p1", "about.modes.verses.p2"), "#2e6da4",
            modeHref("verses"));

        addModeCard(grid, t("about.modes.titles.label"), t("about.modes.titles.subtitle"),
            para2("about.modes.titles.p1", "about.modes.titles.p2"), "#c9a84c",
            modeHref("titles"));

        s.add(grid);
        return s;
    }

    /** Add a display-mode card wrapped in an anchor to {@code aReaderHref} —
     *  the whole card opens the reader showing this very mode on John 1. */
    private void addModeCard(final Div aContainer, final String aMode, final String aSubtitle,
                             final String aBody, final String anAccentColor,
                             final String aReaderHref) {
        final Div card = new Div();
        card.getStyle()
            .set("background", "white").set("border-radius", "8px")
            .set("padding", "24px").set("border-top", "4px solid " + anAccentColor)
            .set("box-shadow", "0 2px 8px rgba(0,0,0,0.06)");

        final Span modeLabel = new Span(aMode);
        modeLabel.getStyle()
            .set("display", "inline-block").set("background", anAccentColor).set("color", "white")
            .set("font-weight", "700").set("font-size", "12px").set("padding", "3px 10px")
            .set("border-radius", "20px").set("margin-bottom", "8px");

        final H3 sub = new H3(aSubtitle);
        sub.getStyle()
            .set("font-size", "16px").set("font-weight", "700").set("color", "#1a3a5c")
            .set("margin", "0 0 12px 0");

        card.add(modeLabel, sub);

        // Split body on \n\n into paragraphs
        for (final String para : aBody.split("\n\n")) {
            final Paragraph p = new Paragraph(para.trim());
            p.getStyle().set("font-size", "13px").set("color", "#555")
             .set("line-height", "1.7").set("margin", "0 0 10px 0");
            card.add(p);
        }

        // The whole card links into the reader opened in this mode — the About
        // page describes the mode, the reader demonstrates it.
        final Anchor open = new Anchor(aReaderHref, card);
        open.getElement().setAttribute("title", t("about.modes.openInReader"));
        open.addClassName("mode-card-link");
        open.getStyle()
            .set("text-decoration", "none").set("color", "inherit")
            .set("display", "flex");            // stretch the card to the grid cell
        card.getStyle().set("flex-grow", "1");
        aContainer.add(open);
    }


    // ── Chapter & verse problems ──────────────────────────────────────

    private Div buildChapterVerseProblemsSection() {
        final Div s = section("white");
        s.add(sectionTitle(t("about.problems.title")));

        s.add(prose(t("about.problems.intro")));

        addProblemExample(s, t("about.problems.isaiah.ref"), t("about.problems.isaiah.title"),
            para2("about.problems.isaiah.p1", "about.problems.isaiah.p2"),
            passageHref("ISA.52.13-53.12"));

        addProblemExample(s, t("about.problems.romans.ref"), t("about.problems.romans.title"),
            para2("about.problems.romans.p1", "about.problems.romans.p2"),
            passageHref("ROM.7.21-8.2"));

        addProblemExample(s, t("about.problems.philippians.ref"), t("about.problems.philippians.title"),
            para2("about.problems.philippians.p1", "about.problems.philippians.p2"),
            passageHref("PHP.4.10-13"));

        addProblemExample(s, t("about.problems.jeremiah.ref"), t("about.problems.jeremiah.title"),
            para2("about.problems.jeremiah.p1", "about.problems.jeremiah.p2"),
            passageHref("JER.29.10-14"));

        addProblemExample(s, t("about.problems.john.ref"), t("about.problems.john.title"),
            para2("about.problems.john.p1", "about.problems.john.p2"),
            passageHref("JHN.11.32-37"));

        // Broader point
        final Div callout = new Div();
        callout.getStyle()
            .set("background", "#f8fafc").set("border-left", "4px solid #c9a84c")
            .set("border-radius", "4px").set("padding", "20px 24px")
            .set("margin-top", "32px").set("max-width", "760px");

        final H3 calloutTitle = new H3(t("about.problems.callout.title"));
        calloutTitle.getStyle()
            .set("font-size", "15px").set("font-weight", "700")
            .set("color", "#1a3a5c").set("margin", "0 0 10px 0");

        final Paragraph calloutBody = new Paragraph(
            para2("about.problems.callout.p1", "about.problems.callout.p2"));
        calloutBody.getStyle()
            .set("font-size", "14px").set("color", "#555").set("line-height", "1.8").set("margin", "0");

        callout.add(calloutTitle, calloutBody);
        s.add(callout);

        return s;
    }

    // ── The Qur'an ────────────────────────────────────────────────────

    private Div buildQuranSection() {
        final Div s = section("white");
        s.add(sectionTitle(t("about.quran.title")));
        s.add(prose(t("about.quran.intro")));

        addQuranBlock(s, t("about.quran.original.title"),
            para2("about.quran.original.p1", "about.quran.original.p2"));
        addQuranBlock(s, t("about.quran.standardization.title"),
            para2("about.quran.standardization.p1", "about.quran.standardization.p2"));
        addQuranBlock(s, t("about.quran.orders.title"),
            para2("about.quran.orders.p1", "about.quran.orders.p2"));
        addQuranBlock(s, t("about.quran.meccaMedina.title"),
            para2("about.quran.meccaMedina.p1", "about.quran.meccaMedina.p2"));
        addQuranBlock(s, t("about.quran.translations.title"),
            para2("about.quran.translations.p1", "about.quran.translations.p2"));

        // Closing callout — mirrors the Bible section's callout styling.
        final Div callout = new Div();
        callout.getStyle()
            .set("background", "#f8fafc").set("border-left", "4px solid #c9a84c")
            .set("border-radius", "4px").set("padding", "20px 24px")
            .set("margin-top", "32px").set("max-width", "760px");

        final H3 calloutTitle = new H3(t("about.quran.callout.title"));
        calloutTitle.getStyle()
            .set("font-size", "15px").set("font-weight", "700")
            .set("color", "#1a3a5c").set("margin", "0 0 10px 0");

        final Paragraph calloutBody = new Paragraph(
            para2("about.quran.callout.p1", "about.quran.callout.p2"));
        calloutBody.getStyle()
            .set("font-size", "14px").set("color", "#555").set("line-height", "1.8").set("margin", "0");

        callout.add(calloutTitle, calloutBody);
        s.add(callout);
        return s;
    }

    /** A subsection of the Qur'an section: a heading + paragraphs, no scripture
     *  reference pill (unlike the Bible examples). Body splits on the blank-line
     *  separator. */
    private void addQuranBlock(final Div aContainer, final String aTitle, final String aBody) {
        final Div block = new Div();
        block.getStyle()
            .set("margin-bottom", "32px").set("padding-bottom", "32px")
            .set("border-bottom", "1px solid #e8edf2").set("max-width", "760px");

        final H3 h = new H3(aTitle);
        h.getStyle()
            .set("font-size", "17px").set("font-weight", "700")
            .set("color", "#1a3a5c").set("margin", "0 0 8px 0");
        block.add(h);

        for (final String para : aBody.split("\n\n")) {
            final Paragraph p = new Paragraph(para.trim());
            p.getStyle().set("font-size", "14px").set("color", "#444")
             .set("line-height", "1.8").set("margin", "0 0 12px 0");
            block.add(p);
        }
        aContainer.add(block);
    }

    private void addProblemExample(final Div aContainer, final String aReference,
                                   final String aTitle, final String aBody,
                                   final String aPassageHref) {
        final Div example = new Div();
        example.getStyle()
            .set("margin-bottom", "32px").set("padding-bottom", "32px")
            .set("border-bottom", "1px solid #e8edf2").set("max-width", "760px");

        final Div header = new Div();
        header.getStyle().set("display", "flex").set("align-items", "baseline")
              .set("gap", "12px").set("margin-bottom", "8px").set("flex-wrap", "wrap");

        final Span ref = new Span(aReference);
        ref.getStyle()
            .set("font-family", "monospace").set("font-size", "13px")
            .set("background", "#eef2f7").set("color", "#1a3a5c")
            .set("padding", "2px 8px").set("border-radius", "4px")
            .set("font-weight", "600").set("white-space", "nowrap");

        final H3 h = new H3(aTitle);
        h.getStyle()
            .set("font-size", "17px").set("font-weight", "700")
            .set("color", "#1a3a5c").set("margin", "0");

        // The reference chip links straight to the passage, opened in the
        // reader's default edition (preference → UI language → NIV) so the
        // reader can see the broken context for themselves.
        final Anchor refLink = new Anchor(aPassageHref, ref);
        refLink.getElement().setAttribute("title", t("about.problems.openInReader"));
        refLink.addClassName("problem-ref-link");
        refLink.getStyle().set("text-decoration", "none");
        header.add(refLink, h);
        example.add(header);

        for (final String para : aBody.split("\n\n")) {
            final Paragraph p = new Paragraph(para.trim());
            p.getStyle().set("font-size", "14px").set("color", "#444")
             .set("line-height", "1.8").set("margin", "0 0 12px 0");
            example.add(p);
        }

        aContainer.add(example);
    }

    // ── Available texts ───────────────────────────────────────────────

    /**
     * The translation-lineage ("rungs") explainer.
     *
     * <p>Promoted from a 12px grey caption to a real section on 2026-07-29. It was
     * the site's most distinctive feature described in its least prominent
     * typography, and the caption omitted the two things a reader most needs:
     * that rungs render ONLY in the Verses and Titles display modes (the single
     * likeliest reason someone concludes the feature is broken), and which
     * editions actually have a recorded ancestry.
     *
     * <p>The chain list is deliberately hard-coded rather than derived from
     * {@code SourceCatalog}: it mirrors the curated {@code @basedOn} map in
     * {@code patch_lineage.py}, and if the two drift apart that is a fact worth
     * noticing in review rather than papering over at render time. Abbreviations
     * and years are proper nouns and stay untranslated.
     */
    private void addLineageSection(final Div aSection) {
        final H3 header = new H3(t("about.texts.lineageTitle"));
        header.getStyle().set("color", "#1a3a5c").set("margin", "8px 0 12px 0")
            .set("font-size", "17px");
        aSection.add(header);

        for (final String key : new String[] {
                "about.texts.lineageIntro", "about.texts.lineageHow",
                "about.texts.lineageModes" }) {
            aSection.add(lineagePara(t(key)));
        }

        final Paragraph chainsIntro = lineagePara(t("about.texts.lineageChains"));
        chainsIntro.getStyle().set("margin", "0 0 8px 0");
        aSection.add(chainsIntro);

        final Div chains = new Div();
        chains.getStyle()
            .set("font-family", "ui-monospace, SFMono-Regular, Menlo, monospace")
            .set("font-size", "13px").set("color", "#345").set("line-height", "2")
            .set("background", "#fff").set("border", "1px solid #dde5ec")
            .set("border-radius", "6px").set("padding", "12px 16px")
            .set("margin", "0 0 16px 0").set("max-width", "760px")
            .set("overflow-x", "auto");
        for (final String chain : new String[] {
                "WEB \u2192 ASV \u2192 RV \u2192 KJV \u2192 GNV \u2192 \u2248 WLC / \u2248 TR",
                "DRA \u2192 VUL",
                "KR3338 \u2192 FB1776 \u2192 FB1642 \u2192 AGR1548 \u2192 "
                    + "LUT1545 \u2192 \u2248 TR / \u2248 WLC" }) {
            final Div row = new Div();
            row.setText(chain);
            chains.add(row);
        }
        aSection.add(chains);

        final Paragraph witness = lineagePara(t("about.texts.lineageWitness"));
        witness.getStyle().set("color", "#666").set("font-size", "13px")
            .set("margin", "0 0 32px 0");
        aSection.add(witness);
    }

    /** Body paragraph inside the lineage section \u2014 matches the problem-example prose. */
    private static Paragraph lineagePara(final String aText) {
        final Paragraph p = new Paragraph(aText);
        p.getStyle().set("font-size", "14px").set("color", "#444")
            .set("line-height", "1.8").set("margin", "0 0 14px 0")
            .set("max-width", "760px");
        return p;
    }

    private Div buildAvailableTextsSection() {
        final Div s = section("#f0f4f8");
        s.add(sectionTitle(t("about.texts.title")));

        final Paragraph intro = new Paragraph(t("about.texts.intro"));
        intro.getStyle().set("color", "#555").set("margin", "12px 0 28px").set("line-height", "1.6");
        s.add(intro);

        final H3 bibleHeader = new H3(t("about.texts.bibleHeader"));
        bibleHeader.getStyle().set("color", "#1a3a5c").set("margin", "0 0 12px 0").set("font-size", "17px");
        s.add(bibleHeader);

        final Div grid = new Div();
        grid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(280px, 1fr))")
            .set("gap", "12px").set("margin-bottom", "32px");

        // Translation names, abbreviations and years are proper nouns — left as-is.
        // Only the language and licence columns are localised.
        addTextCard(grid, "AGR1548", "Agricola 1548",             t("about.lang.finnish"),         "1548", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "ASV",    "American Standard Version",  t("about.lang.english"),         "1901", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "BES",    "Biblia en Español Sencillo", t("about.lang.spanish"),         "—",    "CC BY 4.0",                     "✅");
        addTextCard(grid, "BSB",    "Berean Standard Bible",      t("about.lang.english"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "CUV",    "Chinese Union Version",      t("about.lang.chinese"),         "1919", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "DBY",    "Darby (1885)",               t("about.lang.french"),          "1885", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "DIO",    "Diodati 1649",               t("about.lang.italian"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "DRA",    "Douay-Rheims 1899",          t("about.lang.englishCatholic"), "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "ELB",    "Elberfelder 1905",           t("about.lang.german"),          "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "FB1642", "Biblia 1642",                t("about.lang.finnish"),         "1642", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "FB1776", "Biblia 1776",                t("about.lang.finnish"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "FBV",    "Free Bible Version",         t("about.lang.english"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "GNV",    "Geneva Bible 1599",          t("about.lang.english"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "HEBM",   "Hebrew Bible (Masoretic OT + Delitzsch NT)", t("about.lang.hebrew"), "—", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "IRVHIN", "Indian Revised Version (IRV)", t("about.lang.hindi"),         "2019", "CC BY-SA 4.0",                  "✅");
        addTextCard(grid, "KJV",    "King James Version",         t("about.lang.english"),         "1611", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "LSV",    "Literal Standard Version",   t("about.lang.english"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "LUT1545", "Luther Bibel 1545", t("about.lang.german"),          "1545", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "LUT1912", "Luther Bibel 1912", t("about.lang.german"),          "1912", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "NASB",   "New American Standard Bible 2020", t("about.lang.english"),         "2020", t("about.license.licensed"),     "✅");
        addTextCard(grid, "NBLA",   "Nueva Biblia de las Américas", t("about.lang.spanish"),       "—",    t("about.license.licensed"),     "✅");
        addTextCard(grid, "NIV",    "New International Version",  t("about.lang.english"),         "2011", t("about.license.licensed"),     "✅");
        addTextCard(grid, "OLCIM",  "Baibal Olcim",               t("about.lang.matuChin"),        "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "PDDPT",  "Palabra de Dios para Ti",    t("about.lang.spanish"),         "—",    "CC BY-SA 4.0",                  "✅");
        addTextCard(grid, "RV",     "Revised Version 1885",       t("about.lang.english"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "RVR09",  "Reina Valera 1909",          t("about.lang.spanish"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "SV1917", "Svenska 1917",               t("about.lang.swedish"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "SVD",    "Smith & Van Dyck",           t("about.lang.arabic"),          "1865", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "SYN",    "Synodal",                    t("about.lang.russian"),         "1876", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "VBL",    "Versión Biblia Libre",       t("about.lang.spanish"),         "—",    "CC BY-SA 4.0",                  "✅");
        addTextCard(grid, "WEB",    "World English Bible",        t("about.lang.english"),         "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "YTC",    "Yorumsuz Türkçe Çeviri",     t("about.lang.turkish"),         "2023", "CC BY-ND 4.0",                  "✅");
        // Original-language and other editions made navigable by the canonical-seq
        // stamping pass (stamp_canonical.py). Names/abbreviations are proper nouns;
        // only the language + licence columns are localised.
        addTextCard(grid, "KR3338", "Kirkkoraamattu 1933/38",      t("about.lang.finnish"), "1933", t("about.license.publicDomain"), "✅");
        addTextCard(grid, "WLC",    "Westminster Leningrad Codex", t("about.lang.hebrew"),  "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "TR",     "Textus Receptus (NT)",        t("about.lang.greek"),   "—",    t("about.license.publicDomain"), "✅");
        addTextCard(grid, "VUL",    "Latin Vulgate",               t("about.lang.latin"),   "—",    t("about.license.publicDomain"), "✅");
        s.add(grid);

        addLineageSection(s);

        // Qur'an — Arabic Uthmani base + Pickthall English translation, both live.
        final H3 quranHeader = new H3(t("about.texts.quranHeader"));
        quranHeader.getStyle().set("color", "#1a3a5c").set("margin", "0 0 12px 0").set("font-size", "17px");
        s.add(quranHeader);

        final Div quranGrid = new Div();
        quranGrid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(280px, 1fr))")
            .set("gap", "12px").set("margin-bottom", "32px");
        addTextCard(quranGrid, "Q-AR", "Qur'an \u2014 Uthmani",  t("about.lang.arabic"),  "\u2014", t("about.license.publicDomain"), "✅");
        addTextCard(quranGrid, "Q-EN", "Qur'an \u2014 Pickthall", t("about.lang.english"), "1930",   t("about.license.publicDomain"), "✅");
        addTextCard(quranGrid, "Q-YA", "Qur'an \u2014 Yusuf Ali", t("about.lang.english"), "1934", t("about.license.publicDomainRegional"), "✅");
        addTextCard(quranGrid, "Q-SAB", "Qur'an \u2014 Sablukov", t("about.lang.russian"), "1878", t("about.license.publicDomain"), "✅");
        s.add(quranGrid);

        // Hadith — ten classical collections, Arabic matn + English translation,
        // ingested from fawazahmed0/hadith-api (see ingest_hadith.py). The Arabic
        // matn is classical/public domain; the English translation provenance is
        // still being verified per edition, so the licence label says as much.
        final H3 hadithHeader = new H3(t("about.texts.hadithHeader"));
        hadithHeader.getStyle().set("color", "#1a3a5c").set("margin", "0 0 12px 0").set("font-size", "17px");
        s.add(hadithHeader);

        final Div hadithGrid = new Div();
        hadithGrid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(280px, 1fr))")
            .set("gap", "12px").set("margin-bottom", "16px");
        final String hadithLang = t("about.lang.arabic") + " + " + t("about.lang.english");
        final String hadithLic  = t("about.license.hadith");
        addTextCard(hadithGrid, "BUK", "Sahih al-Bukhari",                       hadithLang, "\u2014", hadithLic, "✅", readerHref("buk-ar", true));
        addTextCard(hadithGrid, "MUS", "Sahih Muslim",                           hadithLang, "\u2014", hadithLic, "✅", readerHref("mus-ar", true));
        addTextCard(hadithGrid, "ABD", "Sunan Abi Dawud",                        hadithLang, "\u2014", hadithLic, "✅", readerHref("abd-ar", true));
        addTextCard(hadithGrid, "TIR", "Jami` at-Tirmidhi",                      hadithLang, "\u2014", hadithLic, "✅", readerHref("tir-ar", true));
        addTextCard(hadithGrid, "NAS", "Sunan an-Nasa'i",                        hadithLang, "\u2014", hadithLic, "✅", readerHref("nas-ar", true));
        addTextCard(hadithGrid, "IBM", "Sunan Ibn Majah",                        hadithLang, "\u2014", hadithLic, "✅", readerHref("ibm-ar", true));
        addTextCard(hadithGrid, "MAL", "Muwatta Malik",                          hadithLang, "\u2014", hadithLic, "✅", readerHref("mal-ar", true));
        addTextCard(hadithGrid, "NAW", "Forty Hadith of an-Nawawi",              hadithLang, "\u2014", hadithLic, "✅", readerHref("naw-ar", true));
        addTextCard(hadithGrid, "QUD", "Forty Hadith Qudsi",                     hadithLang, "\u2014", hadithLic, "✅", readerHref("qud-ar", true));
        addTextCard(hadithGrid, "DEH", "Forty Hadith of Shah Waliullah Dehlawi", hadithLang, "\u2014", hadithLic, "✅", readerHref("deh-ar", true));
        s.add(hadithGrid);

        final Paragraph hadithNote = new Paragraph(t("about.texts.hadithNote"));
        hadithNote.getStyle().set("color", "#888").set("font-size", "12px")
            .set("margin", "0 0 32px").set("max-width", "760px").set("line-height", "1.6");
        s.add(hadithNote);

        // Latter-day Saint standard works — Book of Mormon, Doctrine & Covenants,
        // Pearl of Great Price (see ingest_bom.py). Public domain (1830 BoM +
        // standard editions); copyrighted modern study apparatus excluded.
        final H3 ldsHeader = new H3(t("about.texts.ldsHeader"));
        ldsHeader.getStyle().set("color", "#1a3a5c").set("margin", "0 0 12px 0").set("font-size", "17px");
        s.add(ldsHeader);

        final Div ldsGrid = new Div();
        ldsGrid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(280px, 1fr))")
            .set("gap", "12px").set("margin-bottom", "16px");
        addTextCard(ldsGrid, "BOM", "Book of Mormon",         t("about.lang.english"), "—", t("about.license.publicDomain"), "✅");
        addTextCard(ldsGrid, "DC",  "Doctrine and Covenants", t("about.lang.english"), "—", t("about.license.publicDomain"), "✅");
        addTextCard(ldsGrid, "PGP", "Pearl of Great Price",   t("about.lang.english"), "—", t("about.license.publicDomain"), "✅");
        s.add(ldsGrid);

        final Paragraph ldsNote = new Paragraph(t("about.texts.ldsNote"));
        ldsNote.getStyle().set("color", "#888").set("font-size", "12px")
            .set("margin", "0 0 32px").set("max-width", "760px").set("line-height", "1.6");
        s.add(ldsNote);

        // Interlinear / study editions — word-for-word alignment aids, not reading
        // editions. Kept in their OWN section (as the LDS works are) so a gloss is
        // never mistaken for a translation: Wilson's sublinear gloss and his own
        // published Emphatic Version disagree at John 1:1, which is exactly why the
        // gloss is cited in debate and exactly why it must be labelled as a gloss.
        final H3 glossHeader = new H3(t("about.texts.glossHeader"));
        glossHeader.getStyle().set("color", "#1a3a5c").set("margin", "0 0 12px 0").set("font-size", "17px");
        s.add(glossHeader);

        final Div glossGrid = new Div();
        glossGrid.getStyle()
            .set("display", "grid")
            .set("grid-template-columns", "repeat(auto-fit, minmax(280px, 1fr))")
            .set("gap", "12px").set("margin-bottom", "16px");
        addTextCard(glossGrid, "DIAGIL", "Emphatic Diaglott (interlinear)",
                    t("about.lang.english"), "1864", t("about.license.publicDomain"), "\u2705");
        s.add(glossGrid);

        final Paragraph glossNote = new Paragraph(t("about.texts.glossNote"));
        glossNote.getStyle().set("color", "#888").set("font-size", "12px")
            .set("margin", "0 0 32px").set("max-width", "760px").set("line-height", "1.6");
        s.add(glossNote);

        final H3 comingHeader = new H3(t("about.texts.comingHeader"));
        comingHeader.getStyle().set("color", "#1a3a5c").set("margin", "0 0 12px 0").set("font-size", "17px");
        s.add(comingHeader);

        final Div coming = new Div();
        coming.getStyle().set("display", "flex").set("flex-wrap", "wrap").set("gap", "10px");
        // Mostly proper nouns; only the generic last item is localised.
        // NBLA was here until 2026-08-03, when it was ingested — a coming-soon
        // badge for a text that has a card two sections up is the same class of
        // error as the sources table that listed translations we did not hold.
        // Anything added here must come out when it lands.
        for (final String text : new String[]{
                "Additional Qur'an translations",
                t("about.texts.comingMore")}) {
            final Span badge = new Span("⏳ " + text);
            badge.getStyle()
                .set("background", "white").set("border", "1px solid #c9a84c")
                .set("color", "#1a3a5c").set("padding", "4px 12px")
                .set("border-radius", "20px").set("font-size", "13px");
            coming.add(badge);
        }
        s.add(coming);
        return s;
    }

    /**
     * Reader deep link opening a single column on the given source.
     *
     * @param aSrcToken      the source token — the source's {@code @abbreviation}
     *                       lowercased (see {@code docs/link-format.md})
     * @param aCompanionFlag whether the column opens with its in-column companion
     *                       translation visible ({@code c1.companion=1})
     * @return a root-relative {@code /reader?…} URL
     */
    private static String readerHref(final String aSrcToken
                                     , final boolean aCompanionFlag) {
        return "/reader?c1.src=" + aSrcToken.toLowerCase()
             + (aCompanionFlag ? "&c1.companion=1" : "");
    }

    /** Reader deep link opening a passage in the reader's default edition —
     *  deliberately src-less, so the column resolves per the user: saved
     *  preference, else an edition in the UI language, else NIV.
     *
     * @param aRef an edition-independent reference ({@code BOOK.chapter} or
     *             {@code BOOK.chapter.verse}, per {@code docs/link-format.md})
     * @return a root-relative {@code /reader?…} URL
     */
    private static String passageHref(final String aRef) {
        return "/reader?c1.ref=" + aRef;
    }

    /** Reader deep link demonstrating a display mode on John 1 — the same
     *  passage for every mode card, so flipping between them compares like
     *  with like. Src-less like {@link #passageHref(String)}.
     *
     * @param aModeToken a mode token ({@code original} {@code continuous}
     *                   {@code chapters} {@code verses} {@code titles})
     * @return a root-relative {@code /reader?…} URL
     */
    private static String modeHref(final String aModeToken) {
        return "/reader?c1.ref=JHN.1&c1.mode=" + aModeToken;
    }

    /** Add a linked text card whose reader source token is {@code anAbbr}
     *  lowercased — correct wherever the displayed abbreviation IS the corpus
     *  abbreviation (Bibles, Qur'an editions, LDS works). */
    private void addTextCard(final Div aContainer
                             , final String anAbbr
                             , final String aName
                             , final String aLanguage
                             , final String aYear
                             , final String aLicense
                             , final String aStatus) {
        addTextCard(aContainer, anAbbr, aName, aLanguage, aYear, aLicense, aStatus
                    , readerHref(anAbbr, false));
    }

    /** Add a text card wrapped in an anchor to {@code aReaderHref}, so the whole
     *  card opens the reader on its source. The hadith cards pass an explicit
     *  href: their displayed abbreviation ("BUK") is a collection label, while
     *  the corpus abbreviations are per-edition ("BUK-AR"/"BUK-EN") — they link
     *  to the Arabic base with the English companion shown beneath each hadith. */
    private void addTextCard(final Div aContainer
                             , final String anAbbr
                             , final String aName
                             , final String aLanguage
                             , final String aYear
                             , final String aLicense
                             , final String aStatus
                             , final String aReaderHref) {
        final Div card = new Div();
        card.getStyle()
            .set("background", "white").set("border-radius", "6px")
            .set("padding", "14px 16px").set("border", "1px solid #e0e8f0")
            .set("display", "flex").set("align-items", "center").set("gap", "12px");

        // Looked up here rather than at the corner-affordance block below,
        // because the badge's own indent depends on whether the info corner
        // will be drawn over it. See the margin-left just below.
        final EditionInfo editionInfo = EditionInfo.PAGES.get(anAbbr);

        final Span abbrSpan = new Span(anAbbr);
        abbrSpan.getStyle()
            .set("background", "#1a3a5c").set("color", "white")
            .set("font-weight", "700").set("font-size", "13px")
            .set("padding", "4px 10px").set("border-radius", "4px").set("white-space", "nowrap");
        // The info corner is an absolute overlay pinned to the card's top-left,
        // which is exactly where this badge sits: measured, the corner runs to
        // x=38 inside the card and the badge started at x=17, so the two drew
        // on top of each other by 21px. (The download corner opposite clears
        // the status glyph by 0.4px, which is why only this side showed it.)
        // Indent the badge past the corner rather than move the corner: the
        // corner's position is half of a pair, and moving it would put two
        // chips in one corner. Indenting the badge also leaves the title and
        // meta column aligned with cards that have no info page, so the grid
        // does not go ragged down the column that is actually read. 26px puts
        // the badge at x=43 — a 5px gap, and constant whatever the
        // abbreviation's length, since the badge is not what is positioned.
        if (editionInfo != null) abbrSpan.getStyle().set("margin-left", "26px");

        final Div info = new Div();
        info.getStyle().set("flex-grow", "1");
        final Span nameSpan = new Span(aName);
        nameSpan.getStyle().set("font-weight", "600").set("font-size", "14px")
                .set("color", "#1a3a5c").set("display", "block");
        final Span meta = new Span(aLanguage + (aYear.equals("—") ? "" : " · " + aYear) + " · " + aLicense);
        meta.getStyle().set("font-size", "12px").set("color", "#888");
        info.add(nameSpan, meta);

        final Span statusSpan = new Span(aStatus);
        statusSpan.getStyle().set("font-size", "16px");

        card.add(abbrSpan, info, statusSpan);

        // The whole card is the link. A plain Anchor (not a RouterLink) is right
        // here: the target carries query params and a full navigation into the
        // reader is exactly what a shareable /reader?… URL does anyway.
        final Anchor open = new Anchor(aReaderHref, card);
        open.getElement().setAttribute("title", t("about.texts.openInReader"));
        open.addClassName("text-card-link");
        open.getStyle()
            .set("text-decoration", "none").set("color", "inherit")
            .set("display", "flex");            // stretch the card to the grid cell
        card.getStyle().set("flex-grow", "1");

        // A download link or an info-page link INSIDE the card anchor would be a
        // nested <a> — invalid, and browsers unnest it in ways that break both
        // links. So the card and any corner affordances sit as siblings in a
        // positioned wrapper: click the card to read, click a corner for the file
        // or the edition's bibliographic info.
        final boolean isPublicDomain = t("about.license.publicDomain").equals(aLicense);
        if (isPublicDomain || editionInfo != null) {
            final Div wrapper = new Div();
            wrapper.getStyle()
                .set("position", "relative").set("display", "flex")
                // The wrapper is the grid item now, so it stretches to the whole
                // cell. The anchor must stretch with it: left to size itself, a
                // short card is narrower and shorter than its cell, and a corner
                // — positioned against the WRAPPER — drifts out into the gap.
                .set("width", "100%").set("align-items", "stretch");
            open.getStyle().set("flex", "1 1 auto");
            wrapper.add(open);
            if (editionInfo != null) wrapper.add(infoCorner(anAbbr));
            aContainer.add(wrapper);
        } else {
            aContainer.add(open);
        }
    }

    /**
     * The "about this edition" info-page affordance, shown only on cards for
     * which {@link EditionInfo#PAGES} has an entry — most cards show nothing
     * extra, exactly as before this feature existed. Positioned opposite the
     * download corner (top-LEFT vs. top-right) so the two never collide on a
     * public-domain edition that also has an info page.
     *
     * <p>Links to the page in the CURRENT UI language when that translation
     * exists, and to the English one when it does not — see
     * {@link EditionInfo#slugFor}, which owns that fallback. Sending a Finnish
     * visitor to an English article is a shame; sending them to a 404 because
     * their language's entry is not written yet is a bug, and the icon is only
     * shown at all when SOME entry exists.
     *
     * @param anAbbr the edition abbreviation, used as the {@link EditionInfo}
     *               map key and the {@code /edition/:abbr} route parameter
     * @return an anchor to that edition's info page
     */
    private Anchor infoCorner(final String anAbbr) {
        final String slug =
            EditionInfo.slugFor(anAbbr, LocaleUtil.currentLocale().getLanguage());
        final Anchor info = new Anchor(EditionInfo.PREFIX + slug, "\u2139");
        info.getElement().setAttribute("title", t("about.texts.editionInfo"));
        info.getElement().setAttribute("aria-label", t("about.texts.editionInfo"));
        info.getStyle()
            .set("position", "absolute").set("top", "7px").set("left", "8px")
            .set("font-size", "13px").set("font-weight", "700")
            .set("line-height", "1")
            .set("padding", "4px 7px").set("border-radius", "5px")
            // Same visual weight as the download corner, so neither reads as
            // more or less important than the other.
            .set("color", "#3f6285").set("text-decoration", "none")
            .set("background", "#f2f6fa");
        info.addClassName("text-card-info");
        return info;
    }

    // ── Full-text search ───────────────────────────────────

    private Div buildSearchSection() {
        final Div s = section("white");
        s.add(sectionTitle(t("about.search.title")));
        s.add(prose(t("about.search.body")));
        return s;
    }

    // ── Sources & Attribution ─────────────────────────────────────────

    private Div buildSourcesSection() {
        final Div s = section("white");
        s.add(sectionTitle(t("about.sources.title")));

        final Paragraph intro = new Paragraph(t("about.sources.intro"));
        intro.getStyle().set("color", "#555").set("margin", "12px 0 24px")
             .set("max-width", "680px").set("line-height", "1.7");
        s.add(intro);

        final Div sources = new Div();
        sources.getStyle()
            .set("display", "flex").set("flex-direction", "column")
            .set("gap", "12px").set("max-width", "720px");

        // Provider names and URLs are proper nouns — left as-is; only the row label is localised.
        addSourceRow(sources, t("about.sources.row.bible"),    "wldeh/bible-api",                     "https://github.com/wldeh/bible-api");
        addSourceRow(sources, t("about.sources.row.ebible"),   "eBible.org (USFM bundles)",            "https://ebible.org");
        addSourceRow(sources, t("about.sources.row.osis"),     "gratis-bible/bible (OSIS)",            "https://github.com/gratis-bible/bible");
        addSourceRow(sources, t("about.sources.row.hebrew"),   "tanach.us (Unicode/XML WLC)",          "https://www.tanach.us");
        addSourceRow(sources, t("about.sources.row.greek"),    "getbible.net (NT Textus Receptus)",    "https://getbible.net");
        addSourceRow(sources, t("about.sources.row.kotus"),    "Kotus VKS corpus (kaino.kotus.fi)",    "https://kaino.kotus.fi");
        addSourceRow(sources, t("about.sources.row.licensed"), "API.Bible by American Bible Society",  "https://scripture.api.bible");
        addSourceRow(sources, t("about.sources.row.quran"),    "alquran.cloud (Tanzil Uthmani text)",  "https://alquran.cloud");
        addSourceRow(sources, t("about.sources.row.hadith"),   "fawazahmed0/hadith-api",               "https://github.com/fawazahmed0/hadith-api");
        addSourceRow(sources, t("about.sources.row.lds"),      "bcbooks/scriptures-json",              "https://github.com/bcbooks/scriptures-json");

        s.add(sources);

        final Paragraph note = new Paragraph(t("about.sources.note"));
        note.getStyle()
            .set("color", "#888").set("font-size", "13px")
            .set("margin-top", "24px").set("max-width", "680px").set("line-height", "1.6")
            .set("border-left", "3px solid #e0e8f0").set("padding-left", "12px");
        s.add(note);

        // The attribution note above ends on "public domain texts may be freely used
        // and shared". This is where that stops being a sentence and becomes a link.
        final H3 downloadHeader = new H3(t("about.sources.downloadTitle"));
        downloadHeader.getStyle().set("color", "#1a3a5c").set("margin", "28px 0 10px 0")
            .set("font-size", "17px");
        s.add(downloadHeader);

        final Paragraph downloadNote = new Paragraph(t("about.sources.download"));
        downloadNote.getStyle().set("font-size", "14px").set("color", "#444")
            .set("line-height", "1.8").set("margin", "0 0 10px 0").set("max-width", "720px");
        s.add(downloadNote);

        final Anchor downloadLink = new Anchor("/api/docs#downloads", "common-root.org/api/docs");
        downloadLink.getStyle().set("font-family", "ui-monospace, SFMono-Regular, Menlo, monospace")
            .set("font-size", "13px").set("color", "#1a5c8a");
        s.add(downloadLink);

        return s;
    }

    private void addSourceRow(final Div aContainer, final String aLabel,
                              final String aSource, final String aUrl) {
        final Div row = new Div();
        row.getStyle()
            .set("display", "flex").set("align-items", "center")
            .set("gap", "16px").set("padding", "12px 16px")
            .set("background", "#f8fafc").set("border-radius", "6px");

        final Span labelSpan = new Span(aLabel);
        labelSpan.getStyle()
            .set("font-size", "14px").set("color", "#333")
            .set("font-weight", "500").set("flex-grow", "1");

        final Anchor link = new Anchor(aUrl, aSource);
        link.setTarget("_blank");
        link.getStyle()
            .set("font-size", "13px").set("color", "#2e6da4")
            .set("text-decoration", "none").set("white-space", "nowrap");

        row.add(labelSpan, link);
        aContainer.add(row);
    }

    // ── Comments CTA ──────────────────────────────────────────────────

    private Div buildCommentsSection() {
        final Div s = section("#f0f4f8");

        final Div inner = new Div();
        inner.getStyle()
            .set("background", "linear-gradient(135deg, #1a3a5c, #2e6da4)")
            .set("border-radius", "12px").set("padding", "40px 48px")
            .set("display", "flex").set("align-items", "center")
            .set("gap", "32px").set("flex-wrap", "wrap");

        final Div text = new Div();
        text.getStyle().set("flex-grow", "1");

        final H2 title = new H2(t("about.comments.title"));
        title.getStyle()
            .set("color", "white").set("font-size", "22px")
            .set("font-weight", "700").set("margin", "0 0 10px 0");

        final Paragraph body = new Paragraph(t("about.comments.body"));
        body.getStyle()
            .set("color", "rgba(255,255,255,0.85)").set("margin", "0")
            .set("font-size", "14px").set("line-height", "1.6").set("max-width", "480px");

        text.add(title, body);

        final Div buttons = new Div();
        buttons.getStyle().set("display", "flex").set("gap", "12px").set("flex-wrap", "wrap");

        final Anchor register = new Anchor("/register", t("about.comments.register"));
        register.getStyle()
            .set("display", "inline-block").set("background", "#c9a84c").set("color", "#1a3a5c")
            .set("font-weight", "700").set("font-size", "14px")
            .set("padding", "10px 22px").set("border-radius", "4px")
            .set("text-decoration", "none").set("white-space", "nowrap");

        final Anchor login = new Anchor("/login", t("about.comments.signIn"));
        login.getStyle()
            .set("display", "inline-block").set("background", "transparent").set("color", "white")
            .set("font-weight", "600").set("font-size", "14px")
            .set("padding", "10px 22px").set("border-radius", "4px")
            .set("text-decoration", "none").set("white-space", "nowrap")
            .set("border", "1px solid rgba(255,255,255,0.4)");

        buttons.add(register, login);
        inner.add(text, buttons);
        s.add(inner);
        return s;
    }

    // ── About the project ─────────────────────────────────────────────

    private Div buildAboutSection() {
        final Div s = section("white");
        s.add(sectionTitle(t("about.about.title")));

        s.add(prose(t("about.about.p1")));
        s.add(prose(t("about.about.p2")));
        s.add(prose(t("about.about.p3")));

        s.add(buildSupportCallout());
        s.add(buildDocsDownload());

        return s;
    }

    // ── Documentation downloads ─────────────────────────────

    // Served by the app itself from the jar (META-INF/resources/docs — see the
    // docs <resource> in app/pom.xml + /docs/** permitAll in SecurityConfig).
    // Previously linked raw.githubusercontent.com, which 404s for visitors now
    // that the repo is private. Filenames keep the historical religious-texts-
    // prefix (see docs/README.md).
    private static final String DOCS_BASE = "/docs/";

    private Div buildDocsDownload() {
        final Div wrap = new Div();
        wrap.getStyle().set("margin-top", "20px").set("max-width", "760px");

        final Paragraph intro = new Paragraph(t("about.docs.intro"));
        intro.getStyle()
            .set("font-size", "14px").set("color", "#555")
            .set("font-weight", "600").set("margin", "0 0 10px 0");
        wrap.add(intro);

        final Div links = new Div();
        links.getStyle().set("display", "flex").set("gap", "12px").set("flex-wrap", "wrap");
        // Link the overview PDF for the active locale, falling back to English
        // when that locale has no bundled overview. The technical doc is
        // developer-facing and kept in English only.
        final String lang = LocaleUtil.currentLocale().getLanguage();
        // Fall back to the English overview when this locale ships no bundled PDF.
        // HE, HI and TR are UI locales without an overview doc (see CLAUDE_NOTES);
        // without this check their "Overview" link 404s.
        final String localizedOverview = "religious-texts-overview." + lang + ".pdf";
        final boolean hasLocalizedOverview = !"en".equals(lang)
            && getClass().getResource("/META-INF/resources/docs/" + localizedOverview) != null;
        addDocLink(links, t("about.docs.overview"),
            hasLocalizedOverview ? localizedOverview : "religious-texts-overview.pdf");
        addDocLink(links, t("about.docs.technical"), "religious-texts-technical.pdf");
        // The Javadoc API reference — served from the jar at /docs/api (generated into the
        // built jar by the maven-javadoc-plugin execution in app/pom.xml). HTML, not a PDF.
        addApiRefLink(links, t("about.docs.api"));
        wrap.add(links);

        return wrap;
    }

    private void addDocLink(final Div aContainer, final String aLabel, final String aFile) {
        aContainer.add(docButton(DOCS_BASE + aFile, "↓ " + aLabel + " (PDF)"));
    }

    private void addApiRefLink(final Div aContainer, final String aLabel) {
        aContainer.add(docButton(DOCS_BASE + "api/index.html", "↳ " + aLabel + " (HTML)"));
    }

    /** A doc-download / doc-reference button anchor (shared style), opening in a new tab. */
    private Anchor docButton(final String anHref, final String aText) {
        final Anchor a = new Anchor(anHref, aText);
        a.setTarget("_blank");
        a.getElement().setAttribute("rel", "noopener");
        a.getStyle()
            .set("display", "inline-block").set("background", "white")
            .set("border", "1px solid #1a3a5c").set("color", "#1a3a5c")
            .set("font-weight", "600").set("font-size", "13px")
            .set("padding", "8px 18px").set("border-radius", "6px")
            .set("text-decoration", "none");
        return a;
    }

    // ── Support / Sponsor callout ─────────────────────────────────────

    private static final String SPONSOR_URL = "https://github.com/sponsors/christa-claw";

    private Div buildSupportCallout() {
        final Div callout = new Div();
        callout.getStyle()
            .set("background", "#f8fafc").set("border", "1px solid #e0e8f0")
            .set("border-radius", "10px").set("padding", "28px 32px")
            .set("margin-top", "28px").set("max-width", "760px")
            .set("display", "flex").set("align-items", "center")
            .set("gap", "28px").set("flex-wrap", "wrap");

        final Div text = new Div();
        text.getStyle().set("flex", "1").set("min-width", "240px");

        final H3 title = new H3(t("about.support.title"));
        title.getStyle()
            .set("font-size", "18px").set("font-weight", "700")
            .set("color", "#1a3a5c").set("margin", "0 0 8px 0");

        final Paragraph body = new Paragraph(t("about.support.body"));
        body.getStyle()
            .set("font-size", "14px").set("color", "#555")
            .set("line-height", "1.7").set("margin", "0");

        text.add(title, body);

        final Anchor sponsor = new Anchor(SPONSOR_URL, t("about.support.cta"));
        sponsor.setTarget("_blank");
        sponsor.getElement().setAttribute("rel", "noopener");
        sponsor.getStyle()
            .set("display", "inline-block").set("background", "#1a3a5c").set("color", "white")
            .set("font-weight", "700").set("font-size", "14px")
            .set("padding", "12px 24px").set("border-radius", "6px")
            .set("text-decoration", "none").set("white-space", "nowrap");

        callout.add(text, sponsor);
        return callout;
    }

    // ── Footer ────────────────────────────────────────────────────────

    private Div buildFooter() {
        final Div footer = new Div();
        footer.setWidthFull();
        footer.getStyle()
            .set("background", "#1a3a5c").set("padding", "24px 10%")
            .set("display", "flex").set("align-items", "center")
            .set("justify-content", "space-between").set("flex-wrap", "wrap").set("gap", "12px");

        final Span copy = new Span(t("about.footer.copyright"));
        copy.getStyle().set("color", "rgba(255,255,255,0.6)").set("font-size", "13px");

        final Div links = new Div();
        links.getStyle().set("display", "flex").set("gap", "20px");
        for (final String[] link : new String[][]{
                {t("about.footer.openReader"), "/reader"},
                {t("about.footer.aboutHelp"), "/"},
                {t("about.footer.sponsor"), SPONSOR_URL}}) {
            final Anchor a = new Anchor(link[1], link[0]);
            a.getStyle().set("color", "rgba(255,255,255,0.7)").set("font-size", "13px")
             .set("text-decoration", "none");
            if (link[1].startsWith("http")) {
                a.setTarget("_blank");
                a.getElement().setAttribute("rel", "noopener");
            }
            links.add(a);
        }

        footer.add(copy, links);
        return footer;
    }
}
