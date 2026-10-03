// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.model.user;

import java.util.List;

/**
 * How the reader is dressed: the classic reader, or the typeset "book" look the
 * print designer previews (drop caps, a serif face, an accent colour).
 *
 * <p>Resolved here, in one place, from "who is reading": a signed-OUT reader
 * always gets the classic reader; a signed-IN reader gets the book look in
 * rubric red unless their preferences say otherwise. The stored preference
 * columns are nullable for exactly that reason — null means "the default", so
 * the default can change without migrating rows.
 */
public record ReaderLook(boolean book, String accent, boolean longTitles) {

    public static final String BOOK = "book";
    public static final String CLASSIC = "classic";
    public static final String DEFAULT_ACCENT = "rubric";
    /** The accents, in the order Preferences lists them (the print designer's five). */
    public static final List<String> ACCENTS =
        List.of("rubric", "black", "indigo", "sepia", "forest");

    /** What a signed-out reader sees: today's reader, untouched. */
    public static final ReaderLook CLASSIC_LOOK = new ReaderLook(false, DEFAULT_ACCENT, false);

    public static ReaderLook of(final boolean aSignedIn, final UserPreferences aPrefs) {
        if (!aSignedIn) return CLASSIC_LOOK;
        final String style = aPrefs == null ? null : aPrefs.getReaderStyle();
        final String accent = aPrefs == null ? null : aPrefs.getReaderAccent();
        final Boolean titles = aPrefs == null ? null : aPrefs.getReaderLongTitles();
        return new ReaderLook(
            !CLASSIC.equalsIgnoreCase(style),
            accent != null && ACCENTS.contains(accent.toLowerCase(java.util.Locale.ROOT))
                ? accent.toLowerCase(java.util.Locale.ROOT) : DEFAULT_ACCENT,
            titles == null || titles);
    }
}
