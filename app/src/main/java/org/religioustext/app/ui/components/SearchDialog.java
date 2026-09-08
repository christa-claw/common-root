// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.components;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import org.religioustext.app.i18n.LocaleUtil;
import org.religioustext.app.service.CommentQueryService.VerseComment;
import org.religioustext.app.service.SearchService;
import org.religioustext.app.service.SearchService.CommentHit;
import org.religioustext.app.service.SearchService.VerseHit;

import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.function.Function;

/**
 * Full-text search, presented as a dialog opened from the reader toolbar (🔍).
 *
 * Results are COLLAPSED to one row per verse-ref: the same verse exists in many
 * editions, so the service returns one representative hit per verse (Meili
 * {@code distinct:"ref"}). Opening a verse resolves the translations the query
 * actually matched — one opens straight away, several show a picker with a
 * checkbox per translation so the reader can open one or compare several at once
 * (each in its own column). Selecting routes through the same open-ref path the
 * comment panel uses (new unsynced column + flash).
 *
 * Book names are localised by code via the {@code booknames} bundle (same as the
 * reader) — the index stores the canonical English name, so without this the
 * addresses would read "Exodus" even in a Finnish UI. Verse text and comment
 * content render as PLAIN TEXT, never the index's highlighted HTML.
 */
public class SearchDialog extends Dialog {

    /** Don't query on a single character — too noisy to be useful. */
    private static final int MIN_CHARS = 2;

    /** Opens a verse ref in the reader. {@code sourceId} names the chosen edition
     *  (null = reader default); {@code synced} opens it synced so it scrolls with
     *  the other columns opened in the same action (comparing translations). */
    @FunctionalInterface
    public interface OpenRef {
        void open(VerseComment.Ref aRef, String aSourceId, boolean aSyncedFlag);
    }

    private final SearchService search;
    private final OpenRef onOpenRef;
    /** source_id -> full edition name (e.g. "King James Version"); may return null. */
    private final Function<String, String> editionName;
    private final Function<String, String> t;
    private final ResourceBundle bookNames;
    private final Div results = new Div();
    private String lastQuery = "";

    public SearchDialog(final SearchService aSearch,
                        final OpenRef anOpenRef,
                        final Function<String, String> anEditionName,
                        final Function<String, String> aTranslator) {
        this.search      = aSearch;
        this.onOpenRef   = anOpenRef;
        this.editionName = anEditionName;
        this.t           = aTranslator;

        ResourceBundle bn = null;
        try { bn = ResourceBundle.getBundle("i18n/booknames", LocaleUtil.currentLocale()); }
        catch (final Exception ignored) { /* no bundle -> fall back to index names */ }
        this.bookNames = bn;

        setHeaderTitle(t.apply("reader.search.title"));
        setWidth("640px");
        setMaxWidth("96vw");
        setMaxHeight("82vh");
        setDraggable(true);
        setResizable(true);

        final TextField field = new TextField();
        field.setPlaceholder(t.apply("reader.search.placeholder"));
        field.setPrefixComponent(VaadinIcon.SEARCH.create());
        field.setClearButtonVisible(true);
        field.setWidthFull();
        field.setValueChangeMode(ValueChangeMode.LAZY);   // debounced as-you-type
        field.addValueChangeListener(e -> runSearch(e.getValue()));
        field.focus();

        results.getStyle()
            .set("margin-top", "10px")
            .set("overflow-y", "auto")
            .set("max-height", "62vh");
        hint();

        add(field, results);
    }

    private void runSearch(final String aQuery) {
        results.removeAll();
        final String q = aQuery == null ? "" : aQuery.trim();
        lastQuery = q;
        if (q.length() < MIN_CHARS) { hint(); return; }

        final SearchService.Results r;
        try {
            r = search.search(q);
        } catch (final SearchService.SearchUnavailableException ex) {
            results.add(message(t.apply("reader.search.unavailable")));
            return;
        }
        if (r.isEmpty()) {
            results.add(message(t.apply("reader.search.empty")));
            return;
        }
        if (!r.verses().isEmpty()) {
            results.add(groupHeader(t.apply("reader.search.versesGroup"), r.verseTotal()));
            for (final VerseHit h : r.verses()) results.add(verseRow(h));
        }
        if (!r.comments().isEmpty()) {
            results.add(groupHeader(t.apply("reader.search.commentsGroup"), r.commentTotal()));
            for (final CommentHit c : r.comments()) results.add(commentRow(c));
        }
    }

    private void hint() {
        results.add(message(t.apply("reader.search.hint")));
    }

    // ── verse hits (collapsed by ref) ─────────────────────────────────

    /** A collapsed verse hit — a tappable row. Click resolves the matched
     *  translations: one opens directly, several open the picker. */
    private Div verseRow(final VerseHit aHit) {
        final Div row = new Div();
        row.getStyle()
            .set("cursor", "pointer")
            .set("padding", "8px 4px")
            .set("border-radius", "4px")
            .set("border-bottom", "1px solid var(--lumo-contrast-5pct)");

        final Span addr = new Span(addressOf(aHit));
        addr.getStyle()
            .set("font-weight", "600").set("font-size", "13px")
            .set("color", "var(--lumo-primary-text-color)").set("display", "block");

        final Span body = new Span(aHit.text());
        body.getStyle()
            .set("font-size", "14px").set("line-height", "1.5")
            .set("color", "var(--lumo-body-text-color)")
            .set("direction", isRtl(aHit.language()) ? "rtl" : "ltr");

        row.add(addr, body);
        row.getElement().setAttribute("title", t.apply("reader.search.openVerse"));
        row.addClickListener(e -> openVerse(aHit));
        return row;
    }

    /** Resolve the translations the query matched for this verse, then either
     *  open the single match or present the picker. */
    private void openVerse(final VerseHit aHit) {
        List<VerseHit> variants;
        try {
            variants = search.translationsFor(lastQuery, aHit.ref());
        } catch (final SearchService.SearchUnavailableException ex) {
            variants = List.of(aHit);
        }
        if (variants.size() <= 1) {
            final VerseHit only = variants.isEmpty() ? aHit : variants.get(0);
            onOpenRef.open(toRef(only), only.sourceId(), false);
            close();
        } else {
            showTranslationPicker(aHit, variants);
        }
    }

    /** Picker with a checkbox per matched translation — open one, or tick several
     *  to compare them side by side (each opens in its own column). */
    private void showTranslationPicker(final VerseHit aVerse, final List<VerseHit> theVariants) {
        final Dialog picker = new Dialog();
        picker.setHeaderTitle(addressOf(aVerse) + " — " + t.apply("reader.search.pickTranslation"));
        picker.setWidth("560px");
        picker.setMaxWidth("94vw");
        picker.setMaxHeight("80vh");
        picker.setDraggable(true);
        picker.setResizable(true);

        final List<Checkbox> boxes = new ArrayList<>();
        final Div list = new Div();
        list.getStyle().set("overflow-y", "auto").set("max-height", "62vh");

        final Button open = new Button(t.apply("reader.search.openSelected"));
        open.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        open.setEnabled(false);

        for (final VerseHit v : theVariants) {
            final Checkbox cb = new Checkbox();
            boxes.add(cb);
            cb.addValueChangeListener(e -> {
                final long n = boxes.stream().filter(Checkbox::getValue).count();
                open.setEnabled(n > 0);
                open.setText(n > 0
                    ? t.apply("reader.search.openSelected") + " (" + n + ")"
                    : t.apply("reader.search.openSelected"));
            });
            list.add(translationRow(v, cb));
        }

        open.addClickListener(e -> {
            final long selected = boxes.stream().filter(Checkbox::getValue).count();
            final boolean sync = selected >= 2;   // comparing several -> scroll together
            for (int i = 0; i < theVariants.size(); i++) {
                if (Boolean.TRUE.equals(boxes.get(i).getValue())) {
                    final VerseHit v = theVariants.get(i);
                    onOpenRef.open(toRef(v), v.sourceId(), sync);
                }
            }
            picker.close();
            close();
        });

        picker.add(list);
        picker.getFooter().add(open);
        picker.open();
    }

    /** One translation option: a checkbox plus the edition's abbreviation and its
     *  wording (so the differences are visible). Clicking the text toggles the box;
     *  the checkbox toggles itself. */
    private Div translationRow(final VerseHit aVerseHit, final Checkbox aCheckbox) {
        final Div row = new Div();
        row.getStyle()
            .set("display", "flex").set("align-items", "flex-start").set("gap", "10px")
            .set("padding", "8px 4px")
            .set("border-radius", "4px")
            .set("border-bottom", "1px solid var(--lumo-contrast-5pct)");
        aCheckbox.getStyle().set("margin-top", "2px").set("flex-shrink", "0");

        final Div textCol = new Div();
        textCol.getStyle().set("flex", "1").set("cursor", "pointer");

        final String full = editionName == null ? null : editionName.apply(aVerseHit.sourceId());
        final String name = full != null && !full.isBlank() ? full
            : (aVerseHit.abbreviation() == null || aVerseHit.abbreviation().isBlank() ? aVerseHit.sourceId() : aVerseHit.abbreviation());
        final Span abbr = new Span(name);
        abbr.getStyle()
            .set("font-weight", "700").set("font-size", "13px")
            .set("color", "var(--lumo-primary-text-color)").set("display", "block");

        final Span body = new Span(aVerseHit.text());
        body.getStyle()
            .set("font-size", "14px").set("line-height", "1.5")
            .set("color", "var(--lumo-body-text-color)")
            .set("direction", isRtl(aVerseHit.language()) ? "rtl" : "ltr");

        textCol.add(abbr, body);
        // Toggle via the text column only (not the whole row), so a click on the
        // checkbox itself isn't double-handled and cancelled out.
        textCol.addClickListener(e -> aCheckbox.setValue(!Boolean.TRUE.equals(aCheckbox.getValue())));

        row.add(aCheckbox, textCol);
        return row;
    }

    // ── comment hits ──────────────────────────────────────────────────

    /** A comment hit — its text plus a jump button per cited verse. Comment refs
     *  open in the reader's default edition (no translation choice). */
    private Div commentRow(final CommentHit aCommentHit) {
        final Div row = new Div();
        row.getStyle()
            .set("padding", "8px 4px")
            .set("border-bottom", "1px solid var(--lumo-contrast-5pct)");

        final Span body = new Span(aCommentHit.content());
        body.getStyle()
            .set("font-size", "14px").set("line-height", "1.5")
            .set("display", "block").set("white-space", "pre-wrap");
        row.add(body);

        if (!aCommentHit.refs().isEmpty()) {
            final Div refs = new Div();
            refs.getStyle()
                .set("display", "flex").set("flex-wrap", "wrap")
                .set("gap", "4px 8px").set("margin-top", "6px");
            for (final String refStr : aCommentHit.refs()) {
                final VerseComment.Ref ref = parseRef(refStr);
                if (ref == null) continue;
                final Button b = new Button(refLabel(ref), e -> {
                    onOpenRef.open(ref, null, false);   // default edition
                    close();
                });
                b.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
                refs.add(b);
            }
            row.add(refs);
        }
        return row;
    }

    // ── helpers ───────────────────────────────────────────────────────

    private Span message(final String aText) {
        final Span s = new Span(aText);
        s.getStyle()
            .set("color", "var(--lumo-secondary-text-color)")
            .set("display", "block")
            .set("padding", "12px 2px");
        return s;
    }

    private Div groupHeader(final String aLabel, final int aTotal) {
        final Div h = new Div();
        h.setText(aLabel + " (" + aTotal + ")");
        h.getStyle()
            .set("font-weight", "700").set("font-size", "12px")
            .set("text-transform", "uppercase").set("letter-spacing", "0.04em")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("margin", "14px 0 6px").set("padding-bottom", "4px")
            .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");
        return h;
    }

    /** Address line for a hit, with the book name localised to the UI language. */
    private String addressOf(final VerseHit aHit) {
        final String book = localBookName(aHit);
        return aHit.quran()
            ? book + " " + aHit.verse()
            : book + " " + aHit.chapter() + ":" + aHit.verse();
    }

    /** Localised book name by code (like the reader), falling back to the index's
     *  canonical (English) name — e.g. for Qur'an surah codes not in the bundle. */
    private String localBookName(final VerseHit aHit) {
        if (bookNames != null && aHit.bookCode() != null && !aHit.bookCode().isBlank()) {
            try { return bookNames.getString(aHit.bookCode()); }
            catch (final java.util.MissingResourceException ignored) { /* fall through */ }
        }
        return aHit.bookName();
    }

    private static VerseComment.Ref toRef(final VerseHit aVerseHit) {
        return new VerseComment.Ref(aVerseHit.quran(), aVerseHit.bookCode(), aVerseHit.chapter(), aVerseHit.verse());
    }

    private static boolean isRtl(final String aLanguage) {
        return "ar".equalsIgnoreCase(aLanguage) || "he".equalsIgnoreCase(aLanguage);
    }

    /** Parse an index ref ("JHN.3.16") into the reader's jump currency. Qur'an
     *  refs carry the surah NUMBER as the book code (all-digits). */
    private static VerseComment.Ref parseRef(final String aRef) {
        if (aRef == null) return null;
        final String[] p = aRef.split("\\.");
        if (p.length != 3) return null;
        try {
            return new VerseComment.Ref(isSurahCode(p[0]), p[0],
                Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private static boolean isSurahCode(final String aCode) {
        return !aCode.isEmpty() && aCode.chars().allMatch(Character::isDigit);
    }

    private static String refLabel(final VerseComment.Ref aRef) {
        return aRef.quran()
            ? "Q " + aRef.bookCode() + ":" + aRef.verse()
            : aRef.bookCode() + " " + aRef.chapter() + ":" + aRef.verse();
    }
}
