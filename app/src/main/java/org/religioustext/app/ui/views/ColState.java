// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import org.religioustext.app.model.DisplayOptions;
import org.religioustext.app.model.DisplayOptions.OrderMode;
import org.religioustext.app.model.SourceColumn;
import org.religioustext.app.ui.views.reader.SourceCatalog;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable per-column state for the reader: the active book list + display-name
 * map, the in-column Qur'an companion pairing, the verse-window bounds (in
 * active-seq space), the column's Vaadin controls, and the source attribution
 * parts. One instance per open column, held in {@code ReaderView.states} beside
 * the column's {@link SourceColumn} model.
 *
 * <p>Extracted verbatim from ReaderView (2026-07-01) as the first structural step
 * of the reader split — package-private so ReaderView keeps direct field access.
 * Its methods are pure column-local queries/mutations (they touch no ReaderView
 * state), which is why it lifts out without any coupling to untangle.
 */
final class ColState {
    int             uid;
    List<String[]>  books        = new ArrayList<>();   // {name, chapterCount, arabicName, code} in active order
    java.util.Map<String,String> bookDisplay = new java.util.HashMap<>(); // English bookName -> shown name (Arabic surah name for the RTL Quran)
    // In-column companion pairing (Qur'an: a translation shown beneath each
    // ayah, or the Arabic beneath a translation). companionId is the paired
    // source; null when the column has no companion (all non-Qur'an columns).
    String          companionId    = null;
    boolean         companionRtl   = false;
    boolean         showCompanion  = false;   // hidden until toggled
    java.util.Map<Integer,String> companionText = new java.util.HashMap<>(); // canonicalSeq -> companion verse text
    Button          companionToggle;
    // Lineage (Bible editions), walked BOTH ways from the selected edition:
    // `lineage` = the texts it stands on (@basedOn chains) and `descendants` =
    // the texts that stand on it, each nearest generation first. rungTexts
    // caches every rung's "code|chapter|verse" -> text, whichever side it came
    // from. Rungs align by the reference axis, NOT canonical seq — seqs are
    // stamped per-edition and don't agree between two Bibles.
    //
    // ONE SIGNED AXIS, ONE PAIR OF CHEVRONS (Christa, 2026-08-21). rungDepth
    // runs from -totalDescendantRungs() to +totalRungs(): positive opens
    // ancestors BENEATH each verse, negative opens descendants ABOVE it, and
    // zero is the selected edition alone. ▾ increments and ▴ decrements, so ▴
    // closes the open ancestors one at a time and then, once they are all shut,
    // keeps going the same direction into the newer translations. The reader
    // never has to learn which of four buttons does what: the column reads as
    // one ladder in time with the chosen Bible fixed in the middle.
    java.util.List<java.util.List<SourceCatalog.Rung>> lineage = java.util.List.of();
    java.util.List<java.util.List<SourceCatalog.Rung>> descendants = java.util.List.of();
    int             rungDepth = 0;
    java.util.Map<String, java.util.Map<String,String>> rungTexts = new java.util.HashMap<>();
    Button          rungMoreBtn;
    Button          rungLessBtn;
    Button          commentsToggle;        // in-text comment-marker toggle (per column)
    Select<String[]> translationSelect;   // Qur'an: which translation shows beneath the Arabic
    Select<DisplayOptions.DisplayMode> modeSelect;
    Select<OrderMode> orderSelect;
    Button          syncBtn;
    /** The first {@code aCount} rungs of a ladder, generation by generation and
     *  main line first within each — one click, one rung (Christa's read of "a
     *  rung at a time"), even where a generation holds several parents. */
    private static java.util.List<SourceCatalog.Rung> take(
            final java.util.List<java.util.List<SourceCatalog.Rung>> aLadder, final int aCount) {
        if (aCount <= 0 || aLadder.isEmpty()) return java.util.List.of();
        final java.util.List<SourceCatalog.Rung> out = new ArrayList<>();
        for (final java.util.List<SourceCatalog.Rung> generation : aLadder) {
            for (final SourceCatalog.Rung rung : generation) {
                if (out.size() >= aCount) return out;
                out.add(rung);
            }
        }
        return out;
    }

    private static java.util.List<SourceCatalog.Rung> flatten(
            final java.util.List<java.util.List<SourceCatalog.Rung>> aLadder) {
        final java.util.List<SourceCatalog.Rung> out = new ArrayList<>();
        for (final java.util.List<SourceCatalog.Rung> generation : aLadder) out.addAll(generation);
        return out;
    }

    /** The ancestor rungs currently shown BENEATH each verse. Empty whenever
     *  rungDepth is zero or negative — at negative depth the column is showing
     *  descendants instead, and the two never appear together. */
    java.util.List<SourceCatalog.Rung> activeRungs() {
        return take(lineage, rungDepth);
    }

    /** The descendant rungs currently shown ABOVE each verse, nearest first.
     *  Empty whenever rungDepth is zero or positive. */
    java.util.List<SourceCatalog.Rung> activeDescendantRungs() {
        return take(descendants, -rungDepth);
    }

    /** Whichever side is currently open — ancestors below at positive depth,
     *  descendants above at negative, empty at zero. The two are never open at
     *  once, which is what makes one pair of chevrons enough. Fetching and
     *  visibility both key off this rather than off the sign. */
    java.util.List<SourceCatalog.Rung> openRungs() {
        return rungDepth >= 0 ? activeRungs() : activeDescendantRungs();
    }

    /** Every ancestor rung in ladder order (all generations flattened). */
    java.util.List<SourceCatalog.Rung> allRungs() {
        return flatten(lineage);
    }

    /** Every descendant rung in ladder order. */
    java.util.List<SourceCatalog.Rung> allDescendantRungs() {
        return flatten(descendants);
    }

    /** Total ancestor rungs — the ceiling for ▾. */
    int totalRungs() {
        return flatten(lineage).size();
    }

    /** Total descendant rungs — the floor for ▴ is its negation. */
    int totalDescendantRungs() {
        return flatten(descendants).size();
    }

    int             bookIndex    = 0;                   // index into books of the visible book
    int             visibleChapter = 1;
    // Verse-window bounds, in active-seq space. The DOM holds the verses
    // whose active-seq lies in [firstSeq, lastSeq]. visibleSeq tracks the
    // top-of-viewport verse for the label + sync broadcast.
    int             firstSeq      = -1;
    int             lastSeq       = -1;
    int             visibleSeq    = -1;
    long            suppressUntil = 0;   // ignore chapter-visible broadcasts until this ms (programmatic sync)
    boolean         observerReady = false;
    Div             scrollRoot;
    Div             content;
    Div             botSentinel;   // always last child — insert before it reliably
    Select<String>  bookSelect;
    Select<String>     langCombo;
    ComboBox<String[]> sourceCombo;
    Span            attribution;            // source/translation + licence line under the nav
    String          srcTranslation, srcLicense, srcSource;  // primary-source attribution parts
    boolean         showComments;           // in-text 💬 markers; OFF by default — scripture text stays pristine
    Span            chapterLabel;
    /** The chapter label doubles as a jump box: clicking it swaps in this
     *  field, typing a number and pressing Enter goes there. Maria Lehtonen
     *  (Kotus, 2026-08-22) asked for it after reading the site on a phone,
     *  where stepping ‹ › through a long book is the only alternative and the
     *  verse-search box at the top does not announce what it is for. */
    com.vaadin.flow.component.textfield.TextField chapterInput;
    String          chapterAbbrev = "Ch.";
    // Verse-precision link memory: when this column was OPENED AT A SPECIFIC
    // VERSE (a comment ref, a search hit, a cN.ref=BOOK.CH.VS deep link), the
    // verse is remembered here so Copy-link can emit it. Used only while the
    // column still shows that (book, chapter) — scroll or navigate away and the
    // link degrades gracefully to chapter precision. openedRefVerse == 0 means
    // "no verse-precision memory". (The reader tracks the visible CHAPTER, not
    // the top verse — see resume item #2 — so this covers the open-side cases
    // exactly and leaves free scrolling at chapter precision.)
    String          openedRefBook;
    int             openedRefChapter;
    int             openedRefVerse;
    SourceColumn    col;

    OrderMode order() { return col.getDisplayOptions().getOrderMode(); }

    /** How MANY chapters a book has here — not how far they run. For an edition
     *  that prints selections the two differ: Agricola's Exodus has four chapters
     *  and they are numbered 15, 19, 20 and 32. Use chapterNumbersForBook for
     *  anything that navigates. */
    int chapterCountForBook(final int anIndex) {
        if (anIndex < 0 || anIndex >= books.size()) return 0;
        try { return Integer.parseInt(books.get(anIndex)[1]); }
        catch (final Exception e) { return 1; }
    }

    /**
     * The chapter numbers a book actually has here, ascending.
     *
     * <p>Every complete Bible makes chapters look like 1..N, and the reader used
     * to assume it: open a book at chapter 1, step until the count runs out. Both
     * halves of that are false for AGR1548. Twenty-five of its seventy-one books
     * have no chapter 1 at all — Agricola printed selections — so "open at 1"
     * resolved to no verse and the book silently would not open, and stepping
     * compared a chapter NUMBER against a chapter COUNT, which for Exodus meant
     * chapter 15 immediately tested as past the end of a four-chapter book.
     *
     * <p>Falls back to 1..count when the corpus row predates the fifth field, so
     * an old cached book list degrades to the previous behaviour rather than to
     * an empty reader.
     */
    int[] chapterNumbersForBook(final int anIndex) {
        if (anIndex < 0 || anIndex >= books.size()) return new int[0];
        final String[] row = books.get(anIndex);
        final String csv = row.length > 4 ? row[4] : null;
        if (csv == null || csv.isBlank()) {
            final int n = chapterCountForBook(anIndex);
            final int[] assumed = new int[n];
            for (int i = 0; i < n; i++) assumed[i] = i + 1;
            return assumed;
        }
        final String[] parts = csv.split(",");
        final int[] out = new int[parts.length];
        int k = 0;
        for (final String part : parts) {
            try { out[k] = Integer.parseInt(part.trim()); k++; }
            catch (final NumberFormatException ignored) { /* skip a malformed entry */ }
        }
        return k == out.length ? out : java.util.Arrays.copyOf(out, k);
    }

    /** The first chapter of a book as printed here, or {@code null} if it has none. */
    Integer firstChapterForBook(final int anIndex) {
        final int[] chapters = chapterNumbersForBook(anIndex);
        return chapters.length == 0 ? null : chapters[0];
    }

    /** The last chapter of a book as printed here, or {@code null} if it has none. */
    Integer lastChapterForBook(final int anIndex) {
        final int[] chapters = chapterNumbersForBook(anIndex);
        return chapters.length == 0 ? null : chapters[chapters.length - 1];
    }

    /**
     * The next chapter after {@code aFrom} within one book, or the previous one
     * when stepping backwards; {@code null} at either end of the book.
     *
     * <p>Strictly next-greater / next-smaller rather than an index lookup, so it
     * behaves sensibly even if {@code aFrom} is not itself one of the book's
     * chapters — which happens when a column is showing a passage opened by
     * reference rather than by navigation.
     */
    Integer chapterStep(final int anIndex, final int aFrom, final int aDelta) {
        final int[] chapters = chapterNumbersForBook(anIndex);
        if (aDelta >= 0) {
            for (final int n : chapters) if (n > aFrom) return n;
            return null;
        }
        for (int i = chapters.length - 1; i >= 0; i--) if (chapters[i] < aFrom) return chapters[i];
        return null;
    }

    int indexOfBook(final String aBookName) {
        for (int i = 0; i < books.size(); i++)
            if (books.get(i)[0].equals(aBookName)) return i;
        return -1;
    }

    String currentBookName() {
        return books.isEmpty() ? null : books.get(bookIndex)[0];
    }

    void setVisible(final int aChapter, final String aBook) {
        visibleChapter = aChapter;
        chapterLabel.setText(col.getDisplayOptions().isContinuous() ? "" : chapterAbbrev + " " + aChapter);
        if (aBook != null && !aBook.equals(bookSelect.getValue())) {
            bookSelect.setValue(aBook);
        }
        final int bi = indexOfBook(aBook);
        if (bi >= 0) bookIndex = bi;
    }
}
