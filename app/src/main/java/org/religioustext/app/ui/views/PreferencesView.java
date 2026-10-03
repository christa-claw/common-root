// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.model.DisplayOptions;
import org.religioustext.app.model.DisplayOptions.OrderMode;
import org.religioustext.app.model.user.ReaderLook;
import org.religioustext.app.model.user.UserPreferences;
import org.religioustext.app.service.CommentQueryService;
import org.religioustext.app.service.TextQueryService;
import org.religioustext.app.service.UserPreferencesService;
import org.religioustext.app.ui.views.reader.SourceCatalog;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Per-user reader defaults (see {@link UserPreferences}): the Bible edition,
 * display mode, and reading order the reader opens with when a link doesn't
 * specify its own; whether the comments panel and in-text markers start on;
 * "continue where I left off"; and the preferred UI language.
 *
 * All stored values use the ReaderLink token vocabulary — applying preferences
 * is synthesizing a link (ReaderView.applyPreferenceDefaults), and the saved
 * reading position IS a reader link's query string.
 */
@Route("preferences")
@PageTitle("Preferences — Common Root?")
@RolesAllowed("USER")
public class PreferencesView extends VerticalLayout {

    public PreferencesView(final UserPreferencesService aPrefsService,
                           final TextQueryService aQueryService,
                           final CommentQueryService aCommentService,
                           final AuthenticationContext anAuthContext,
                           final BuildInfo aBuildInfo) {

        setSizeFull();
        setAlignItems(Alignment.CENTER);
        getStyle().set("overflow-y", "auto");

        final String email = anAuthContext.getPrincipalName().orElse("");
        final UserPreferences existing = aPrefsService.find(email).orElse(null);

        final H2 title = new H2(t("prefs.title"));
        title.getStyle().set("margin", "24px 0 4px");

        final Paragraph intro = new Paragraph(t("prefs.intro"));
        intro.getStyle()
            .set("font-size", "13px")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("max-width", "420px")
            .set("text-align", "center")
            .set("margin", "0 0 16px 0");

        // ── Default Bible ─────────────────────────────────────────────
        // Bible-type primaries only (the reader substitutes this token for
        // src-less links and for opening comment references, which are USFM
        // refs — a Qur'an default couldn't resolve them).
        final SourceCatalog catalog = new SourceCatalog(aQueryService.listSources());
        final List<String[]> bibles = catalog.primaries().stream()
            .filter(r -> r.length > 6 && "bible".equals(r[6]))
            .sorted(java.util.Comparator.comparing(r -> r.length > 2 ? r[2] : ""))
            .toList();
        final ComboBox<String[]> bibleBox = new ComboBox<>(t("prefs.defaultBible"));
        bibleBox.setItems(bibles);
        bibleBox.setItemLabelGenerator(r ->
            (r.length > 2 ? r[2] : "") + " \u2014 " + (r.length > 1 ? r[1] : ""));
        bibleBox.setClearButtonVisible(true);
        bibleBox.setWidth("320px");
        bibleBox.setHelperText(t("prefs.defaultBible.helper"));
        if (existing != null && existing.getDefaultSource() != null) {
            bibles.stream()
                .filter(r -> r.length > 2 && existing.getDefaultSource()
                    .equalsIgnoreCase(r[2]))
                .findFirst().ifPresent(bibleBox::setValue);
        }

        // ── Default display mode ──────────────────────────────────────
        final Select<DisplayOptions.DisplayMode> modeSel = new Select<>();
        modeSel.setLabel(t("prefs.defaultMode"));
        modeSel.setItems(DisplayOptions.DisplayMode.values());
        modeSel.setItemLabelGenerator(m -> t("mode." + m.name()));
        modeSel.setWidth("320px");
        modeSel.setValue(modeFromPref(existing));

        // ── Default reading order ─────────────────────────────────────
        final Select<OrderMode> orderSel = new Select<>();
        orderSel.setLabel(t("prefs.defaultOrder"));
        orderSel.setItems(OrderMode.values());
        orderSel.setItemLabelGenerator(o -> t("order.full." + o.name()));   // room for the long form here
        orderSel.setWidth("320px");
        orderSel.setValue(existing == null ? OrderMode.CANONICAL
            : ReaderLink.orderFromToken(existing.getDefaultOrder()));

        // ── Reader look ───────────────────────────────────────────────
        // The typeset "book" look the print designer previews, for signed-in
        // readers; signed-out readers always get the classic reader and never
        // see this page. Null in the row means "the default" (book, rubric, on).
        final ReaderLook look = ReaderLook.of(true, existing);
        final Select<String> lookSel = new Select<>();
        lookSel.setLabel(t("prefs.readerLook"));
        lookSel.setItems(ReaderLook.BOOK, ReaderLook.CLASSIC);
        lookSel.setItemLabelGenerator(v -> t("prefs.look." + v));
        lookSel.setWidth("320px");
        lookSel.setValue(look.book() ? ReaderLook.BOOK : ReaderLook.CLASSIC);

        // The accent is a row of swatches, as on the print designer's page —
        // the colour is the choice, so it is shown rather than named in a list.
        final String[] accentChoice = { look.accent() };
        final java.util.Map<String, String> accentHex = java.util.Map.of(
            "rubric", "#9e2b20", "black", "#1a1a1a", "indigo", "#23366b",
            "sepia", "#74502c", "forest", "#1f5136");
        final Span accentLabel = new Span(t("prefs.readerAccent"));
        accentLabel.getStyle().set("font-size", "var(--lumo-font-size-s)")
            .set("color", "var(--lumo-secondary-text-color)").set("font-weight", "500");
        final Span accentName = new Span(t("prefs.accent." + accentChoice[0]));
        accentName.getStyle().set("font-size", "var(--lumo-font-size-s)")
            .set("color", "var(--lumo-body-text-color)");
        final HorizontalLayout swatches = new HorizontalLayout();
        swatches.setPadding(false);
        swatches.setSpacing(false);
        swatches.getStyle().set("gap", "10px").set("margin-top", "4px");
        swatches.getElement().setAttribute("role", "radiogroup");
        swatches.getElement().setAttribute("aria-label", t("prefs.readerAccent"));
        final java.util.Map<String, Button> swatchButtons = new java.util.LinkedHashMap<>();
        final Runnable paintSwatches = () -> swatchButtons.forEach((a, b) -> {
            final boolean on = a.equals(accentChoice[0]);
            b.getStyle().set("box-shadow", on
                ? "0 0 0 2px var(--lumo-base-color), 0 0 0 4px " + accentHex.get(a) : "none");
            b.getElement().setAttribute("aria-checked", String.valueOf(on));
        });
        for (final String a : ReaderLook.ACCENTS) {
            final Button b = new Button();
            b.getStyle().set("background", accentHex.get(a)).set("min-width", "28px")
                .set("width", "28px").set("height", "28px").set("padding", "0")
                .set("border-radius", "50%").set("margin", "0");
            b.getElement().setAttribute("role", "radio");
            b.getElement().setAttribute("aria-label", t("prefs.accent." + a));
            b.setTooltipText(t("prefs.accent." + a));
            b.addClickListener(e -> {
                accentChoice[0] = a;
                accentName.setText(t("prefs.accent." + a));
                paintSwatches.run();
            });
            swatchButtons.put(a, b);
            swatches.add(b);
        }
        paintSwatches.run();
        final VerticalLayout accentBlock = new VerticalLayout(accentLabel, swatches, accentName);
        accentBlock.setPadding(false);
        accentBlock.setSpacing(false);
        accentBlock.setWidth("320px");
        accentBlock.setAlignItems(Alignment.START);
        swatchButtons.values().forEach(b -> b.setEnabled(look.book()));
        final Checkbox titlesBox = new Checkbox(t("prefs.longTitles"), look.longTitles());
        titlesBox.setEnabled(look.book());
        lookSel.addValueChangeListener(e -> {
            final boolean book = ReaderLook.BOOK.equals(e.getValue());
            swatchButtons.values().forEach(b -> b.setEnabled(book));
            accentBlock.getStyle().set("opacity", book ? "1" : "0.5");
            titlesBox.setEnabled(book);
        });
        accentBlock.getStyle().set("opacity", look.book() ? "1" : "0.5");

        // ── Booleans ──────────────────────────────────────────────────
        final Checkbox panelBox = new Checkbox(t("prefs.showPanel"),
            existing != null && existing.isShowCommentsPanel());
        final Checkbox markersBox = new Checkbox(t("prefs.showMarkers"),
            existing != null && existing.isShowCommentMarkers());
        final Checkbox resumeBox = new Checkbox(t("prefs.resume"),
            existing == null || existing.isResumeEnabled());      // on until switched off
        resumeBox.setTooltipText(t("prefs.resume.helper"));

        // ── Current location ──────────────────────────────────────────
        // The saved position, spelled out per column ("KJV · Genesis 12:3"), with
        // a link that replays it and a way to forget it. It fills in by itself as
        // a signed-in reader reads; this is where they can see what the reader
        // will open at, and reset it when they start a new read-through.
        final VerticalLayout locationBlock = new VerticalLayout();
        locationBlock.setPadding(false);
        locationBlock.setSpacing(false);
        locationBlock.setWidth("320px");
        locationBlock.setAlignItems(Alignment.START);
        final Span locationLabel = new Span(t("prefs.location"));
        locationLabel.getStyle().set("font-size", "var(--lumo-font-size-s)")
            .set("color", "var(--lumo-secondary-text-color)").set("font-weight", "500");
        final Paragraph locationHelp = new Paragraph(t("prefs.location.helper"));
        locationHelp.getStyle().set("font-size", "var(--lumo-font-size-xs)")
            .set("color", "var(--lumo-secondary-text-color)").set("margin", "2px 0 0 0");
        locationBlock.add(locationLabel);
        final String position = existing == null ? null : existing.getLastPosition();
        final List<String> where = describePosition(position);
        if (where.isEmpty()) {
            final Span none = new Span(t("prefs.location.none"));
            none.getStyle().set("font-size", "var(--lumo-font-size-s)");
            locationBlock.add(none);
        } else {
            for (final String line : where) {
                final Span s = new Span(line);
                s.getStyle().set("font-size", "var(--lumo-font-size-m)");
                locationBlock.add(s);
            }
            final Anchor open = new Anchor("/reader?" + position, t("prefs.location.open"));
            final Button forget = new Button(t("prefs.location.forget"), e -> {
                aPrefsService.save(email, p -> p.setLastPosition(null));
                getUI().ifPresent(ui -> ui.getPage().reload());
            });
            forget.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
            final HorizontalLayout actions = new HorizontalLayout(open, forget);
            actions.setAlignItems(Alignment.BASELINE);
            actions.setSpacing(true);
            locationBlock.add(actions);
        }
        locationBlock.add(locationHelp);

        // A Checkbox auto-sizes to its label, so under the parent's center
        // alignment each would centre on a different width and stair-step. Hold
        // them in a fixed 320px, left-aligned column so they share the same left
        // edge as the 320px selects above.
        final VerticalLayout boolGroup =
            new VerticalLayout(titlesBox, panelBox, markersBox, resumeBox, locationBlock);
        boolGroup.setPadding(false);
        boolGroup.setSpacing(false);
        boolGroup.setWidth("320px");
        boolGroup.setAlignItems(Alignment.START);
        boolGroup.getStyle().set("gap", "8px");

        // ── Muted voices ──────────────────────────────────────────────
        // The unmute surface. Muting happens in the reader, on the card of the
        // voice itself; this is where a reader sees the whole list at once and
        // takes something back off it. Items are the voices that actually exist
        // in public comments, plus whatever is already muted — a voice that has
        // since gone quiet must still be removable, or it would be stuck.
        final Set<String> mutedNow =
            CommentQueryService.parseMuted(existing == null ? null : existing.getMutedVoices());
        final MultiSelectComboBox<String> mutedBox = new MultiSelectComboBox<>();
        mutedBox.setLabel(t("prefs.mutedVoices"));
        mutedBox.setHelperText(t("prefs.mutedVoices.helper"));
        mutedBox.setWidth("320px");
        mutedBox.setClearButtonVisible(true);
        final java.util.LinkedHashSet<String> voiceItems =
            new java.util.LinkedHashSet<>(aCommentService.knownVoices());
        voiceItems.addAll(mutedNow);
        mutedBox.setItems(voiceItems.stream().sorted().toList());
        mutedBox.setValue(mutedNow);

        // ── Preferred UI language ─────────────────────────────────────
        final Select<Locale> langSel = new Select<>();
        langSel.setLabel(t("prefs.language"));
        langSel.setItems(LocaleUtil.LOCALES);
        langSel.setItemLabelGenerator(loc -> {
            final String name = loc.getDisplayLanguage(loc);
            return name == null || name.isBlank() ? loc.getLanguage() : name;
        });
        langSel.setWidth("320px");
        final Locale prefLoc = existing == null ? null
            : LocaleUtil.fromTag(existing.getUiLanguage());
        langSel.setValue(prefLoc != null ? prefLoc : LocaleUtil.currentLocale());

        // ── Save ──────────────────────────────────────────────────────
        final Button save = new Button(t("action.save"), e -> {
            final String[] bible = bibleBox.getValue();
            final Locale   lang  = langSel.getValue();
            try {
                aPrefsService.save(email, p -> {
                    p.setDefaultSource(bible == null || bible.length < 3
                        ? null : bible[2].toLowerCase());
                    p.setDefaultMode(ReaderLink.modeToken(modeSel.getValue()));
                    p.setDefaultOrder(ReaderLink.orderToken(orderSel.getValue()));
                    p.setReaderStyle(lookSel.getValue());
                    p.setReaderAccent(accentChoice[0]);
                    p.setReaderLongTitles(Boolean.TRUE.equals(titlesBox.getValue()));
                    p.setShowCommentsPanel(Boolean.TRUE.equals(panelBox.getValue()));
                    p.setShowCommentMarkers(Boolean.TRUE.equals(markersBox.getValue()));
                    p.setResumeEnabled(Boolean.TRUE.equals(resumeBox.getValue()));
                    if (!Boolean.TRUE.equals(resumeBox.getValue())) p.setLastPosition(null);
                    p.setMutedVoices(CommentQueryService.formatMuted(mutedBox.getValue()));
                    p.setUiLanguage(lang == null ? null : lang.getLanguage());
                });
                Notification.show(t("prefs.saved"), 2500, Notification.Position.TOP_CENTER);
                // The language preference also applies to THIS session right
                // away; a reload re-renders the whole UI in it.
                if (lang != null && !Objects.equals(lang, LocaleUtil.currentLocale())) {
                    LocaleUtil.store(lang);
                    getUI().ifPresent(ui -> ui.getPage().reload());
                }
            } catch (final Exception ex) {
                Notification.show(t("prefs.saveFailed"), 4000, Notification.Position.TOP_CENTER);
            }
        });
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        final Button back = new Button(t("prefs.backToReader"),
            e -> getUI().ifPresent(ui -> ui.navigate("reader")));
        back.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        final Button profile = new Button(t("prefs.profile"),
            e -> getUI().ifPresent(ui -> ui.navigate("profile")));
        profile.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        add(title, intro, bibleBox, modeSel, orderSel, lookSel, accentBlock,
            boolGroup, mutedBox, langSel, save, profile, back, aBuildInfo.pinned());
    }

    /** The saved position as one line per column — "KJV · Genesis 12:3",
     *  "Q-AR · Surah 2:255" — book names in the UI language via the booknames
     *  bundle. Empty when nothing is saved or nothing in it parses. */
    static List<String> describePosition(final String aQueryString) {
        if (aQueryString == null || aQueryString.isBlank()) return List.of();
        final java.util.Map<String, String> flat = new java.util.HashMap<>();
        for (final String kv : aQueryString.split("&")) {
            final int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            try {
                flat.put(java.net.URLDecoder.decode(kv.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
                         java.net.URLDecoder.decode(kv.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
            } catch (final IllegalArgumentException ignored) { /* malformed pair */ }
        }
        java.util.ResourceBundle names = null;
        try { names = java.util.ResourceBundle.getBundle("i18n/booknames", LocaleUtil.currentLocale()); }
        catch (final Exception ignored) { /* no bundle — codes will do */ }
        final List<String> out = new java.util.ArrayList<>();
        for (final ReaderLink.ColSpec c : ReaderLink.parse(flat).cols) {
            final StringBuilder sb = new StringBuilder();
            if (c.src != null && !c.src.isBlank()) sb.append(c.src.toUpperCase(Locale.ROOT));
            if (c.ref != null) {
                if (sb.length() > 0) sb.append(" \u00b7 ");
                if (c.ref.quran()) {
                    sb.append("Surah ").append(c.ref.a());
                    if (c.ref.b() > 0) sb.append(':').append(c.ref.b());
                } else {
                    String book = c.ref.unit();
                    if (names != null && names.containsKey(book)) book = names.getString(book);
                    sb.append(book).append(' ').append(c.ref.a());
                    if (c.ref.b() > 0) sb.append(':').append(c.ref.b());
                }
            }
            if (sb.length() > 0) out.add(sb.toString());
        }
        return out;
    }

    private static DisplayOptions.DisplayMode modeFromPref(final UserPreferences aPreferences) {
        final DisplayOptions.DisplayMode m = aPreferences == null ? null
            : ReaderLink.modeFromToken(aPreferences.getDefaultMode());
        return m != null ? m : DisplayOptions.DisplayMode.CHAPTERS_VERSES;
    }

    private String t(final String aKey) {
        return getTranslation(aKey, LocaleUtil.currentLocale());
    }
}
