// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the order page asks the print builder for: the settings of the book, as the page holds them.
 *
 * <p>Nothing here is trusted. Every value is checked against a fixed list or a numeric range, the
 * edition must be one of {@link PrintEditions}, and the command the builder runs is made as an
 * ARGUMENT LIST, never a shell string, so no value can add an option or run anything. A request that
 * asks for something the generator cannot build yet (a text mode without verse numbers, the
 * apocrypha in a reading order that has no placement for them) is refused with the reason.
 *
 * @param edition  the edition's abbreviation, as the page lists it ("WEB")
 * @param ordering canonical, chronological, tanakh or writing
 * @param canon    "66" or "full"
 * @param trim     pocket, standard, large, or notes (only with margin cross references)
 * @param body     type size in points, or null for the trim's own
 * @param outer    fore-edge margin in mm, or null for the trim's own
 * @param head     running head: split, book-chapter, book or none
 * @param titles   long or short book titles
 * @param accent   black, rubric, indigo, sepia or forest
 * @param xrefs    none, inline or margin
 * @param xrefTop  references per verse, 1 to 5
 * @param mode     the page's text mode; only CHAPTERS_VERSES is built
 */
public record PrintPackageRequest(String edition, String ordering, String canon, String trim,
                                  Double body, Integer outer, String head, String titles,
                                  String accent, String xrefs, Integer xrefTop, String mode) {

    private static final Set<String> ORDERINGS = Set.of("canonical", "chronological", "tanakh", "writing");
    private static final Set<String> CANONS = Set.of("66", "full");
    private static final Set<String> TRIMS = Set.of("pocket", "standard", "large", "notes");
    private static final Set<String> HEADS = Set.of("split", "book-chapter", "book", "none");
    private static final Set<String> TITLES = Set.of("long", "short");
    private static final Set<String> ACCENTS = Set.of("black", "rubric", "indigo", "sepia", "forest");
    private static final Set<String> XREFS = Set.of("none", "inline", "margin");

    /** The typeface directory and family for each language the builder can set. */
    private static final Map<String, String[]> FONTS = Map.of(
        "en", new String[] {"fonts/crimson", "CrimsonPro"},
        "ar", new String[] {"fonts/scheherazade", "ScheherazadeNew"});

    /** A request that has passed every check, with its defaults filled in. */
    public record Valid(PrintEditions.Edition edition, String ordering, String canon, String trim,
                        Double body, Integer outer, String head, String titles, String accent,
                        String xrefs, int xrefTop, String fontDir, String fontFamily) {

        /** A name for the download: {@code common-root-web-chronological-package.zip}. */
        public String filename() {
            return "common-root-" + edition.abbr().toLowerCase(Locale.ROOT) + "-" + ordering
                + (xrefs.equals("none") ? "" : "-xrefs-" + xrefs) + "-package.zip";
        }

        /**
         * The builder's arguments after the interpreter: the script, then one option at a time.
         *
         * @param aScript the path of {@code build_package.py}
         * @param anOut   where the zip is to be written
         */
        public List<String> arguments(final String aScript, final Path anOut) {
            final List<String> a = new ArrayList<>(List.of(
                aScript, "--translation", edition.id(), "--ordering", ordering, "--canon", canon,
                "--trim", trim, "--fonts", fontDir, "--font-family", fontFamily,
                "--running-head", head, "--book-titles", titles, "--accent", accent));
            if (body != null) a.addAll(List.of("--body-size", String.valueOf(body)));
            if (outer != null) a.addAll(List.of("--outer", String.valueOf(outer)));
            if (!xrefs.equals("none")) a.addAll(List.of("--xrefs", xrefs, "--xref-top", String.valueOf(xrefTop)));
            a.addAll(List.of("--out", anOut.toString()));
            return a;
        }
    }

    /**
     * Check the request.
     *
     * @return the checked request
     * @throws IllegalArgumentException with a sentence the page can show, for anything refused
     */
    public Valid validate() {
        final PrintEditions.Edition ed = PrintEditions.byAbbr(edition)
            .orElseThrow(() -> new IllegalArgumentException(
                "That edition is not offered for printing: " + edition));
        final String order = oneOf("reading order", ordering, ORDERINGS, "chronological");
        final String canonValue = oneOf("canon", canon, CANONS, "66");
        String trimValue = oneOf("format", trim, TRIMS, "standard");
        final String xr = oneOf("cross references", xrefs, XREFS, "none");
        final String headValue = oneOf("running head", head, HEADS, "split");
        final String titleValue = oneOf("book titles", titles, TITLES, "long");
        final String accentValue = oneOf("colour", accent, ACCENTS, "black");

        if (mode != null && !mode.isBlank() && !mode.equals("CHAPTERS_VERSES")) {
            throw new IllegalArgumentException(
                "Only the verse-numbered text is built so far; the other text modes are not.");
        }
        if (canonValue.equals("full") && (order.equals("chronological") || order.equals("writing"))) {
            throw new IllegalArgumentException(
                "The apocrypha have no placement in that reading order yet; use the traditional order.");
        }
        Integer outerValue = outer;
        if (outerValue != null && (outerValue < 8 || outerValue > 40)) {
            throw new IllegalArgumentException("The fore-edge margin must be 8 to 40 mm.");
        }
        Double bodyValue = body;
        if (bodyValue != null) {
            if (bodyValue < 7.2 || bodyValue > 10.5) {
                throw new IllegalArgumentException("The type size must be 7.2 to 10.5 pt.");
            }
            bodyValue = Math.round(bodyValue * 10.0) / 10.0;
        }
        int top = xrefTop == null ? 3 : xrefTop;
        if (top < 1 || top > 5) {
            throw new IllegalArgumentException("Cross references per verse must be 1 to 5.");
        }
        if (!xr.equals("none") && !ed.lang().equals("en")) {
            throw new IllegalArgumentException(
                "Cross references are built for English editions only so far.");
        }
        if (xr.equals("margin")) {
            // The margin needs the one-column interior with a wide fore-edge, and that trim sets its own.
            trimValue = "notes";
            outerValue = null;
        } else if (trimValue.equals("notes")) {
            throw new IllegalArgumentException(
                "The wide one-column format is for cross references in the margin.");
        }
        final String[] font = FONTS.get(ed.lang());
        if (font == null) {
            throw new IllegalArgumentException("There is no print typeface for " + ed.lang() + " yet.");
        }
        return new Valid(ed, order, canonValue, trimValue, bodyValue, outerValue, headValue,
            titleValue, accentValue, xr, top, font[0], font[1]);
    }

    private static String oneOf(final String aName, final String aValue, final Set<String> anAllowed,
                                final String aDefault) {
        if (aValue == null || aValue.isBlank()) return aDefault;
        if (!anAllowed.contains(aValue)) {
            throw new IllegalArgumentException("Not a valid " + aName + ": " + aValue);
        }
        return aValue;
    }
}
