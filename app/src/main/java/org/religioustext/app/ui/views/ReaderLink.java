// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.religioustext.app.model.DisplayOptions.DisplayMode;
import org.religioustext.app.model.DisplayOptions.OrderMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Shareable reader-link codec — pure, no Vaadin, unit-testable.
 *
 * A link captures the whole multi-column reader state as human-readable query
 * params (deliberately NOT an opaque id): self-describing, DB-free, hand-
 * authorable, crawler-readable, and reusable as a comment anchor. Shape:
 *
 *   {@code ?cols=2&sync=1&c1.src=kjv&c1.mode=titles&c1.order=chrono&c1.ref=SNG.2.16&c2...}
 *
 * - src   : the source's @abbreviation lowercased (kjv, niv, q-ar, q-en, …). The
 *           token implies the column kind; ReaderView maps it to a document.
 *           `comments` and future hadith/commentary tokens are reserved.
 * - ref   : edition/language-independent canonical reference. Bible uses the USFM
 *           code (SNG.2.16); the Qur'an uses Q.&lt;surah&gt;.&lt;ayah&gt; (Q.2.255). The
 *           verse part is optional (defaults to the chapter's first verse). The
 *           leading "Q" vs an alpha book code makes a ref self-describing.
 * - order : canon (default) | chrono.   mode : original | continuous | chapters |
 *           verses (default) | titles  (aliases: scriptio, simple, numbered).
 * - companion=N — Qur'an/hadith base columns: any N &gt;= 1 shows the in-column
 *   companion translation. Bible columns: opens the first N RUNGS of the
 *   edition's antecedent lineage (the translations it stands on) beneath each
 *   verse — ladder order, generation by generation, main line first.
 * - comments=1 shows that column's in-text comment markers (opt-in, off by default).
 *
 * Parsing is lenient: columns are capped at {@link #MAX_COLUMNS}, an unparseable
 * ref becomes null, and unknown tokens fall back to defaults — so old links keep
 * working as the app evolves and a public, hand-crafted URL has no surprises.
 */
public final class ReaderLink {

    /** Hard cap on columns a link may request (abuse guard). */
    public static final int MAX_COLUMNS = 9;

    /** Hard cap on highlight spans one column may request (abuse guard). */
    public static final int MAX_HIGHLIGHTS = 12;

    private ReaderLink() { }

    /** A canonical scripture reference, optionally a RANGE. For a Bible ref
     *  {@code unit} is the USFM book code and (a, b) = (chapter, verse); for the
     *  Qur'an {@code unit} is "Q" and (a, b) = (surah, ayah). b &lt;= 0 means
     *  "no verse given". (endA, endB) is the range's inclusive end — endB &lt;= 0
     *  means "no range". A ranged column opens at the start and highlights the
     *  whole span, so a link can point at a passage, not just its first verse
     *  (e.g. {@code ISA.52.13-53.12}, the whole fourth Servant Song). */
    public record Ref(boolean quran, String unit, int a, int b, int endA, int endB) {

        /** A plain (rangeless) reference. */
        public Ref(final boolean aQuranFlag, final String aUnit, final int aChapter, final int aVerse) {
            this(aQuranFlag, aUnit, aChapter, aVerse, 0, 0);
        }

        /** Whether this reference names a range (a valid inclusive end). */
        public boolean hasEnd() { return endB > 0; }

        public String format() {
            final StringBuilder sb = new StringBuilder(unit).append('.').append(a);
            if (b > 0) sb.append('.').append(b);
            if (hasEnd()) {
                sb.append('-');
                if (endA != a) sb.append(endA).append('.');
                sb.append(endB);
            }
            return sb.toString();
        }

        /** Parse "SNG.2.16" / "SNG.2" / "Q.2.255", or a range "ISA.52.13-53.12" /
         *  "PHP.4.10-13" / "Q.2.255-260" (after the dash: CH.VS, or VS alone for
         *  the same chapter). Returns null if malformed; a malformed or
         *  backwards range is dropped leniently, keeping the start. */
        public static Ref parse(final String aString) {
            if (aString == null || aString.isBlank()) return null;
            final String whole = aString.trim();
            final int dash = whole.indexOf('-');
            final String startPart = dash >= 0 ? whole.substring(0, dash) : whole;
            final String[] p = startPart.trim().split("\\.");
            if (p.length < 2 || p[0].isBlank()) return null;
            try {
                final int a = Integer.parseInt(p[1].trim());
                final int b = p.length >= 3 && !p[2].isBlank() ? Integer.parseInt(p[2].trim()) : 0;
                int endA = 0, endB = 0;
                if (dash >= 0 && b > 0) {
                    final String e = whole.substring(dash + 1).trim();
                    try {
                        final int dot = e.indexOf('.');
                        endA = dot >= 0 ? Integer.parseInt(e.substring(0, dot).trim()) : a;
                        endB = Integer.parseInt(e.substring(dot + 1).trim());
                    } catch (final NumberFormatException ignored) {
                        endA = 0; endB = 0;
                    }
                    if (endB <= 0 || endA < a || (endA == a && endB < b)) {
                        endA = 0; endB = 0;            // lenient: drop a bad/backwards end
                    }
                }
                return new Ref("Q".equalsIgnoreCase(p[0]), p[0], a, b, endA, endB);
            } catch (final NumberFormatException e) {
                return null;
            }
        }
    }

    /** One column's parsed/serializable state. */
    public static final class ColSpec {
        public String      src;                 // lowercased abbreviation token
        public Ref         ref;                  // nullable
        public DisplayMode mode;                 // nullable -> column default
        public OrderMode   order = OrderMode.CANONICAL;
        /** Extra passages to flash on landing, beyond {@link #ref}. Empty by
         *  default. Deliberately SEPARATE from ref rather than widening ref
         *  into a cross-book range: in a reordered edition the passage worth
         *  highlighting is the one that MOVED, which is usually not the one the
         *  link opens at. A range would be forced to paint both. */
        public final List<Ref> highlights = new ArrayList<>();
        public int         companion;           // 0 = off; N = companion shown / N lineage generations
        public boolean     comments;             // in-text comment markers (per column)
    }

    /** Result of parsing a query string. */
    public static final class Parsed {
        public boolean        sync = true;
        public final List<ColSpec> cols = new ArrayList<>();
    }

    // ── Token maps ────────────────────────────────────────────────────

    public static String orderToken(final OrderMode anOrderMode) {
        return switch (anOrderMode) {
            case CHRONOLOGICAL -> "chrono";
            case TANAKH        -> "tanakh";
            default            -> "canon";
        };
    }

    public static OrderMode orderFromToken(final String aToken) {
        if (aToken == null) return OrderMode.CANONICAL;
        return switch (aToken.trim().toLowerCase()) {
            case "chrono", "crono", "chronological" -> OrderMode.CHRONOLOGICAL;
            case "tanakh"                            -> OrderMode.TANAKH;
            default -> OrderMode.CANONICAL;
        };
    }

    public static String modeToken(final DisplayMode aMode) {
        return switch (aMode) {
            case ORIGINAL        -> "original";
            case ORIGINAL_SIMPLE -> "continuous";
            case CHAPTERS        -> "chapters";
            case CHAPTERS_VERSES -> "verses";
            case TITLES          -> "titles";
        };
    }

    public static DisplayMode modeFromToken(final String aToken) {
        if (aToken == null) return null;
        return switch (aToken.trim().toLowerCase()) {
            case "original", "scriptio"   -> DisplayMode.ORIGINAL;
            case "continuous", "simple"   -> DisplayMode.ORIGINAL_SIMPLE;
            case "chapters"               -> DisplayMode.CHAPTERS;
            case "verses", "numbered"     -> DisplayMode.CHAPTERS_VERSES;
            case "titles"                 -> DisplayMode.TITLES;
            default                        -> null;
        };
    }

    // ── Build ─────────────────────────────────────────────────────────

    /** Serialize column specs to a query string (no leading '?'). Columns beyond
     *  {@link #MAX_COLUMNS} are dropped. Defaults (canon order, no companion) are
     *  omitted to keep links short. */
    public static String build(final List<ColSpec> theColumns, final boolean aSyncFlag) {
        final int n = Math.min(theColumns.size(), MAX_COLUMNS);
        final StringBuilder sb = new StringBuilder();
        sb.append("cols=").append(n).append("&sync=").append(aSyncFlag ? 1 : 0);
        for (int i = 0; i < n; i++) {
            final ColSpec c = theColumns.get(i);
            final String p = "&c" + (i + 1) + ".";
            if (c.src != null && !c.src.isBlank()) sb.append(p).append("src=").append(c.src);
            if (c.ref != null)                     sb.append(p).append("ref=").append(c.ref.format());
            if (c.order != null && c.order != OrderMode.CANONICAL)
                                                   sb.append(p).append("order=").append(orderToken(c.order));
            if (c.mode != null)                    sb.append(p).append("mode=").append(modeToken(c.mode));
            if (c.companion > 0)                   sb.append(p).append("companion=").append(c.companion);
            if (c.comments)                        sb.append(p).append("comments=1");
            if (c.highlights != null && !c.highlights.isEmpty()) {
                final StringBuilder hl = new StringBuilder();
                for (final Ref r : c.highlights) {
                    if (r == null) continue;
                    if (hl.length() > 0) hl.append(',');
                    hl.append(r.format());
                }
                if (hl.length() > 0) sb.append(p).append("hl=").append(hl);
            }
        }
        return sb.toString();
    }

    // ── Parse ─────────────────────────────────────────────────────────

    /** Parse a flat query-param map (first value per key) into a {@link Parsed}.
     *  Lenient throughout; never throws. */
    public static Parsed parse(final Map<String, String> aQueryMap) {
        final Parsed out = new Parsed();
        if (aQueryMap == null || aQueryMap.isEmpty()) return out;

        final String syncTok = aQueryMap.get("sync");
        out.sync = syncTok == null || !"0".equals(syncTok.trim());

        int n = parseIntOr(aQueryMap.get("cols"), 0);
        if (n <= 0) {                                  // infer from the highest cK.src/ref present
            for (int i = MAX_COLUMNS; i >= 1; i--) {
                if (aQueryMap.containsKey("c" + i + ".src") || aQueryMap.containsKey("c" + i + ".ref")) { n = i; break; }
            }
        }
        n = Math.min(n, MAX_COLUMNS);

        for (int i = 1; i <= n; i++) {
            final String p = "c" + i + ".";
            final String src  = aQueryMap.get(p + "src");
            final String refS = aQueryMap.get(p + "ref");
            // A fully-empty slot (sparse numbering) is skipped; a column with a
            // ref but NO src is kept with src = null — the reader substitutes
            // its default edition (the user's preferred Bible when set), so
            // hand-authored links like ?c1.ref=JHN.3 work without naming one.
            if ((src == null || src.isBlank()) && (refS == null || refS.isBlank())) continue;
            final ColSpec c = new ColSpec();
            c.src       = (src == null || src.isBlank()) ? null : src.trim().toLowerCase();
            c.ref       = Ref.parse(refS);
            c.order     = orderFromToken(aQueryMap.get(p + "order"));
            c.mode      = modeFromToken(aQueryMap.get(p + "mode"));
            final String comp = aQueryMap.get(p + "companion");
            c.companion = "true".equalsIgnoreCase(comp)
                ? 1 : Math.max(0, Math.min(9, parseIntOr(comp, 0)));
            final String cmts = aQueryMap.get(p + "comments");
            c.comments = "1".equals(cmts) || "true".equalsIgnoreCase(cmts);
            c.highlights.addAll(parseHighlights(aQueryMap.get(p + "hl")));
            out.cols.add(c);
        }
        return out;
    }

    /** Parse {@code hl=BOOK.CH[.VS][-…],BOOK.CH…} into refs. Lenient like the
     *  rest of this codec: an unparseable entry is dropped, not fatal, so a
     *  hand-edited link degrades to fewer highlights rather than to an error
     *  page. Capped so a crafted link cannot ask the client to scan for
     *  hundreds of spans on every tick of the flash interval. */
    static List<Ref> parseHighlights(final String aValue) {
        final List<Ref> out = new ArrayList<>();
        if (aValue == null || aValue.isBlank()) return out;
        for (final String part : aValue.split(",")) {
            if (out.size() >= MAX_HIGHLIGHTS) break;
            final Ref r = Ref.parse(part);
            if (r != null) out.add(r);
        }
        return out;
    }

    private static int parseIntOr(final String aString, final int aFallback) {
        if (aString == null) return aFallback;
        try { return Integer.parseInt(aString.trim()); }
        catch (final NumberFormatException e) { return aFallback; }
    }
}
