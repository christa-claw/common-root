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
import org.religioustext.app.service.CrossRefQueryService.XRef;
import org.religioustext.app.service.TextQueryService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * The dialog behind a verse's cross-reference badge: one row per related
 * passage — a button that opens it in a new column, and one line of its text in
 * the column's own edition. The top {@value #TOP} by votes show first; "Show
 * all" reveals the rest. OpenBible.info's CC BY 4.0 credit is the footer.
 *
 * <p>Lives outside ReaderView (which is already 3,700 lines); everything it needs
 * from the reader — translation and "open this passage" — is handed in.
 */
final class CrossRefDialog {

    static final int TOP = 10;
    static final String CREDIT_URL = "https://www.openbible.info/labs/cross-references/";
    static final String LICENCE_URL = "https://creativecommons.org/licenses/by/4.0/";
    private static final int PREVIEW_CHARS = 160;

    private final TextQueryService queryService;
    private final BiFunction<String, Object[], String> translate;

    /** @param aTranslate key + params -> text */
    CrossRefDialog(final TextQueryService aQueryService,
                   final BiFunction<String, Object[], String> aTranslate) {
        this.queryService = aQueryService;
        this.translate = aTranslate;
    }

    /** @param aHeading e.g. "John 3:16"; @param aSourceId the column's edition, for the text lines;
     *  @param anOpener opens one passage in a new column (it knows which column asked) */
    void open(final String aHeading, final String aSourceId, final List<XRef> theRefs,
              final Consumer<XRef> anOpener) {
        final Dialog dialog = new Dialog();
        dialog.addClassName("instant-close");   // no close animation: see the theme stylesheet
        dialog.setHeaderTitle(aHeading + " — " + t("reader.xrefs.title"));
        dialog.setWidth("600px");
        dialog.setMaxWidth("94vw");
        dialog.setMaxHeight("82vh");
        dialog.setDraggable(true);
        dialog.setResizable(true);

        final Map<String, List<VerseRef>> chapters = new HashMap<>();   // one fetch per chapter
        final Div rows = new Div();
        final int[] shown = {0};
        final Button more = new Button();
        final Runnable fill = () -> {
            final int to = Math.min(theRefs.size(), shown[0] == 0 ? TOP : theRefs.size());
            for (int i = shown[0]; i < to; i++) rows.add(row(theRefs.get(i), aSourceId, chapters, dialog::close, anOpener));
            shown[0] = to;
            more.setVisible(shown[0] < theRefs.size());
        };
        more.setText(t("reader.xrefs.showAll", theRefs.size()));
        more.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        more.addClickListener(e -> fill.run());
        fill.run();
        dialog.add(rows, more);

        final Button openAll = new Button(t("reader.xrefs.openAll"), e -> {
            dialog.close();
            theRefs.stream().limit(Math.min(shown[0], ReaderLink.MAX_COLUMNS)).forEach(anOpener);
        });
        openAll.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        dialog.getFooter().add(credit(), openAll);
        dialog.open();
    }

    private Div row(final XRef aRef, final String aSourceId,
                    final Map<String, List<VerseRef>> theChapters, final Runnable aClose,
                    final Consumer<XRef> anOpener) {
        final Div row = new Div();
        row.getStyle().set("display", "flex").set("align-items", "baseline").set("gap", "10px")
            .set("padding", "3px 0");
        final Button ref = new Button(label(aRef), e -> {
            aClose.run();   // the new column is the answer; leave nothing floating over it
            anOpener.accept(aRef);
        });
        ref.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY_INLINE);
        ref.getStyle().set("flex", "none").set("min-width", "7em").set("text-align", "start");
        final Span text = new Span(preview(aRef, aSourceId, theChapters));
        text.getStyle().set("color", "var(--lumo-secondary-text-color)").set("font-size", "0.9em")
            .set("overflow", "hidden").set("text-overflow", "ellipsis").set("white-space", "nowrap")
            .set("min-width", "0");
        row.add(ref, text);
        return row;
    }

    /** One line of the target's first verse in the column's edition; blank if the edition lacks it. */
    private String preview(final XRef aRef, final String aSourceId,
                           final Map<String, List<VerseRef>> theChapters) {
        if (aSourceId == null) return "";
        try {
            final List<VerseRef> verses = theChapters.computeIfAbsent(
                aRef.toBook() + "|" + aRef.toChapter(),
                k -> queryService.versesByCodeChapter(aSourceId, aRef.toBook(), aRef.toChapter()));
            for (final VerseRef v : verses)
                if (v.getVerseNumber() == aRef.toVerse()) return clip(v.getDisplayContent(false));
        } catch (final RuntimeException e) {
            // A dead BaseX must not take the reference list with it: show the buttons without text.
        }
        return "";
    }

    private Div credit() {
        final Anchor src = new Anchor(CREDIT_URL, "OpenBible.info");
        src.setTarget("_blank");
        src.getElement().setAttribute("rel", "noopener");
        final Anchor lic = new Anchor(LICENCE_URL, "CC BY 4.0");
        lic.setTarget("_blank");
        lic.getElement().setAttribute("rel", "noopener");
        // CC BY 4.0 §3(a)(1)(B): say what was changed. The line is the seeder's own filtering.
        final Div credit = new Div(new Span(t("reader.xrefs.credit") + " "), src, new Span(" · "), lic);
        final Div changes = new Div(new Span(t("reader.xrefs.changes")));
        changes.getStyle().set("margin-top", "2px");
        final Div d = new Div(credit, changes);
        d.getStyle().set("font-size", "var(--lumo-font-size-xs)")
            .set("color", "var(--lumo-secondary-text-color)").set("margin-inline-end", "auto");
        return d;
    }

    private String t(final String aKey, final Object... aParams) { return translate.apply(aKey, aParams); }

    // ── Pure helpers (unit-tested) ────────────────────────────────────

    /** {@code GEN 1:1}, a same-chapter range {@code PRO 8:22–30}, or a cross-chapter one {@code PSA 89:11–90:2}. */
    static String label(final XRef r) {
        final String start = r.toBook() + " " + r.toChapter() + ":" + r.toVerse();
        if (r.toEndChapter() == null || r.toEndVerse() == null) return start;
        if (r.toEndChapter() == r.toChapter())
            return r.toEndVerse() == r.toVerse() ? start : start + "–" + r.toEndVerse();
        return start + "–" + r.toEndChapter() + ":" + r.toEndVerse();
    }

    static String clip(final String aText) {
        if (aText == null) return "";
        final String s = aText.strip().replaceAll("\\s+", " ");
        return s.length() <= PREVIEW_CHARS ? s : s.substring(0, PREVIEW_CHARS - 1).stripTrailing() + "…";
    }
}
