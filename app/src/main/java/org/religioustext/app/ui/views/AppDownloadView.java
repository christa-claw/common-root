// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.OrderedList;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.HasDynamicTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import org.religioustext.app.i18n.LocaleUtil;

import java.util.Optional;

/**
 * {@code /app}: the Common Root? Android app, offered as an APK to install
 * straight from the site, with the steps to do it.
 *
 * <p>The app is not on Google Play yet, so this page is the whole of its
 * distribution: what it is, the download, how to get past Android's
 * "unknown apps" prompt, how updates work (they don't, yet: install the new
 * one over the old), the checksum, and what the app does and does not collect.
 * The file and its numbers come from {@link AndroidRelease}; without a release
 * the page says the download is being prepared instead of offering a dead link.
 *
 * <p>A visitor on a computer or an iPhone gets the same page, headed by a line
 * telling them to open it on their Android phone, since an APK downloaded to a
 * laptop is no use to anyone.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
@Route("app")
@AnonymousAllowed
public class AppDownloadView extends VerticalLayout implements HasDynamicTitle {

    /** The address this page is reached at, as a reader would type it. */
    static final String SHORT_URL = "common-root.org/app";

    private static final String NAVY = "#1a3a5c";
    private static final String GOLD = "#c9a84c";

    /**
     * @param aBuildInfo the injected build identity, for the shared header
     */
    public AppDownloadView(final BuildInfo aBuildInfo) {
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("overflow-y", "auto").set("overflow-x", "hidden");

        final Optional<AndroidRelease> release = AndroidRelease.current();
        final Anchor allTexts = new Anchor("/", t("edition.allTexts"));
        allTexts.getStyle()
            .set("font-size", "14px").set("color", "var(--lumo-secondary-text-color)")
            .set("text-decoration", "none").set("white-space", "nowrap");
        add(new SiteHeader(aBuildInfo, allTexts), hero(release), body(release));
    }

    /** {@inheritDoc} */
    @Override
    public String getPageTitle() {
        return t("app.title");
    }

    // ── Hero: what it is, and the download ────────────────────────────

    private Div hero(final Optional<AndroidRelease> aRelease) {
        final Div hero = new Div();
        hero.setWidthFull();
        hero.getStyle()
            .set("background", "linear-gradient(135deg, " + NAVY + " 0%, #2e6da4 100%)")
            .set("padding", "48px 6% 44px").set("text-align", "center")
            .set("box-sizing", "border-box");

        final H1 title = new H1(t("app.title"));
        title.getStyle()
            .set("color", "white").set("font-size", "clamp(24px, 4vw, 38px)")
            .set("font-weight", "700").set("margin", "0 0 14px 0").set("line-height", "1.2");

        final Paragraph lead = new Paragraph(t("app.lead"));
        lead.getStyle()
            .set("color", "rgba(255,255,255,0.88)").set("font-size", "clamp(15px, 2vw, 18px)")
            .set("max-width", "640px").set("margin", "0 auto 28px").set("line-height", "1.6");
        hero.add(title, lead);

        if (!onAndroid()) {
            final Paragraph elsewhere = new Paragraph(t("app.notAndroid", SHORT_URL));
            elsewhere.getStyle()
                .set("color", "white").set("background", "rgba(255,255,255,0.12)")
                .set("border", "1px solid rgba(255,255,255,0.25)").set("border-radius", "8px")
                .set("padding", "12px 18px").set("max-width", "560px")
                .set("margin", "0 auto 24px").set("font-size", "15px");
            hero.add(elsewhere);
        }

        if (aRelease.isPresent()) {
            final AndroidRelease r = aRelease.get();
            final Anchor download = new Anchor(r.href(), "↓ " + t("app.download"));
            download.getElement().setAttribute("download", r.file());
            download.getElement().setAttribute("data-umami-event", "app-download");
            download.getElement().setAttribute("data-umami-event-version", r.version());
            download.getStyle()
                .set("display", "inline-block").set("background", GOLD).set("color", NAVY)
                .set("font-weight", "700").set("font-size", "18px")
                .set("padding", "14px 34px").set("border-radius", "6px").set("text-decoration", "none");
            final Paragraph meta = new Paragraph(t("app.download.meta",
                r.version(), Long.toString(r.megabytes()), r.minAndroid()));
            meta.getStyle()
                .set("color", "rgba(255,255,255,0.75)").set("font-size", "13px").set("margin", "12px 0 0 0");
            hero.add(download, meta);
        } else {
            final Paragraph soon = new Paragraph(t("app.unavailable"));
            soon.getStyle().set("color", "white").set("font-weight", "600").set("margin", "0");
            hero.add(soon);
        }
        return hero;
    }

    // ── Body: install, updates, checksum, privacy ─────────────────────

    private Div body(final Optional<AndroidRelease> aRelease) {
        final Div s = new Div();
        s.setWidthFull();
        s.getStyle().set("padding", "40px 6% 56px").set("box-sizing", "border-box")
                    .set("max-width", "820px").set("margin", "0 auto");

        s.add(prose(t("app.notOnPlay")));

        s.add(heading(t("app.install.title")));
        final OrderedList steps = new OrderedList();
        steps.getStyle().set("padding-inline-start", "22px").set("margin", "0 0 8px 0");
        for (int i = 1; i <= 5; i++) {
            final ListItem li = new ListItem(t("app.install." + i));
            li.getStyle().set("color", "#333").set("line-height", "1.7")
                .set("font-size", "15px").set("margin-bottom", "8px");
            steps.add(li);
        }
        s.add(steps);

        s.add(heading(t("app.update.title")), prose(t("app.update.body")));

        if (aRelease.isPresent() && !aRelease.get().sha256().isEmpty()) {
            final Span sum = new Span(aRelease.get().sha256());
            sum.getStyle()
                .set("font-family", "ui-monospace, Menlo, monospace").set("font-size", "13px")
                .set("background", "#f0f4f8").set("padding", "8px 12px").set("border-radius", "6px")
                .set("display", "block").set("overflow-wrap", "anywhere").set("color", NAVY);
            s.add(heading(t("app.verify.title")), prose(t("app.verify.body")), sum);
        }

        s.add(heading(t("app.privacy.title")), prose(t("app.privacy.body")));
        s.add(heading(t("app.iphone.title")), prose(t("app.iphone.body")));
        return s;
    }

    private static H2 heading(final String aText) {
        final H2 h = new H2(aText);
        h.getStyle().set("font-size", "20px").set("color", NAVY)
            .set("margin", "28px 0 10px 0").set("font-weight", "700");
        return h;
    }

    private static Paragraph prose(final String aText) {
        final Paragraph p = new Paragraph(aText);
        p.getStyle().set("color", "#444").set("line-height", "1.75")
            .set("font-size", "15px").set("margin", "0 0 12px 0");
        return p;
    }

    /**
     * Whether the visitor's browser says it runs on Android. Read from this
     * request rather than {@code VaadinSession.getBrowser()}, which keeps the
     * browser of the request that opened the session.
     *
     * @return true for an Android user agent
     */
    private static boolean onAndroid() {
        final VaadinRequest request = VaadinRequest.getCurrent();
        final String agent = request == null ? null : request.getHeader("User-Agent");
        return agent != null && agent.contains("Android");
    }

    private String t(final String aKey, final Object... theParams) {
        return getTranslation(aKey, LocaleUtil.currentLocale(), theParams);
    }
}
