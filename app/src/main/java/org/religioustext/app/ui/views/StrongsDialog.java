// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import org.religioustext.app.model.VerseRef;
import org.religioustext.app.service.LexiconService;
import org.religioustext.app.service.LexiconService.Entry;
import org.religioustext.app.service.TextQueryService;
import org.religioustext.app.service.VerseNumbering;
import org.religioustext.app.service.WordLayerService;
import org.religioustext.app.service.WordLayerService.Occurrence;
import org.religioustext.app.ui.views.reader.BookNames;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The window behind a double-clicked Strong's number: the whole lexicon entry (the tooltip clips the
 * definition), how often the word occurs in the Hebrew Bible, and those passages. A passage expands
 * in place — the whole Hebrew verse, the word highlighted, the other words with their numbers — so
 * nothing has to open to read it; a button on each row opens it in a new column of the
 * original-language text instead. Same dialog setup as the comments and cross-reference dialogs.
 */
final class StrongsDialog {

    /** Passages listed up front; the rest of the fetched ones sit behind "Show all". */
    static final int SHOWN = 20;
    /** Most passages fetched per word. */
    static final int LIMIT = 200;
    static final String LEXICON_URL = "https://github.com/openscriptures/HebrewLexicon";
    static final String OSHB_URL = "https://github.com/openscriptures/morphhb";
    static final String LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/";

    /** The Bible shown under each Hebrew verse: its edition id, abbreviation (the chip) and direction. */
    record Translation(String editionId, String abbreviation, boolean rtl) {}

    private final LexiconService lexicon;
    private final WordLayerService words;
    private final TextQueryService texts;
    private final VerseNumbering numbering;
    private final BiFunction<String, Object[], String> translate;

    StrongsDialog(final LexiconService aLexicon, final WordLayerService aWords, final TextQueryService aTexts,
                  final VerseNumbering aNumbering, final BiFunction<String, Object[], String> aTranslate) {
        this.lexicon = aLexicon;
        this.words = aWords;
        this.texts = aTexts;
        this.numbering = aNumbering;
        this.translate = aTranslate;
    }

    /** @param aStrongs e.g. {@code H430}; @param anEditionId the original-language edition whose words are
     *  counted; @param aTranslation the Bible to show under each Hebrew verse, or null for Hebrew only;
     *  @param anOpener opens one passage in a new column. No-op for an unknown number. */
    void open(final String aStrongs, final String anEditionId, final Locale aLocale, final Translation aTranslation,
              final Consumer<Occurrence> anOpener) {
        final Optional<Entry> found = lexicon.hebrew(aStrongs, aLocale);
        if (found.isEmpty()) return;
        final Entry e = found.get();
        final boolean rtl = isRtl(aLocale);

        final Dialog dialog = new Dialog();
        dialog.addClassName("instant-close");   // hands over to a new column: see the theme stylesheet
        dialog.setHeaderTitle(e.strongs() + " — " + e.lemma());
        dialog.setWidth("620px");
        dialog.setMaxWidth("94vw");
        dialog.setMaxHeight("82vh");
        dialog.setDraggable(true);
        dialog.setResizable(true);

        final Div body = new Div();
        body.getElement().setAttribute("dir", rtl ? "rtl" : "ltr");

        final Div head = new Div();
        final Span lemma = new Span(e.lemma());
        lemma.getElement().setAttribute("lang", "he");
        lemma.getElement().setAttribute("dir", "rtl");
        lemma.getStyle().set("font-size", "2em").set("margin-inline-end", "0.4em").set("unicode-bidi", "isolate");
        final Span xlit = new Span(e.xlit());
        xlit.getStyle().set("font-style", "italic").set("color", "var(--lumo-secondary-text-color)");
        head.add(lemma, xlit);
        body.add(head);

        if (!e.gloss().isBlank()) {
            final Div g = new Div(new Span(e.gloss()));
            g.getStyle().set("font-weight", "600").set("margin-top", "4px");
            body.add(g);
        }
        if (!e.definition().isBlank()) {
            final Div d = new Div(new Span(e.definition()));
            d.getStyle().set("color", "var(--lumo-secondary-text-color)").set("margin-top", "4px")
                .set("line-height", "1.5");
            body.add(d);
        }
        if (e.machineTranslated()) {
            final Div m = new Div(new Span(t("reader.strongs.machine")));
            m.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("font-style", "italic")
                .set("color", "var(--lumo-tertiary-text-color)").set("margin-top", "4px");
            body.add(m);
        }

        final int total = words.countOccurrences(anEditionId, aStrongs);
        if (total > 0) {
            final List<Occurrence> all = words.occurrences(anEditionId, aStrongs, LIMIT);
            final Div h = new Div(new Span(t("reader.strongs.occurrences", total)));
            h.getStyle().set("font-weight", "600").set("margin", "16px 0 6px")
                .set("border-top", "1px solid var(--lumo-contrast-10pct)").set("padding-top", "10px");
            body.add(h);
            final Div rows = new Div();
            final Map<String, List<VerseRef>> chapters = new HashMap<>();   // one fetch per chapter of the Bible shown
            final int[] shown = {0};
            final Button more = new Button();
            final Runnable fill = () -> {
                final int to = shown[0] == 0 ? Math.min(SHOWN, all.size()) : all.size();
                for (int i = shown[0]; i < to; i++)
                    rows.add(row(all.get(i), aStrongs, anEditionId, aLocale, aTranslation, chapters,
                        dialog::close, anOpener));
                shown[0] = to;
                more.setVisible(shown[0] < all.size());
            };
            more.setText(t("reader.xrefs.showAll", all.size()));
            more.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
            more.addClickListener(ev -> fill.run());
            fill.run();
            body.add(rows, more);
        }
        dialog.add(body);
        dialog.getFooter().add(credit());
        dialog.open();
    }

    /** One passage: a toggle that expands the whole Hebrew verse in place (the word highlighted, the
     *  other words with their numbers), the word as written there, and a button that opens the
     *  passage in a new column. A verse whose text did not match the source exactly has no word
     *  list to show, so for it the toggle opens the column instead. */
    private Div row(final Occurrence o, final String aStrongs, final String anEditionId, final Locale aLocale,
                    final Translation aTranslation, final Map<String, List<VerseRef>> theChapters,
                    final Runnable aClose, final Consumer<Occurrence> anOpener) {
        final Div item = new Div();
        final Div head = new Div();
        head.getStyle().set("display", "flex").set("align-items", "baseline").set("gap", "10px").set("padding", "2px 0");

        final Div detail = new Div();
        detail.setVisible(false);
        detail.getElement().setAttribute("dir", "rtl");
        detail.getStyle().set("text-align", "right").set("font-size", "1.25em").set("line-height", "1.9")
            .set("padding", "4px 12px 8px").set("margin", "2px 0 6px")
            .set("background", "var(--lumo-contrast-5pct)").set("border-radius", "6px");

        final boolean[] built = {false};
        final Button ref = new Button("\u25B8 " + label(o, aLocale));
        ref.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
        ref.getStyle().set("flex", "none").set("min-width", "7em").set("text-align", "start");
        ref.addClickListener(ev -> {
            if (!built[0]) {
                final List<WordLayerService.Word> verse =
                    words.forChapter(anEditionId, o.book(), o.chapter()).get(o.verse());
                if (verse == null || verse.isEmpty()) {          // not an exact verse: nothing to expand
                    aClose.run();
                    anOpener.accept(o);
                    return;
                }
                detail.add(VerseWindowRenderer.strongsLine(verse, aLocale == null ? "en" : aLocale.toLanguageTag(), aStrongs, 0));
                final Div line = translationLine(o, aTranslation, aLocale, theChapters);
                if (line != null) detail.add(line);
                built[0] = true;
            }
            detail.setVisible(!detail.isVisible());
            ref.setText((detail.isVisible() ? "\u25BE " : "\u25B8 ") + label(o, aLocale));
        });

        final Span w = new Span(o.text());
        w.getElement().setAttribute("lang", "he");
        w.getElement().setAttribute("dir", "rtl");
        w.getStyle().set("font-size", "1.2em").set("unicode-bidi", "isolate").set("flex", "1");

        final Button open = new Button(com.vaadin.flow.component.icon.VaadinIcon.EXTERNAL_LINK.create(), ev -> {
            aClose.run();                // the new column is the answer; leave nothing floating over it
            anOpener.accept(o);
        });
        open.addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE, ButtonVariant.LUMO_SMALL);
        open.setTooltipText(t("reader.comments.openInNewColumn"));
        open.getElement().setAttribute("aria-label", t("reader.comments.openInNewColumn"));

        head.add(ref, w, open);
        item.add(head, detail);
        return item;
    }

    /** The same verse in the reader's Bible, under the Hebrew: the edition's chip, the verse, and — when that
     *  Bible numbers the verse differently from the Hebrew text — where it found it. Null when the Bible's
     *  numbering is unknown or it has no such verse (a Hebrew-only expansion then). */
    private Div translationLine(final Occurrence o, final Translation aTranslation, final Locale aLocale,
                                final Map<String, List<VerseRef>> theChapters) {
        if (aTranslation == null) return null;
        final Optional<VerseNumbering.Ref> target =
            numbering.fromHebrew(aTranslation.editionId(), o.book(), o.chapter(), o.verse());
        if (target.isEmpty()) return null;
        final VerseNumbering.Ref r = target.get();
        String text = null;
        try {
            for (final VerseRef v : theChapters.computeIfAbsent(r.book() + "|" + r.chapter(),
                    k -> texts.versesByCodeChapter(aTranslation.editionId(), r.book(), r.chapter())))
                if (v.getVerseNumber() == r.verse()) { text = v.getDisplayContent(false); break; }
        } catch (final RuntimeException e) {
            return null;     // a BaseX hiccup must not break the Hebrew expansion
        }
        if (text == null || text.isBlank()) return null;

        final Div line = new Div();
        line.getElement().setAttribute("dir", aTranslation.rtl() ? "rtl" : "ltr");
        line.getStyle().set("font-size", "0.8em").set("line-height", "1.6").set("margin-top", "6px")
            .set("padding-top", "6px").set("border-top", "1px solid var(--lumo-contrast-10pct)")
            .set("text-align", aTranslation.rtl() ? "right" : "left");
        final Span chip = new Span(aTranslation.abbreviation());
        chip.getStyle().set("background", "var(--lumo-contrast-10pct)").set("border-radius", "3px")
            .set("font-size", "0.75em").set("padding", "0 4px").set("margin-inline-end", "6px")
            .set("color", "var(--lumo-secondary-text-color)").set("direction", "ltr").set("display", "inline-block");
        line.add(chip, new Span(text.strip()));
        if (!r.book().equals(o.book()) || r.chapter() != o.chapter() || r.verse() != o.verse()) {
            final Span where = new Span(" (" + BookNames.of(r.book(), aLocale) + " " + r.chapter() + ":" + r.verse() + ")");
            where.getStyle().set("color", "var(--lumo-secondary-text-color)");
            line.add(where);
        }
        return line;
    }

    private Div credit() {
        final Div d = new Div();
        d.add(link("Open Scriptures Hebrew Lexicon", LEXICON_URL), new Span(" · "),
              link("OSHB", OSHB_URL), new Span(" · "), link("CC BY 4.0", LICENCE_URL));
        d.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)")
            .set("margin-inline-end", "auto");
        return d;
    }

    private static Anchor link(final String aText, final String aUrl) {
        final Anchor a = new Anchor(aUrl, aText);
        a.setTarget("_blank");
        a.getElement().setAttribute("rel", "noopener");
        return a;
    }

    private String t(final String aKey, final Object... aParams) { return translate.apply(aKey, aParams); }

    // ── Pure helpers (unit-tested) ────────────────────────────────────

    /** {@code Genesis 1:1} in the interface language. */
    static String label(final Occurrence o, final Locale aLocale) {
        return BookNames.of(o.book(), aLocale) + " " + o.chapter() + ":" + o.verse();
    }

    static boolean isRtl(final Locale aLocale) {
        return aLocale != null && ("ar".equals(aLocale.getLanguage()) || "he".equals(aLocale.getLanguage())
            || "iw".equals(aLocale.getLanguage()));
    }
}
