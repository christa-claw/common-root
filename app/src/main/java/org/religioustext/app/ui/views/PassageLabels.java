// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * English passage labels ("Romans 5:8", "Proverbs 8:22-30") built from a USFM book
 * code, for the analytics events.
 *
 * <p>The label is the same whatever language or edition the reader is in, so one
 * passage is one value in Umami and its counts add up. The names come from the
 * English base bundle {@code i18n/booknames.properties}, with the one book the
 * corpus spells differently from that bundle ("Song of Solomon", not "Song of
 * Songs") set to the corpus's spelling, so a label built here matches the
 * {@code passage} the reader-view event builds from the corpus's own book name.
 * A code the bundle does not know — the Apocrypha, which it does not list — is
 * used as it is, so a label is never empty.
 */
final class PassageLabels {

    private static final Map<String, String> NAMES = load();

    private PassageLabels() { }

    /** The English name for a USFM code, or the code itself when unknown. */
    static String bookName(final String aBookCode) {
        if (aBookCode == null) return "";
        return NAMES.getOrDefault(aBookCode.toUpperCase(java.util.Locale.ROOT), aBookCode);
    }

    /** {@code Romans 5:8}. */
    static String of(final String aBookCode, final int aChapter, final int aVerse) {
        return bookName(aBookCode) + " " + aChapter + ":" + aVerse;
    }

    /**
     * A target that may be a range, shaped like {@code XRef.linkRef()}: a range inside
     * one chapter is {@code Proverbs 8:22-30}; one that crosses chapters is
     * {@code Psalms 89:11-90:2}. A missing end, or an end equal to the start, is the
     * single verse.
     */
    static String range(final String aBookCode, final int aChapter, final int aVerse,
                        final Integer anEndChapter, final Integer anEndVerse) {
        final String start = of(aBookCode, aChapter, aVerse);
        if (anEndChapter == null || anEndVerse == null) return start;
        if (anEndChapter == aChapter) return anEndVerse == aVerse ? start : start + "-" + anEndVerse;
        return start + "-" + anEndChapter + ":" + anEndVerse;
    }

    /**
     * A passage label that came back from the browser ("Genesis 1:1"), or null when it is
     * not one: letters, digits, spaces and {@code . : -} only, at most 60 characters. The
     * value is analytics text sent by the page, so it is checked rather than trusted.
     */
    static String clean(final String aLabel) {
        if (aLabel == null) return null;
        final String t = aLabel.strip();
        return t.matches("[\\p{L}\\p{N} .:\\-]{1,60}") ? t : null;
    }

    private static Map<String, String> load() {
        final Properties p = new Properties();
        try (InputStream in = PassageLabels.class.getResourceAsStream("/i18n/booknames.properties")) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException ignored) {
            // No names is a legitimate state: labels fall back to the bare codes.
        }
        final Map<String, String> out = new HashMap<>();
        for (final String code : p.stringPropertyNames()) out.put(code, p.getProperty(code));
        out.put("SNG", "Song of Solomon");   // the corpus's spelling; the UI bundle says "Song of Songs"
        return Map.copyOf(out);
    }
}
