// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model;

/**
 * Immutable reference to a single verse, self-describing
 * with full address so it can stand alone outside tree context.
 * Used in streaming/chronological mode, search results,
 * commentary anchors and cross-reference panels.
 */
public final class VerseRef {

    private final String  sourceId;
    private final String  bookName;
    private final String  bookAltName;
    private final int     chapterNumber;
    private final String  chapterTitle;
    private final int     verseNumber;
    private final String  content;
    private final Integer globalCanonicalSeq;
    private final Integer globalChronologicalSeq;
    private final Integer globalNarrativeSeq;
    private final String  note;
    private final String  bookCode;

    private VerseRef(final Builder aBuilder) {
        this.sourceId               = aBuilder.sourceId;
        this.bookName               = aBuilder.bookName;
        this.bookAltName            = aBuilder.bookAltName;
        this.chapterNumber          = aBuilder.chapterNumber;
        this.chapterTitle           = aBuilder.chapterTitle;
        this.verseNumber            = aBuilder.verseNumber;
        this.content                = aBuilder.content;
        this.globalCanonicalSeq     = aBuilder.globalCanonicalSeq;
        this.globalChronologicalSeq = aBuilder.globalChronologicalSeq;
        this.globalNarrativeSeq     = aBuilder.globalNarrativeSeq;
        this.note                   = aBuilder.note;
        this.bookCode               = aBuilder.bookCode;
    }

    // ── Getters ───────────────────────────────────────────────────────

    public String  getSourceId()               { return sourceId; }
    public String  getBookName()               { return bookName; }
    public String  getBookAltName()            { return bookAltName; }
    public int     getChapterNumber()          { return chapterNumber; }
    public String  getChapterTitle()           { return chapterTitle; }
    public int     getVerseNumber()            { return verseNumber; }
    public String  getContent()                { return content; }
    public Integer getGlobalCanonicalSeq()     { return globalCanonicalSeq; }
    public Integer getGlobalChronologicalSeq() { return globalChronologicalSeq; }
    public Integer getGlobalNarrativeSeq()     { return globalNarrativeSeq; }
    public String  getNote()                   { return note; }
    public String  getBookCode()               { return bookCode; }

    /**
     * Returns the display content, uppercased if allCaps is set.
     */
    public String getDisplayContent(final boolean anAllCaps) {
        final String cleaned = clean(content);
        return anAllCaps ? cleaned.toUpperCase() : cleaned;
    }

    /**
     * Strips artefacts from raw verse text:
     *   - Pilcrow signs and section markers (paragraph markers in source data)
     *   - Footnote text appended to verse (e.g. "1.4 the light from...")
     *   - Carriage returns, newlines, tabs
     *
     * Public + static so consumers that render raw stored text rather than going
     * through {@link #getDisplayContent(boolean)} (e.g. full-text search results,
     * whose index holds the raw BaseX text) clean it identically to the reader.
     */
    public static String clean(final String aRaw) {
        if (aRaw == null) return "";
        return aRaw
            // Strip pilcrow and other paragraph/section markers
            .replace("\u00B6", "")   // ¶
            .replace("\u00A7", "")   // §
            // Strip appended footnotes — pattern: digits.digits followed by text
            .replaceAll("\\d+\\.\\d+\\s+[A-Z][^.]*(\\..*)?$", "")
            // Strip carriage returns, newlines, tabs
            .replaceAll("[\\r\\n\\t]+", " ")
            .trim();
    }

    /**
     * Human-readable address e.g. "Genesis 1:1" or "Al-Baqarah 2:1"
     */
    public String getAddress() {
        return bookName + " " + chapterNumber + ":" + verseNumber;
    }

    // ── Builder ───────────────────────────────────────────────────────

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private String  sourceId;
        private String  bookName;
        private String  bookAltName;
        private int     chapterNumber;
        private String  chapterTitle;
        private int     verseNumber;
        private String  content;
        private Integer globalCanonicalSeq;
        private Integer globalChronologicalSeq;
        private Integer globalNarrativeSeq;
        private String  note;
        private String  bookCode;

        public Builder sourceId(final String aSourceId) {
            this.sourceId = aSourceId;
            return this;
        }

        public Builder bookName(final String aBookName) {
            this.bookName = aBookName;
            return this;
        }

        public Builder bookAltName(final String aBookAltName) {
            this.bookAltName = aBookAltName;
            return this;
        }

        public Builder chapterNumber(final int aChapterNumber) {
            this.chapterNumber = aChapterNumber;
            return this;
        }

        public Builder chapterTitle(final String aChapterTitle) {
            this.chapterTitle = aChapterTitle;
            return this;
        }

        public Builder verseNumber(final int aVerseNumber) {
            this.verseNumber = aVerseNumber;
            return this;
        }

        public Builder content(final String aContent) {
            this.content = aContent;
            return this;
        }

        public Builder globalCanonicalSeq(final Integer aGlobalCanonicalSeq) {
            this.globalCanonicalSeq = aGlobalCanonicalSeq;
            return this;
        }

        public Builder globalChronologicalSeq(final Integer aGlobalChronologicalSeq) {
            this.globalChronologicalSeq = aGlobalChronologicalSeq;
            return this;
        }

        public Builder globalNarrativeSeq(final Integer aGlobalNarrativeSeq) {
            this.globalNarrativeSeq = aGlobalNarrativeSeq;
            return this;
        }

        public Builder note(final String aNote) {
            this.note = aNote;
            return this;
        }

        public Builder bookCode(final String aBookCode) {
            this.bookCode = aBookCode;
            return this;
        }

        public VerseRef build() {
            return new VerseRef(this);
        }
    }
}
