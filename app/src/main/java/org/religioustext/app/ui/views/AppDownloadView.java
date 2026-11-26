// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Image;
import com.vaadin.flow.component.html.ListItem;
import com.vaadin.flow.component.html.NativeButton;
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
 * one over the old), screenshots of the app itself, the checksum, and what the
 * app does and does not collect.
 * The file and its numbers come from {@link AndroidRelease}; without a release
 * the page says the download is being prepared instead of offering a dead link.
 *
 * <p>A visitor on a computer or an iPhone gets the same page, headed by a line
 * telling them to open it on their Android phone, since an APK downloaded to a
 * laptop is no use to anyone.
 *
 * @author Christa Claw
 * @version 1.0.4-SNAPSHOT
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
        add(new SiteHeader(aBuildInfo, allTexts), hero(release), install(), body(release));
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

        if (!onAndroid()) hero.add(elsewhere());

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

    /**
     * For a visitor on a computer (or an iPhone): open this page on an Android phone, by
     * typing {@value #SHORT_URL} or scanning the QR code beside it. The code leads to this
     * page, not to the APK, so the phone also gets the install steps, and it never goes
     * stale when a new version is released ({@code /images/app-qr.svg}, made with the
     * app's own {@code toqr}).
     *
     * @return the box
     */
    private Div elsewhere() {
        final Paragraph text = new Paragraph(t("app.notAndroid", SHORT_URL));
        text.getStyle().set("margin", "0 0 6px 0");
        final Span hint = new Span(t("app.qr.hint"));
        hint.getStyle().set("font-size", "13px").set("color", "rgba(255,255,255,0.8)");
        final Div words = new Div(text, hint);
        words.getStyle().set("flex", "1 1 220px").set("text-align", "left");

        final Image code = new Image("images/app-qr.svg", t("app.qr.alt", SHORT_URL));
        code.setWidth("132px");
        code.setHeight("132px");
        code.getStyle()
            .set("flex", "0 0 auto").set("background", "white").set("padding", "6px")
            .set("border-radius", "6px").set("box-sizing", "content-box");

        final Div box = new Div(words, code);
        box.getStyle()
            .set("display", "flex").set("flex-wrap", "wrap").set("align-items", "center")
            .set("justify-content", "center").set("gap", "18px")
            .set("color", "white").set("background", "rgba(255,255,255,0.12)")
            .set("border", "1px solid rgba(255,255,255,0.25)").set("border-radius", "8px")
            .set("padding", "14px 18px").set("max-width", "560px")
            .set("margin", "0 auto 24px").set("font-size", "15px").set("box-sizing", "border-box");
        return box;
    }

    // ── Body: install, screenshots, updates, checksum, privacy ────────

    /** The text column the page's sections sit in. */
    private static Div column(final String aPadding) {
        final Div s = new Div();
        s.setWidthFull();
        s.getStyle().set("padding", aPadding).set("box-sizing", "border-box")
                    .set("max-width", "820px").set("margin", "0 auto");
        return s;
    }

    private Div install() {
        final Div s = column("40px 6% 0");
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
        // 280 + 24 + the carousel's 340 fit the column on a computer, so they sit side by side
        steps.getStyle().set("flex", "1 1 280px").set("min-width", "0");

        final Div side = new Div(steps, carousel());
        side.getStyle()
            .set("display", "flex").set("flex-wrap", "wrap").set("gap", "24px")
            .set("align-items", "flex-start").set("justify-content", "center");
        s.add(side);
        return s;
    }

    /**
     * The screenshots, in the order a reader meets them: file stem, caption key.
     * The number in the stem is that order, so the folder lists them as shown.
     */
    private static final String[][] SHOTS = {
        {"1-app-reader", "app.shots.reader"},
        {"2-app-verse-notes", "app.shots.notes"},
        {"3-app-verse-comments", "app.shots.comments"},
        {"4-app-verse-compare", "app.shots.compare"},
        {"5-app-verse-cross-ref", "app.shots.crossRef"},
        {"6-app-verse-strongs", "app.shots.words"},
        {"7-app-verse-view", "app.shots.view"},
        {"8-app-verse-reader", "app.shots.parallel"},
    };

    /** Width of the picture in focus, in CSS pixels; the stage leaves room for its neighbours. */
    private static final int SHOT_WIDTH = 190;
    /** Width of the whole stage: the picture in focus and the two either side of it. */
    private static final int STAGE_WIDTH = 340;
    /** Height of the picture in focus: the screenshots are 480 × 1041. */
    private static final int SHOT_HEIGHT = Math.round(SHOT_WIDTH * 1041f / 480f);

    /**
     * What the app looks like: screenshots from a real phone
     * ({@code /images/app/<n>-app-*.webp}, 480 px wide, about 280 KB together) in a
     * cover-flow carousel. The picture in focus stands in the middle at full
     * size; the ones beside it sit smaller and behind it, and the outer two
     * smaller and fainter still, so five are in view at once and the row
     * wraps round. Tapping a side picture brings it to the middle; tapping the
     * middle one opens it full size. Under the stage, the caption of the
     * picture in focus, then ‹ › buttons and dots.
     *
     * <p>It sits beside the install steps on a computer and wraps under them
     * on a phone. The pictures are stacked on one spot and placed by
     * {@link #CAROUSEL_JS}, which also takes swipes, the arrow keys and the
     * buttons. The stage is kept left-to-right in every language, so "next"
     * is always the picture on the right; the caption follows the page.
     *
     * @return the carousel
     */
    private Div carousel() {
        final Div stage = new Div();
        stage.getElement().setAttribute("data-stage", "");
        stage.getElement().setAttribute("dir", "ltr");
        stage.getElement().setAttribute("tabindex", "0");
        stage.getStyle()
            .set("position", "relative").set("width", "100%")
            .set("height", (SHOT_HEIGHT + 8) + "px").set("touch-action", "pan-y")
            .set("outline-offset", "4px");
        for (int i = 0; i < SHOTS.length; i++) {
            final String[] shot = SHOTS[i];
            final String src = "images/app/" + shot[0] + ".webp";
            final String caption = t(shot[1]);
            final Image img = new Image(src, caption);
            img.getElement().setAttribute("width", "480");
            img.getElement().setAttribute("height", "1041");
            img.getElement().setAttribute("draggable", "false");
            img.getStyle()
                .set("width", "100%").set("height", "auto").set("display", "block")
                .set("border-radius", "14px").set("border", "1px solid #d8dee6");
            final Anchor slide = new Anchor(src, img);
            slide.setTarget("_blank");
            slide.getElement().setAttribute("data-slide", "");
            slide.getElement().setAttribute("data-caption", caption);
            slide.getElement().setAttribute("aria-label", caption);
            slide.getStyle()
                .set("position", "absolute").set("top", "4px").set("left", "50%")
                .set("width", SHOT_WIDTH + "px").set("display", "block")
                .set("transform", "translateX(-50%)").set("opacity", i == 0 ? "1" : "0")
                .set("transition", "transform .45s cubic-bezier(.2,.7,.2,1), opacity .45s, filter .45s, box-shadow .45s")
                .set("border-radius", "14px");
            stage.add(slide);
        }

        final Span caption = new Span(t(SHOTS[0][1]));
        caption.getElement().setAttribute("data-caption-out", "");
        caption.getElement().setAttribute("aria-live", "polite");
        caption.getStyle()
            .set("display", "block").set("font-size", "14px").set("line-height", "1.45")
            .set("color", "#444").set("margin-top", "12px").set("text-align", "center")
            .set("min-height", "2.9em");

        final Div dots = new Div();
        dots.getStyle().set("display", "flex").set("gap", "8px").set("align-items", "center");
        for (int i = 0; i < SHOTS.length; i++) {
            final NativeButton dot = new NativeButton();
            dot.getElement().setAttribute("data-dot", "");
            dot.getElement().setAttribute("aria-label", (i + 1) + " / " + SHOTS.length);
            dot.getStyle()
                .set("width", "9px").set("height", "9px").set("padding", "0")
                .set("border", "none").set("border-radius", "50%").set("cursor", "pointer")
                .set("background", i == 0 ? NAVY : "#c9d3de");
            dots.add(dot);
        }
        final Div controls = new Div(arrow("‹", "data-prev", t("app.shots.prev")), dots,
                                     arrow("›", "data-next", t("app.shots.next")));
        controls.getElement().setAttribute("dir", "ltr");
        controls.getStyle()
            .set("display", "flex").set("align-items", "center").set("justify-content", "center")
            .set("gap", "14px").set("margin-top", "6px");

        final Div box = new Div(stage, caption, controls);
        box.getElement().setAttribute("role", "region");
        box.getElement().setAttribute("aria-roledescription", "carousel");
        box.getElement().setAttribute("aria-label", t("app.shots.title"));
        // overflow hidden: the outer pictures may lean past the edge on a narrow phone
        // min-width 0: a flex item is otherwise as wide as its widest picture (480 px)
        box.getStyle().set("flex", "0 0 " + STAGE_WIDTH + "px").set("width", STAGE_WIDTH + "px")
            .set("min-width", "0").set("max-width", "100%").set("overflow", "hidden");
        box.addAttachListener(e -> box.getElement().executeJs(CAROUSEL_JS));
        return box;
    }

    private static NativeButton arrow(final String aGlyph, final String aMarker, final String aLabel) {
        final NativeButton b = new NativeButton(aGlyph);
        b.getElement().setAttribute(aMarker, "");
        b.getElement().setAttribute("aria-label", aLabel);
        b.getStyle()
            .set("width", "34px").set("height", "34px").set("border-radius", "50%")
            .set("border", "1px solid #c9d3de").set("background", "white").set("color", NAVY)
            .set("font-size", "22px").set("line-height", "1").set("cursor", "pointer")
            .set("padding", "0 0 3px 0");
        return b;
    }

    /**
     * The cover flow. {@code $0} is the carousel. Each picture is placed by its
     * distance from the one in focus, counted the short way round (so with five
     * pictures, two either side): the middle one full size and in front, the
     * next ones smaller and behind it, the outer ones smaller and fainter. The
     * offsets are fractions of the stage, so it narrows with a phone's screen.
     * A tap on a side picture brings it to the middle instead of opening it.
     * Anyone who asks their system for less motion gets the same layout
     * without the animation.
     */
    private static final String CAROUSEL_JS = """
        const box = $0, stage = box.querySelector('[data-stage]');
        const slides = [...box.querySelectorAll('[data-slide]')];
        const dots = [...box.querySelectorAll('[data-dot]')];
        const out = box.querySelector('[data-caption-out]');
        const n = slides.length;
        let cur = 0;
        if (matchMedia('(prefers-reduced-motion: reduce)').matches) {
          slides.forEach((s) => s.style.transition = 'none');
        }
        const place = () => {
          const w = stage.clientWidth;
          slides.forEach((s, i) => {
            let d = ((i - cur) %% n + n) %% n;
            if (d > n / 2) d -= n;
            const a = Math.abs(d);
            const x = d === 0 ? 0 : Math.sign(d) * (a === 1 ? w * 0.27 : w * 0.40);
            const scale = a === 0 ? 1 : a === 1 ? 0.78 : 0.6;
            s.style.transform = `translateX(-50%%) translateX(${x}px) scale(${scale})`;
            s.style.zIndex = String(10 - a);
            s.style.opacity = a > 2 ? '0' : a === 2 ? '0.55' : '1';
            s.style.filter = a === 0 ? 'none' : `brightness(${a === 1 ? 0.92 : 0.85})`;
            s.style.boxShadow = a === 0 ? '0 10px 28px rgba(26,58,92,0.28)' : '0 3px 10px rgba(26,58,92,0.15)';
            s.style.cursor = a === 0 ? 'zoom-in' : 'pointer';
            s.tabIndex = a === 0 ? 0 : -1;
            s.setAttribute('aria-hidden', a === 0 ? 'false' : 'true');
          });
          dots.forEach((dot, i) => {
            dot.style.background = i === cur ? '%s' : '#c9d3de';
            dot.setAttribute('aria-current', i === cur ? 'true' : 'false');
          });
          out.textContent = slides[cur].dataset.caption;
        };
        const go = (i) => { cur = ((i %% n) + n) %% n; place(); };
        slides.forEach((s, i) => s.addEventListener('click', (e) => {
          if (moved || i !== cur) { e.preventDefault(); if (!moved) go(i); }
        }));
        box.querySelector('[data-prev]').onclick = () => go(cur - 1);
        box.querySelector('[data-next]').onclick = () => go(cur + 1);
        dots.forEach((dot, i) => dot.onclick = () => go(i));
        stage.addEventListener('keydown', (e) => {
          if (e.key === 'ArrowLeft') { go(cur - 1); e.preventDefault(); }
          if (e.key === 'ArrowRight') { go(cur + 1); e.preventDefault(); }
        });
        let x0 = null, moved = false;
        stage.addEventListener('pointerdown', (e) => { x0 = e.clientX; moved = false; });
        stage.addEventListener('pointermove', (e) => {
          if (x0 !== null && Math.abs(e.clientX - x0) > 10) moved = true;
        });
        stage.addEventListener('pointerup', (e) => {
          if (x0 === null) return;
          const dx = e.clientX - x0; x0 = null;
          if (Math.abs(dx) > 40) go(cur + (dx < 0 ? 1 : -1));
        });
        stage.addEventListener('pointercancel', () => { x0 = null; });
        new ResizeObserver(place).observe(stage);
        place();
        """.formatted(NAVY);

    private Div body(final Optional<AndroidRelease> aRelease) {
        final Div s = column("12px 6% 56px");
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
