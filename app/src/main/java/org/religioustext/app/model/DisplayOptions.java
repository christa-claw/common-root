// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model;

/**
 * Encapsulates display state for one reader column.
 *
 * A single DisplayMode dropdown controls all structural display options.
 * Historical context for each mode is shown as a tooltip in the UI.
 */
public final class DisplayOptions {

    private DisplayMode mode      = DisplayMode.CHAPTERS_VERSES;
    private OrderMode   orderMode = OrderMode.CANONICAL;

    /**
     * ORIGINAL          : continuous uppercase, no spaces, no chapter/verse breaks
     * ORIGINAL_SIMPLE   : continuous natural-case prose, word spaces + punctuation kept,
     *                     but no chapter/verse numbers or headings
     * CHAPTERS          : chapter headings only, prose flow within
     * CHAPTERS_VERSES   : chapter headings + inline verse numbers (default)
     * TITLES            : chapter headings + section titles + verses (NIV carries
     *                     title data; other translations fall back to plain
     *                     verses until pericope titles are backfilled)
     *
     * Flags: showChapters, showVerses, allCaps, continuous, disabled
     *   continuous = render as one flowing block with no chapter/verse chrome
     *                (true for both Scriptio modes); book-change rules still apply.
     */
    public enum DisplayMode {
         ORIGINAL(
             "Scriptio Continua"
            , "Original manuscripts were written in continuous uppercase "
            + "(scriptio continua) with no word spaces or chapter/verse divisions"
            , false, false, true,  true,  false)

        , ORIGINAL_SIMPLE(
             "Scriptio Continua (simplified)"
            , "Continuous text with modern word spacing and capitalisation, "
            + "but no chapter or verse divisions — as texts appeared before "
            + "numbering was introduced"
            , false, false, false, true,  false)

        , CHAPTERS(
             "Chapters (1227)"
            , "Chapters added by Stephen Langton, Archbishop of Canterbury, c.1227 AD"
            , true,  false, false, false, false)

        , CHAPTERS_VERSES(
             "Verses (1551)"
            , "Verses added by Robert Estienne (Stephanus), 1551 AD"
            , true,  true,  false, false, false)

        , TITLES(
             "Titles"
            , "Section titles are editorial additions varying by publisher"
            , true,  true,  false, false, false);

        private final String  label;
        private final String  tooltip;
        private final boolean showChapters;
        private final boolean showVerses;
        private final boolean allCaps;
        private final boolean continuous;
        private final boolean disabled;

        DisplayMode(
                 final String  aLabel
                , final String  aTooltip
                , final boolean aShowChapters
                , final boolean aShowVerses
                , final boolean anAllCaps
                , final boolean aContinuous
                , final boolean aDisabled) {
            this.label        = aLabel;
            this.tooltip      = aTooltip;
            this.showChapters = aShowChapters;
            this.showVerses   = aShowVerses;
            this.allCaps      = anAllCaps;
            this.continuous   = aContinuous;
            this.disabled     = aDisabled;
        }

        public String  getLabel()        { return label; }
        public String  getTooltip()      { return tooltip; }
        public boolean isShowChapters()  { return showChapters; }
        public boolean isShowVerses()    { return showVerses; }
        public boolean isAllCaps()       { return allCaps; }
        public boolean isContinuous()    { return continuous; }
        public boolean isDisabled()      { return disabled; }
    }

    public enum OrderMode {
         CANONICAL
        , CHRONOLOGICAL
        /** Hebrew-Bible arrangement: Torah → Nevi'im → Ketuvim (Chronicles last),
         *  NT unchanged after it. Driven by @globalTanakhSeq (stamp_tanakh.py);
         *  sources without the attribute fall back to canonical via seqLet. */
        , TANAKH
    }

    // ── Getters ───────────────────────────────────────────────────────

    public DisplayMode getMode()              { return mode; }
    public OrderMode   getOrderMode()         { return orderMode; }
    public boolean     isShowChapters()       { return mode.isShowChapters(); }
    public boolean     isShowChapterTitles()  { return mode == DisplayMode.TITLES; }
    public boolean     isShowVerses()         { return mode.isShowVerses(); }
    public boolean     isAllCaps()            { return mode.isAllCaps(); }
    public boolean     isContinuous()         { return mode.isContinuous(); }

    // ── Setters ───────────────────────────────────────────────────────

    public void setMode(final DisplayMode aMode) {
        this.mode = aMode;
    }

    public void setOrderMode(final OrderMode anOrderMode) {
        this.orderMode = anOrderMode;
    }

    // ── Factory ───────────────────────────────────────────────────────

    public static DisplayOptions defaults() {
        return new DisplayOptions();
    }
}
