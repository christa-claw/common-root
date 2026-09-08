// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.spring.security.AuthenticationContext;
import org.religioustext.app.model.DisplayOptions;
import org.religioustext.app.model.VerseRef;
import org.religioustext.app.service.CommentQueryService;
import org.religioustext.app.service.CommentQueryService.VerseComment;
import org.religioustext.app.service.PersonalNoteService;
import org.religioustext.app.service.TextQueryService;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders the verse window for one reader column and keeps it filled as the
 * reader scrolls: it walks the fetched verse list emitting book separators and
 * chapter groups, appends/prepends forward and backward batches, and trims the
 * off-screen end to hold the window size. The scroll observer
 * ({@link ReaderScrollController}) fires load-next/load-prev into
 * {@link #loadNext}/{@link #loadPrev}; the navigation coordinator (ReaderView's
 * openAtSeq) drives {@link #clearContent}/{@link #loadCompanionFor}/
 * {@link #renderWindow}.
 *
 * <p>Extracted from ReaderView (2026-07-01), behaviour-preserving. Services are
 * injected; the few ReaderView-side computations this needs (seq of a verse in
 * the active order, the display book name, and the per-verse comment/note badges
 * — the last two headed for AnnotationsUi) come back through {@link Host}. DOM
 * work lives here; window bounds (firstSeq/lastSeq/visibleSeq) stay on ColState
 * and are read/written directly (same package).
 */
final class VerseWindowRenderer {

    /** ReaderView-side callbacks the renderer needs. */
    interface Host {
        /** Seq of a verse in the column's ACTIVE order (chronological vs canonical). */
        int activeSeq(ColState aState, VerseRef aVerseRef);
        /** Display name for a book (localized / Arabic surah / + companion Latin). */
        String bookHeading(ColState aState, String aBookName);
        /** Superscript badge for a verse that has public comments. */
        Span commentBadge(ColState aState, String aBookName, int aChapter,
                          String aVerseNo, List<VerseComment> theComments);
        /** Superscript note marker for a verse (signed-in readers only). */
        Span noteBadge(ColState aState, String anEmail, String aBookName, String aBookCode,
                      int aChapter, String aVerseNo, String anExisting);
        /** Open the annotate entry for a BARE verse (the note editor, which carries the
         *  write-comment jump) — reached by clicking the verse number when signed in. */
        void openAnnotate(ColState aState, String aBookName, String aBookCode,
                          int aChapter, String aVerseNo);
        /** Tooltip for the clickable verse number. */
        String annotateHint();
        /** Tooltip for a lineage rung that is an original-language WITNESS
         *  rather than the edition's attested parent. */
        String lineageWitnessHint();
        /** Voices this signed-in reader has muted (V17); empty when signed out.
         *  Applied to the inline bubbles here so a muted voice leaves no trace in
         *  the text \u2014 not merely absent from the panel's list. */
        java.util.Set<String> mutedVoices();
    }

    private final TextQueryService    queryService;
    private final CommentQueryService commentService;
    private final PersonalNoteService noteService;
    private final AuthenticationContext authContext;
    private final Host host;

    VerseWindowRenderer(final TextQueryService aQueryService,
                        final CommentQueryService aCommentService,
                        final PersonalNoteService aNoteService,
                        final AuthenticationContext anAuthContext,
                        final Host aHost) {
        this.queryService   = aQueryService;
        this.commentService = aCommentService;
        this.noteService    = aNoteService;
        this.authContext    = anAuthContext;
        this.host           = aHost;
    }

    /** Populate state.companionText (canonicalSeq -> paired verse text) for the
     *  canonical-seq span of the given primary verses. No-op when the companion
     *  is hidden or absent. Merges into the existing map; openAtSeq clears it
     *  first, forward/backward batches extend it. */
    void loadCompanionFor(final ColState aState, final List<VerseRef> theVerses) {
        final boolean companionOn = aState.showCompanion && aState.companionId != null;
        final List<org.religioustext.app.ui.views.reader.SourceCatalog.Rung> rungs =
            aState.col.getDisplayOptions().isShowVerses() ? aState.openRungs() : List.of();
        if ((!companionOn && rungs.isEmpty()) || theVerses.isEmpty()) return;
        int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
        for (final VerseRef v : theVerses) {
            final Integer c = v.getGlobalCanonicalSeq();
            if (c == null) continue;
            if (c < min) min = c;
            if (c > max) max = c;
        }
        if (min > max) return;
        if (companionOn)
            for (final VerseRef cv : queryService.versesByCanonicalRange(aState.companionId, min, max)) {
                final Integer c = cv.getGlobalCanonicalSeq();
                if (c != null) aState.companionText.put(c, cv.getDisplayContent(false));
            }
        // Antecedent rungs align by the REFERENCE axis (book code + chapter +
        // verse), which is edition- and language-independent — canonical seqs
        // are per-edition and don't agree between two Bibles. Fetch each rung's
        // copy of every chapter present in this batch; a rung lacking the
        // chapter just contributes nothing (the pruning signal).
        if (!rungs.isEmpty()) {
            final java.util.LinkedHashSet<String> chapters = new java.util.LinkedHashSet<>();
            for (final VerseRef v : theVerses) {
                final String code = v.getBookCode();
                if (code != null && !code.isBlank()) chapters.add(code + "|" + v.getChapterNumber());
            }
            for (final org.religioustext.app.ui.views.reader.SourceCatalog.Rung rung : rungs) {
                final String rid = rung.row()[0];
                final java.util.Map<String,String> map =
                    aState.rungTexts.computeIfAbsent(rid, k -> new java.util.HashMap<>());
                for (final String ch : chapters) {
                    final int cut = ch.lastIndexOf('|');
                    final String code = ch.substring(0, cut);
                    final int chapter = Integer.parseInt(ch.substring(cut + 1));
                    for (final VerseRef cv : queryService.versesByCodeChapter(rid, code, chapter))
                        map.put(code + "|" + chapter + "|" + cv.getVerseNumber(),
                                cv.getDisplayContent(false));
                }
            }
        }
    }

    void renderWindow(final ColState aState, final List<VerseRef> aWindow) {
        String  prevBook = null;
        Integer prevChap = null;
        final boolean continuous = aState.col.getDisplayOptions().isContinuous();

        // Group consecutive verses of the same (book, chapter) into one chapter
        // div (keeps the per-chapter heading + inline verse flow intact).
        int i = 0;
        while (i < aWindow.size()) {
            final VerseRef first = aWindow.get(i);
            final String book = first.getBookName();
            final int    chap = first.getChapterNumber();

            int j = i;
            final List<VerseRef> group = new ArrayList<>();
            while (j < aWindow.size()
                   && book.equals(aWindow.get(j).getBookName())
                   && aWindow.get(j).getChapterNumber() == chap) {
                group.add(aWindow.get(j));
                j++;
            }

            final boolean continues =
                book.equals(prevBook) && prevChap != null && prevChap == chap;
            if (!book.equals(prevBook)) {
                appendBefore(aState, buildBookSeparator(aState, book));
            }
            appendBefore(aState, buildChapterGroup(aState, book, chap, group,
                host.activeSeq(aState, first), !continues));

            prevBook = book;
            prevChap = chap;
            i = j;
        }
    }

    /** Insert a node just before the bottom sentinel (or append if none yet). */
    private void appendBefore(final ColState aState, final Div aNode) {
        if (aState.botSentinel != null) {
            final com.vaadin.flow.dom.Element parent = aState.content.getElement();
            final int botIdx = parent.indexOfChild(aState.botSentinel.getElement());
            parent.insertChild(botIdx, aNode.getElement());
        } else {
            aState.content.add(aNode);
        }
    }

    /** Prepend a node right after the top sentinel (index 0 is the top sentinel). */
    private void prepend(final ColState aState, final Div aNode) {
        aState.content.getElement().insertChild(1, aNode.getElement());
    }

    void clearContent(final ColState aState) {
        aState.content.getChildren()
            .filter(c -> c.getElement().hasAttribute("data-chapter")
                      || c.getElement().hasAttribute("data-separator"))
            .toList()
            .forEach(aState.content::remove);
        aState.content.getChildren()
            .filter(c -> c instanceof Span && !c.getElement().hasAttribute("id"))
            .toList()
            .forEach(aState.content::remove);
    }

    private Div buildBookSeparator(final ColState aState, final String aBookName) {
        final Div sep = new Div();
        sep.getElement().setAttribute("data-book", aBookName);
        sep.getElement().setAttribute("data-separator", "true");
        sep.getStyle()
            .set("display", "flex")
            .set("align-items", "center")
            .set("gap", "12px")
            .set("margin", "28px 0 18px 0")
            .set("font-family", "inherit")
            .set("white-space", "normal");
        final Div ruleL = new Div();
        ruleL.getStyle().set("flex", "1").set("height", "2px")
            .set("background", "var(--lumo-contrast-40pct)");
        final Span name = new Span(host.bookHeading(aState, aBookName));
        name.getStyle().set("font-size", "15px").set("font-weight", "700")
            .set("color", "var(--lumo-body-text-color)")
            .set("text-transform", "none").set("letter-spacing", "0.02em")
            .set("white-space", "nowrap");
        final Div ruleR = new Div();
        ruleR.getStyle().set("flex", "1").set("height", "2px")
            .set("background", "var(--lumo-contrast-40pct)");
        sep.add(ruleL, name, ruleR);
        return sep;
    }

    private Div buildChapterGroup(final ColState aState, final String aBookName,
                                  final int aChapter, final List<VerseRef> theVerses,
                                  final int aSeq, final boolean aWithHeading) {
        final Div div = new Div();
        div.getElement().setAttribute("data-book", aBookName);
        div.getElement().setAttribute("data-chapter", String.valueOf(aChapter));
        div.getElement().setAttribute("data-seq", String.valueOf(aSeq));
        // The seq of the LAST verse in this group. Trimming from the bottom uses
        // this to set the window's lower-detail bound correctly: data-seq alone
        // (the FIRST verse's seq) would set lastSeq too low and make the next
        // forward load re-fetch verses still on screen — duplicate chapters.
        if (!theVerses.isEmpty()) {
            div.getElement().setAttribute("data-seq-last",
                String.valueOf(host.activeSeq(aState, theVerses.get(theVerses.size() - 1))));
        }
        final DisplayOptions opts = aState.col.getDisplayOptions();
        final boolean continuous = opts.isContinuous();
        div.getStyle().set("padding-bottom", continuous ? "0" : "12px");
        div.getStyle().set("scroll-margin-top", "92px");

        if (theVerses.isEmpty()) {
            // Should not happen in the windowed model (we only render verses we
            // actually fetched), but guard anyway — emit nothing visible.
            return div;
        }

        if (opts.isAllCaps()) {
            final StringBuilder raw = new StringBuilder();
            // Scriptio continua: uppercase (where the script has case), then
            // strip whitespace and everything that is not a letter or digit.
            // Unicode classes, NOT [^A-Z0-9]: the ASCII version deleted Hebrew/
            // Greek/Arabic/Cyrillic/CJK text entirely and silently dropped
            // accented Latin letters (Finnish ä/ö!). Combining marks (\p{M})
            // are intentionally stripped too — for pointed Hebrew (WLC) that
            // yields unvocalised consonantal text, which is exactly what the
            // ancient manuscripts this mode recreates looked like.
            for (final VerseRef v : theVerses)
                raw.append(v.getDisplayContent(true)
                    .toUpperCase(java.util.Locale.ROOT)
                    .replaceAll("[^\\p{L}\\p{N}]", ""));
            div.getStyle().set("display", "inline").set("margin", "0").set("padding", "0");
            div.setText(raw.toString());
            return div;
        }

        if (opts.isContinuous()) {
            final StringBuilder prose = new StringBuilder();
            for (final VerseRef v : theVerses)
                prose.append(v.getDisplayContent(false).strip()).append(' ');
            div.getStyle().set("display", "inline").set("margin", "0").set("padding", "0");
            div.setText(prose.toString());
            return div;
        }

        if (aWithHeading && opts.isShowChapters()) {
            // Full reference (book + chapter), not just "Chapter N". In
            // chronological order books legitimately interleave (Psalms among
            // Samuel, Chronicles beside Samuel), and a bare "Chapter 7" gives no
            // clue which book it belongs to — the book separators only fire on a
            // book-NAME change, so an intra-book jump shows none. The reference
            // heading keeps every group unambiguous in any order.
            final H2 h = new H2(host.bookHeading(aState, aBookName) + " " + aChapter);
            h.getElement().setAttribute("data-chapter-heading", "true");
            h.getStyle().set("font-size", "15px").set("margin", "8px 0 4px 0")
             .set("color", "var(--lumo-secondary-text-color)");
            div.add(h);
        }

        final Div text = new Div();
        text.getStyle()
            .set("line-height", "1.8")
            .set("text-align", "justify")
            .set("overflow-wrap", "break-word");

        // The signed-in reader's identity first — the comment fetch overlays their own
        // comments (including private drafts) on the public map.
        final boolean signedIn  = authContext.isAuthenticated();
        final String  noteEmail = signedIn ? authContext.getPrincipalName().orElse(null) : null;

        // Comments citing this chapter's verses (public + the caller's own overlay), keyed by
        // verse number. Only fetched for verse-numbered rendering — the badge anchors to the
        // verse, which continuous/all-caps modes don't show.
        final java.util.Map<String, List<VerseComment>> verseComments =
            opts.isShowVerses() && aState.showComments
                ? commentsFor(theVerses.get(0).getBookCode(), aChapter,
                              aState.col.getSourceId(), noteEmail)
                : java.util.Map.of();
        final java.util.Map<String, String> verseNotes =
            (signedIn && opts.isShowVerses() && noteEmail != null)
                ? noteService.forChapter(noteEmail, theVerses.get(0).getBookCode(), aChapter)
                : java.util.Map.of();

        // In-column companion (e.g. a Qur'an translation beneath each ayah).
        // Render each verse as its own block: the primary line, then the paired
        // text muted below it in its own reading direction. One scroll container,
        // so the two are inherently locked together.
        // Lineage rungs are VERSE-GRANULAR: they render only in the modes
        // that show verses as addressable units (Verses / Titles). Original,
        // Continuous and Chapters exist to present unbroken flow — per-verse
        // interleaving would destroy exactly what they demonstrate — so there
        // the rungs (and their controls) are suppressed, with the depth
        // REMEMBERED for the next verse-granular mode.
        final boolean rungsVisible = opts.isShowVerses() && !aState.openRungs().isEmpty();
        if ((aState.showCompanion && aState.companionId != null) || rungsVisible) {
            for (final VerseRef verse : theVerses) {
                final String body = verse.getDisplayContent(false);
                if (body == null || body.isBlank()) continue;   // skip empty verses (defensive; Qur'an ayat are never empty)
                final Div ayah = new Div();
                ayah.getStyle().set("margin", "0 0 14px 0");
                final Div primary = new Div();
                primary.getStyle().set("line-height", "1.9").set("font-size", "1.25em");
                if (opts.isShowVerses()) {
                    final Span num = new Span(verse.getVerseNumber() + " ");
                    num.getStyle().set("font-size", "10px")
                        .set("color", "var(--lumo-secondary-text-color)")
                        .set("vertical-align", "super");
                    final String code = verse.getBookCode();
                    if (code != null && !code.isBlank())
                        num.setId("v-" + code + "-" + aChapter + "-" + verse.getVerseNumber());
                    if (signedIn) {   // annotate entry — same as the plain-verse branch
                        final String verseNo = String.valueOf(verse.getVerseNumber());
                        final String vCode = verse.getBookCode();
                        num.getStyle().set("cursor", "pointer");
                        num.getElement().setAttribute("title", host.annotateHint());
                        num.addClickListener(ev ->
                            host.openAnnotate(aState, aBookName, vCode, aChapter, verseNo));
                    }
                    primary.add(num);
                }
                primary.add(new Span(body));
                final List<VerseComment> ayahComments =
                    verseComments.get(String.valueOf(verse.getVerseNumber()));
                if (ayahComments != null)
                    primary.add(host.commentBadge(aState, aBookName, aChapter,
                        String.valueOf(verse.getVerseNumber()), ayahComments));
                if (signedIn && opts.isShowVerses()
                        && verseNotes.get(String.valueOf(verse.getVerseNumber())) != null)
                    primary.add(host.noteBadge(aState, noteEmail, aBookName, verse.getBookCode(), aChapter,
                        String.valueOf(verse.getVerseNumber()),
                        verseNotes.get(String.valueOf(verse.getVerseNumber()))));
                ayah.add(primary);
                final Integer cseq = verse.getGlobalCanonicalSeq();
                final String comp = (cseq != null) ? aState.companionText.get(cseq) : null;
                if (comp != null && !comp.isBlank()) {
                    final Div c = new Div();
                    c.setText(comp);
                    c.getStyle()
                        .set("direction", aState.companionRtl ? "rtl" : "ltr")
                        .set("text-align", aState.companionRtl ? "right" : "left")
                        .set("font-size", "0.85em")
                        .set("line-height", "1.6")
                        .set("color", "var(--lumo-secondary-text-color)")
                        .set("margin-top", "4px")
                        .set("padding-inline-start", "10px")
                        .set("border-inline-start", "2px solid var(--lumo-contrast-20pct)");
                    ayah.add(c);
                }
                // Lineage rungs: one muted line per related text that has this
                // verse (a rung lacking it is simply pruned), deeper generations
                // progressively inset and dimmer, each led by a small
                // abbreviation chip so the century is identifiable.
                //
                // WHICH SIDE. At positive depth these are the texts this edition
                // stands on and they render BENEATH the verse, oldest lowest. At
                // negative depth they are the texts that stand on it and they
                // render ABOVE, so the column reads down the page as time runs
                // forward and the selected edition keeps its place in the middle.
                // addComponentAsFirst in nearest-first order puts the furthest
                // descendant at the top and the nearest just above the verse.
                final boolean below = aState.rungDepth >= 0;
                final java.util.List<java.util.List<org.religioustext.app.ui.views.reader.SourceCatalog.Rung>> ladder =
                    below ? aState.lineage : aState.descendants;
                final int rungLimit = Math.abs(aState.rungDepth);
                final String rungKey = verse.getBookCode() == null ? null
                    : verse.getBookCode() + "|" + aChapter + "|" + verse.getVerseNumber();
                int shownRungs = 0;
                rungLoop:
                for (int g = 0; rungsVisible && g < ladder.size(); g++) {
                    for (final org.religioustext.app.ui.views.reader.SourceCatalog.Rung rungInfo : ladder.get(g)) {
                        if (shownRungs >= rungLimit) break rungLoop;
                        shownRungs++;
                        final String[] rung = rungInfo.row();
                        final java.util.Map<String,String> tmap = aState.rungTexts.get(rung[0]);
                        final String rtext = (rungKey != null && tmap != null) ? tmap.get(rungKey) : null;
                        if (rtext == null || rtext.isBlank()) continue;
                        final boolean rungRtl = rung.length > 3 && "rtl".equalsIgnoreCase(rung[3]);
                        final String abbr = rung.length > 2 && rung[2] != null ? rung[2] : rung[0];
                        final String year = org.religioustext.app.ui.views.reader.SourceRow.of(rung).year();
                        final String chipLabel = year.isBlank() ? abbr : abbr + " " + year;
                        // A witness rung (original-language floor) is clearly
                        // MARKED as not-the-attested-parent: ≈ prefix, dashed
                        // chip, explanatory tooltip. Attested rungs stay plain.
                        final Span chip = new Span(rungInfo.attested() ? chipLabel : "\u2248 " + chipLabel);
                        chip.getStyle()
                            .set("background", "var(--lumo-contrast-10pct)")
                            .set("border-radius", "3px").set("font-size", "9px")
                            .set("padding", "0 4px").set("margin-inline-end", "6px")
                            .set("vertical-align", "middle")
                            .set("color", "var(--lumo-secondary-text-color)");
                        if (!rungInfo.attested()) {
                            chip.getStyle().set("border", "1px dashed var(--lumo-contrast-40pct)")
                                           .set("background", "transparent");
                            chip.getElement().setAttribute("title", host.lineageWitnessHint());
                        }
                        final Div line = new Div();
                        line.add(chip, new Span(rtext));
                        line.getStyle()
                            .set("direction", rungRtl ? "rtl" : "ltr")
                            .set("text-align", rungRtl ? "right" : "left")
                            .set("font-size", "0.85em").set("line-height", "1.6")
                            .set("color", "var(--lumo-secondary-text-color)")
                            .set(below ? "margin-top" : "margin-bottom", "4px")
                            .set("opacity", String.valueOf(Math.max(0.55, 0.95 - 0.15 * g)))
                            .set("padding-inline-start", (10 + 8 * g) + "px")
                            .set("border-inline-start", "2px solid var(--lumo-contrast-20pct)");
                        if (below) ayah.add(line);
                        else       ayah.addComponentAsFirst(line);
                    }
                }
                text.add(ayah);
            }
            div.add(text);
            return div;
        }

        appendNormalVerses(text.getElement(), aState, theVerses, aChapter, aBookName, opts,
            verseComments, verseNotes, signedIn, noteEmail);
        div.add(text);
        return div;
    }

    /** Fill a chapter group's inline text element with verse spans (number, body,
     *  comment + note badges, section titles). Extracted so a forward scroll-fill
     *  that CONTINUES the same chapter can append straight into the existing
     *  group's text — keeping the chapter one seamless inline flow rather than
     *  splitting it into separate blocks (which left a visible gap). */
    private void appendNormalVerses(final com.vaadin.flow.dom.Element aTarget,
            final ColState aState, final List<VerseRef> theVerses, final int aChapter,
            final String aBookName, final DisplayOptions anOptions,
            final java.util.Map<String, List<VerseComment>> theVerseComments,
            final java.util.Map<String, String> theVerseNotes,
            final boolean aSignedInFlag, final String aNoteEmail) {
        String lastSectionTitle = null;
        for (final VerseRef verse : theVerses) {
            final String body = verse.getDisplayContent(false);
            if (body == null || body.isBlank()) continue;   // skip empty verses (e.g. hadith numbering gaps)
            if (anOptions.isShowChapterTitles()) {
                final String st = verse.getChapterTitle();
                if (st != null && !st.isBlank() && !st.equals(lastSectionTitle)) {
                    final Div section = new Div();
                    section.setText(st);
                    section.getStyle()
                        .set("font-weight", "700")
                        .set("font-style", "italic")
                        .set("font-size", "17px")
                        .set("text-align", "left")
                        .set("color", "var(--lumo-primary-text-color)")
                        .set("margin", "22px 0 8px 0")
                        .set("padding-top", "10px")
                        .set("border-top", "1px solid var(--lumo-contrast-10pct)");
                    aTarget.appendChild(section.getElement());
                    lastSectionTitle = st;
                }
            }
            if (anOptions.isShowVerses()) {
                final Span num = new Span(verse.getVerseNumber() + " ");
                num.getStyle()
                    .set("font-size", "10px")
                    .set("color", "var(--lumo-secondary-text-color)")
                    .set("vertical-align", "super");
                final String code = verse.getBookCode();
                if (code != null && !code.isBlank())
                    num.setId("v-" + code + "-" + aChapter + "-" + verse.getVerseNumber());
                // Signed in: the verse number is the annotate entry for a BARE verse —
                // click opens the note editor (which offers the write-comment jump), so
                // every verse has a path to a note/comment, badges or not.
                if (aSignedInFlag) {
                    final String verseNo = String.valueOf(verse.getVerseNumber());
                    final String bookCode = verse.getBookCode();
                    num.getStyle().set("cursor", "pointer");
                    num.getElement().setAttribute("title", host.annotateHint());
                    num.addClickListener(e ->
                        host.openAnnotate(aState, aBookName, bookCode, aChapter, verseNo));
                }
                aTarget.appendChild(num.getElement());
            }
            aTarget.appendChild(new Span(body + " ").getElement());
            final List<VerseComment> vcs =
                theVerseComments.get(String.valueOf(verse.getVerseNumber()));
            if (vcs != null) {
                aTarget.appendChild(host.commentBadge(aState, aBookName, aChapter,
                    String.valueOf(verse.getVerseNumber()), vcs).getElement());
                aTarget.appendChild(new Span(" ").getElement());
            }
            // Note marker ONLY where a note exists — no icon on every verse (keeps
            // the text pristine). Adding a note on a bare verse comes via the
            // annotation pass (hover / notes-mode), not an always-on per-verse icon.
            if (aSignedInFlag && anOptions.isShowVerses()
                    && theVerseNotes.get(String.valueOf(verse.getVerseNumber())) != null) {
                aTarget.appendChild(host.noteBadge(aState, aNoteEmail, aBookName, verse.getBookCode(),
                    aChapter, String.valueOf(verse.getVerseNumber()),
                    theVerseNotes.get(String.valueOf(verse.getVerseNumber()))).getElement());
                aTarget.appendChild(new Span(" ").getElement());
            }
        }
    }

    /** Append continuation verses into the LAST rendered chapter group's text
     *  element, so a chapter split across scroll batches stays one inline flow.
     *  Returns false if there is no suitable existing group (caller then builds a
     *  normal group instead). */
    private boolean mergeIntoLastGroup(final ColState aState, final String aBookName,
                                       final int aChapter, final List<VerseRef> aGroup) {
        final List<com.vaadin.flow.component.Component> groups = renderedGroups(aState);
        if (groups.isEmpty() || aGroup.isEmpty()) return false;
        final com.vaadin.flow.dom.Element groupEl =
            groups.get(groups.size() - 1).getElement();
        if (groupEl.getChildCount() == 0) return false;
        final com.vaadin.flow.dom.Element textEl =
            groupEl.getChild(groupEl.getChildCount() - 1);

        final DisplayOptions opts = aState.col.getDisplayOptions();
        final boolean signedIn  = authContext.isAuthenticated();
        final String  noteEmail = signedIn ? authContext.getPrincipalName().orElse(null) : null;
        final String  bookCode  = aGroup.get(0).getBookCode();
        final java.util.Map<String, List<VerseComment>> verseComments =
            (opts.isShowVerses() && aState.showComments)
                ? commentsFor(bookCode, aChapter, aState.col.getSourceId(), noteEmail)
                : java.util.Map.of();
        final java.util.Map<String, String> verseNotes =
            (signedIn && opts.isShowVerses() && noteEmail != null)
                ? noteService.forChapter(noteEmail, bookCode, aChapter) : java.util.Map.of();

        appendNormalVerses(textEl, aState, aGroup, aChapter, aBookName, opts,
            verseComments, verseNotes, signedIn, noteEmail);
        groupEl.setAttribute("data-seq-last",
            String.valueOf(host.activeSeq(aState, aGroup.get(aGroup.size() - 1))));
        return true;
    }

    /** Forward (downward) fill: fetch the next BATCH after lastSeq, append, then
     *  trim from the top to hold the window size. */
    void loadNext(final ColState aState) {
        if (aState.col.getSourceId() == null || aState.lastSeq < 0) return;
        final List<VerseRef> batch = queryService.verseWindowFrom(
            aState.col.getSourceId(), aState.col.getDisplayOptions(),
            aState.lastSeq + 1, ReaderView.BATCH_VERSES);
        if (batch.isEmpty()) return;
        loadCompanionFor(aState, batch);

        // Book/chapter of the last currently-rendered group: lets the first
        // appended group continue it (no separator/heading) and — in inline modes
        // — merge straight into its text for seamless flow instead of a new block.
        final DisplayOptions opts = aState.col.getDisplayOptions();
        final boolean inlineMode =
            !aState.showCompanion && !opts.isContinuous() && !opts.isAllCaps();
        String prevBook = lastRenderedBook(aState);
        Integer prevChap = lastRenderedChapter(aState);

        boolean firstIter = true;
        int i = 0;
        while (i < batch.size()) {
            final VerseRef first = batch.get(i);
            final String book = first.getBookName();
            final int    chap = first.getChapterNumber();
            int j = i;
            final List<VerseRef> group = new ArrayList<>();
            while (j < batch.size()
                   && book.equals(batch.get(j).getBookName())
                   && batch.get(j).getChapterNumber() == chap) {
                group.add(batch.get(j)); j++;
            }
            final boolean continues =
                book.equals(prevBook) && prevChap != null && prevChap == chap;
            if (firstIter && continues && inlineMode
                    && mergeIntoLastGroup(aState, book, chap, group)) {
                // merged into the existing chapter group — no new block, no gap
            } else {
                if (!book.equals(prevBook)) appendBefore(aState, buildBookSeparator(aState, book));
                appendBefore(aState, buildChapterGroup(aState, book, chap, group, host.activeSeq(aState, first), !continues));
            }
            prevBook = book; prevChap = chap;
            firstIter = false;
            i = j;
        }
        aState.lastSeq = host.activeSeq(aState, batch.get(batch.size() - 1));
        trimTop(aState);
    }

    /** Backward (upward) fill: fetch the BATCH before firstSeq, prepend in
     *  reverse so reading order is preserved, then trim from the bottom. */
    void loadPrev(final ColState aState) {
        if (aState.col.getSourceId() == null || aState.firstSeq < 0) return;
        final List<VerseRef> batch = queryService.verseWindowBefore(
            aState.col.getSourceId(), aState.col.getDisplayOptions(),
            aState.firstSeq, ReaderView.BATCH_VERSES);   // ascending order
        if (batch.isEmpty()) return;
        loadCompanionFor(aState, batch);

        // The book/chapter currently at the TOP of the DOM (the group the
        // prepended content will sit above). Used to decide whether the last
        // prepended group needs its own separator vs the existing top.
        final String topBook = firstRenderedBook(aState);
        // The group currently at the very top of the DOM and its chapter, so a
        // backward fill that brings the EARLIER part of that same chapter above
        // it can strip the now-duplicate heading off this continuation group.
        final java.util.List<com.vaadin.flow.component.Component> existingTopGroups = renderedGroups(aState);
        final com.vaadin.flow.component.Component oldTopGroup =
            existingTopGroups.isEmpty() ? null : existingTopGroups.get(0);
        Integer oldTopChap = null;
        if (oldTopGroup != null) {
            try { oldTopChap = Integer.valueOf(
                oldTopGroup.getElement().getAttribute("data-chapter")); }
            catch (final Exception ignored) {}
        }

        // Group the batch, then prepend groups in REVERSE so the earliest ends
        // up topmost. Build the node list first, then insert each at index 1.
        final List<Div> nodes = new ArrayList<>();
        int i = 0;
        String prevBook = null;
        while (i < batch.size()) {
            final VerseRef first = batch.get(i);
            final String book = first.getBookName();
            final int    chap = first.getChapterNumber();
            int j = i;
            final List<VerseRef> group = new ArrayList<>();
            while (j < batch.size()
                   && book.equals(batch.get(j).getBookName())
                   && batch.get(j).getChapterNumber() == chap) {
                group.add(batch.get(j)); j++;
            }
            // Separator BEFORE this group if its book differs from the group
            // before it within the batch; the very first group's separator is
            // decided against the existing DOM top below.
            if (prevBook != null && !book.equals(prevBook)) {
                nodes.add(buildBookSeparator(aState, book));
            }
            nodes.add(buildChapterGroup(aState, book, chap, group, host.activeSeq(aState, first), true));
            prevBook = book;
            i = j;
        }
        // If the batch's last book differs from the current DOM top book, the
        // existing top needs a separator above it (now that a different book sits
        // above). Simplest: ensure a separator for topBook is present above the
        // old top group. We prepend a separator for topBook first (it will end up
        // between the batch and the old content) only when books differ.
        final String batchLastBook = batch.get(batch.size() - 1).getBookName();
        if (topBook != null && !topBook.equals(batchLastBook)) {
            prepend(aState, buildBookSeparator(aState, topBook));
        }
        // Prepend nodes in reverse (last node first → earliest ends up on top).
        for (int k = nodes.size() - 1; k >= 0; k--) {
            prepend(aState, nodes.get(k));
        }
        // If the batch's bottom-most group is the SAME chapter as the old DOM top,
        // that chapter's start now sits in the prepended content (which carries
        // the heading), so the old top group's heading is a duplicate — strip it.
        final int batchLastChap = batch.get(batch.size() - 1).getChapterNumber();
        if (oldTopGroup != null && oldTopChap != null
                && batchLastBook.equals(topBook)
                && batchLastChap == oldTopChap) {
            stripChapterHeading(oldTopGroup);
        }
        aState.firstSeq = host.activeSeq(aState, batch.get(0));
        trimBottom(aState);
    }

    /** The chapter's comments for the badges: the public map, overlaid with the caller's OWN
     *  comments (including private drafts) when signed in. An own comment that is also public
     *  REPLACES its public twin so the card carries the own/unpublished flags.
     *  aSourceId is the column's corpus source id — it scopes edition-bound
     *  translator notes to their own edition's column (see forChapter). */
    private java.util.Map<String, List<VerseComment>> commentsFor(final String aBookCode,
            final int aChapter, final String aSourceId, final String anEmail) {
        final java.util.Map<String, List<VerseComment>> pub =
            commentService.forChapter(aBookCode, aChapter, aSourceId);
        if (anEmail == null || anEmail.isBlank()) return pub;
        final java.util.Map<String, List<VerseComment>> own =
            commentService.ownForChapter(anEmail, aBookCode, aChapter);
        if (own.isEmpty()) return muted(pub);
        final java.util.Map<String, List<VerseComment>> merged = new java.util.HashMap<>();
        pub.forEach((verse, list) -> merged.put(verse, new java.util.ArrayList<>(list)));
        own.forEach((verse, list) -> {
            final List<VerseComment> target =
                merged.computeIfAbsent(verse, k -> new java.util.ArrayList<>());
            for (final VerseComment o : list) {
                if (o.publicId() != null)
                    target.removeIf(p -> o.publicId().equals(p.publicId()));
                target.add(o);
            }
        });
        return muted(merged);
    }

    /** Drop the reader's muted voices, and any verse left with nothing to say.
     *
     *  <p>Removing the verse KEY as well as the comments is the point: the caller
     *  renders a bubble for every key present, so a verse whose only comment was
     *  muted must disappear from this map entirely or it renders an empty badge
     *  that opens onto nothing. Own comments survive regardless (see
     *  {@link CommentQueryService#isMuted}). */
    private java.util.Map<String, List<VerseComment>> muted(
            final java.util.Map<String, List<VerseComment>> aByVerse) {
        final java.util.Set<String> mutes = host.mutedVoices();
        if (mutes == null || mutes.isEmpty() || aByVerse.isEmpty()) return aByVerse;
        final java.util.Map<String, List<VerseComment>> out = new java.util.HashMap<>();
        aByVerse.forEach((verse, list) -> {
            final List<VerseComment> kept = list.stream()
                .filter(c -> !CommentQueryService.isMuted(c, mutes)).toList();
            if (!kept.isEmpty()) out.put(verse, kept);
        });
        return out;
    }

    private String lastRenderedBook(final ColState aState) {
        final List<com.vaadin.flow.component.Component> groups = renderedGroups(aState);
        if (groups.isEmpty()) return null;
        return groups.get(groups.size() - 1).getElement().getAttribute("data-book");
    }
    private Integer lastRenderedChapter(final ColState aState) {
        final List<com.vaadin.flow.component.Component> groups = renderedGroups(aState);
        if (groups.isEmpty()) return null;
        try { return Integer.valueOf(groups.get(groups.size() - 1).getElement().getAttribute("data-chapter")); }
        catch (final Exception e) { return null; }
    }
    private String firstRenderedBook(final ColState aState) {
        final List<com.vaadin.flow.component.Component> groups = renderedGroups(aState);
        if (groups.isEmpty()) return null;
        return groups.get(0).getElement().getAttribute("data-book");
    }

    /** Remove the chapter heading (if present) from a chapter group — used when a
     *  backward fill turns an existing group into a chapter continuation. */
    private void stripChapterHeading(final com.vaadin.flow.component.Component aGroup) {
        aGroup.getElement().getChildren()
            .filter(e -> e.hasAttribute("data-chapter-heading"))
            .findFirst()
            .ifPresent(com.vaadin.flow.dom.Element::removeFromParent);
    }

    private List<com.vaadin.flow.component.Component> renderedGroups(final ColState aState) {
        return aState.content.getChildren()
            .filter(c -> c.getElement().hasAttribute("data-chapter")
                      && !c.getElement().hasAttribute("data-separator"))
            .toList();
    }

    /** Trim chapter groups (and their preceding separators) from the TOP until
     *  the window holds ≤ WINDOW_VERSES. We approximate verse count by groups,
     *  but to stay precise we trim by recomputing firstSeq from the new top. */
    private void trimTop(final ColState aState) {
        // Count verses currently in DOM by summing group sizes is costly; instead
        // bound the number of GROUPS so the DOM stays roughly WINDOW_VERSES. A
        // group ≈ one chapter; cap groups so total verses ≈ window. Use a generous
        // group cap derived from the window (avg ~25 verses/chapter ⇒ ~6 groups
        // per viewport-buffer; keep a safe ceiling).
        final int maxGroups = Math.max(8, ReaderView.WINDOW_VERSES / 8);
        List<com.vaadin.flow.component.Component> groups = renderedGroups(aState);
        while (groups.size() > maxGroups) {
            final com.vaadin.flow.component.Component top = groups.get(0);
            // Remove a leading separator sitting above this group, if any.
            removeSeparatorAbove(aState, top);
            aState.content.remove(top);
            groups = renderedGroups(aState);
            if (!groups.isEmpty()) {
                final String seqStr = groups.get(0).getElement().getAttribute("data-seq");
                try { aState.firstSeq = Integer.parseInt(seqStr); } catch (final Exception ignored) {}
            }
        }
    }

    /** Trim chapter groups from the BOTTOM until the window holds ≤ WINDOW_VERSES. */
    private void trimBottom(final ColState aState) {
        final int maxGroups = Math.max(8, ReaderView.WINDOW_VERSES / 8);
        List<com.vaadin.flow.component.Component> groups = renderedGroups(aState);
        while (groups.size() > maxGroups) {
            final com.vaadin.flow.component.Component bottom = groups.get(groups.size() - 1);
            removeSeparatorAbove(aState, bottom);
            aState.content.remove(bottom);
            groups = renderedGroups(aState);
            if (!groups.isEmpty()) {
                final String seqStr = groups.get(groups.size() - 1).getElement().getAttribute("data-seq");
                try { aState.lastSeq = Integer.parseInt(seqStr); } catch (final Exception ignored) {}
            }
        }
    }

    /** Remove a book separator immediately preceding the given group (if the node
     *  just above it in the DOM is a separator). */
    private void removeSeparatorAbove(final ColState aState, final com.vaadin.flow.component.Component aGroup) {
        final com.vaadin.flow.dom.Element parent = aState.content.getElement();
        final int idx = parent.indexOfChild(aGroup.getElement());
        if (idx > 0) {
            final com.vaadin.flow.dom.Element above = parent.getChild(idx - 1);
            if (above.hasAttribute("data-separator")) above.removeFromParent();
        }
    }
}
