// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.religioustext.app.ui.views.reader.SourceRow;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one row of the corpus catalogue into an "Available Texts" card, for every
 * Bible the hand-tuned list in {@link AboutView} does not already cover.
 *
 * <p>The About page shows everything the corpus holds. The hand-tuned cards keep their
 * chosen names, language labels and licence wording; any other Bible is derived from its
 * catalogue row, so a newly ingested edition appears on its own, in its language group,
 * and the nightly job fills in its info page later.
 *
 * <p>A card is a {@code String[]}: {abbreviation, name, language key, year, licence,
 * status}. The language key is either a key of {@code about.lang.*} ("arabic") or
 * {@code "iso:ja"} for a language the bundles have no word for yet, which is then named by
 * the JDK in the reader's own language.
 */
final class AboutCards {

    /** Corpus language code to the key of {@code about.lang.*}. */
    private static final Map<String, String> LANG_KEYS = Map.ofEntries(
        Map.entry("ar", "arabic"), Map.entry("zh", "chinese"), Map.entry("en", "english"),
        Map.entry("fi", "finnish"), Map.entry("fr", "french"), Map.entry("de", "german"),
        Map.entry("grc", "greek"), Map.entry("el", "greek"), Map.entry("he", "hebrew"),
        Map.entry("hi", "hindi"), Map.entry("it", "italian"), Map.entry("la", "latin"),
        Map.entry("ru", "russian"), Map.entry("es", "spanish"), Map.entry("sv", "swedish"),
        Map.entry("tr", "turkish"));

    /** A Latin name in brackets, as in "フリーダム・バイブル (Freedom Bible)". */
    private static final Pattern LATIN_IN_BRACKETS = Pattern.compile("\\(([^()]*[A-Za-z][^()]*)\\)");

    private AboutCards() { }

    /** The card for a catalogue row, or null when the row is not a Bible or has no abbreviation. */
    static String[] derive(final String[] aRow) {
        final SourceRow r = SourceRow.of(aRow);
        if (r == null || !r.isBible()) return null;
        final String abbr = r.abbreviation();
        if (abbr == null || abbr.isBlank()) return null;
        final String year = r.year();
        return new String[] {
            abbr, displayName(r.name(), abbr), languageKey(r.language()),
            year == null || year.isBlank() ? "—" : year,
            r.license() == null || r.license().isBlank() ? "—" : r.license(),
            "✅"};
    }

    /** The name a card shows. A catalogue name in another script carries its own Latin
     *  gloss in brackets where it has one; otherwise the name is used as it stands. */
    static String displayName(final String aName, final String anAbbr) {
        if (aName == null || aName.isBlank()) return anAbbr;
        final boolean hasLatin = aName.chars().anyMatch(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'));
        if (hasLatin && !aName.codePoints().anyMatch(c -> c > 0x024F)) return aName.strip();
        final Matcher m = LATIN_IN_BRACKETS.matcher(aName);
        return m.find() ? m.group(1).strip() : aName.strip();
    }

    /** {@code about.lang.*} key for a corpus language code, else {@code iso:<code>}. */
    static String languageKey(final String aCode) {
        final String code = aCode == null ? "" : aCode.strip().toLowerCase(Locale.ROOT);
        final String base = code.contains("-") ? code.substring(0, code.indexOf('-')) : code;
        final String key = LANG_KEYS.get(base);
        return key != null ? key : (base.isEmpty() ? "iso:und" : "iso:" + base);
    }

    /** The language's name in {@code aLocale}, for a key from {@link #languageKey}. */
    static String languageName(final String aKey, final Locale aLocale,
                               final java.util.function.BiFunction<String, Locale, String> aBundle) {
        if (aKey.startsWith("iso:")) {
            final String name = Locale.forLanguageTag(aKey.substring(4)).getDisplayLanguage(aLocale);
            return name.isBlank() ? aKey.substring(4) : name;
        }
        return aBundle.apply("about.lang." + aKey, aLocale);
    }
}
