// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Paragraph;
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

        // ── Booleans ──────────────────────────────────────────────────
        final Checkbox panelBox = new Checkbox(t("prefs.showPanel"),
            existing != null && existing.isShowCommentsPanel());
        final Checkbox markersBox = new Checkbox(t("prefs.showMarkers"),
            existing != null && existing.isShowCommentMarkers());
        final Checkbox resumeBox = new Checkbox(t("prefs.resume"),
            existing != null && existing.isResumeEnabled());
        resumeBox.setTooltipText(t("prefs.resume.helper"));

        // A Checkbox auto-sizes to its label, so under the parent's center
        // alignment each would centre on a different width and stair-step. Hold
        // them in a fixed 320px, left-aligned column so they share the same left
        // edge as the 320px selects above.
        final VerticalLayout boolGroup =
            new VerticalLayout(panelBox, markersBox, resumeBox);
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

        add(title, intro, bibleBox, modeSel, orderSel,
            boolGroup, mutedBox, langSel, save, back, aBuildInfo.pinned());
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
