// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views.reader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * The traditional long titles of the books ("The First Book of Moses, called
 * Genesis"), for English editions.
 *
 * <p>They live in {@code i18n/booklongnames.properties}, keyed by USFM code, and
 * are read by both the reader (here) and the print generator
 * ({@code scripts/print/build_pdf.py}), so the two cannot disagree. A book is
 * looked up by its English name, which is what the corpus calls it; the English
 * names come from {@code i18n/booknames.properties}.
 *
 * <p>Some of these assert authorship the books do not claim for themselves (see
 * DISPUTED_ATTRIBUTION in build_pdf.py), which is why showing them is a choice
 * the reader makes in Preferences, not something imposed.
 */
public final class BookTitles {

    private static final Map<String, String> BY_NAME = load();

    private BookTitles() { }

    /** The long title for an English book name, or null when there is none. */
    public static String longTitle(final String anEnglishName) {
        return anEnglishName == null ? null : BY_NAME.get(anEnglishName);
    }

    private static Map<String, String> load() {
        final Properties titles = read("/i18n/booklongnames.properties");
        final Properties names = read("/i18n/booknames.properties");
        final Map<String, String> out = new HashMap<>();
        for (final String code : titles.stringPropertyNames()) {
            final String name = names.getProperty(code);
            if (name != null) out.put(name, titles.getProperty(code));
        }
        // The corpus spells two books differently from the UI bundle.
        final String sng = titles.getProperty("SNG");
        if (sng != null) out.putIfAbsent("Song of Solomon", sng);
        final String psa = titles.getProperty("PSA");
        if (psa != null) out.putIfAbsent("Psalm", psa);
        return Map.copyOf(out);
    }

    private static Properties read(final String aPath) {
        final Properties p = new Properties();
        try (InputStream in = BookTitles.class.getResourceAsStream(aPath)) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException ignored) {
            // No titles is a legitimate state: the reader simply shows none.
        }
        return p;
    }
}
