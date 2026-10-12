// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.regex.Pattern;

/**
 * The text behind a Strong's number — pointed lemma, transliteration, gloss, definition — from the
 * lexicon properties files (docs/strongs-design.md: {@code i18n/lexicon/hebrew.properties}, English,
 * and {@code hebrew_<lang>.properties} per locale). The lemma and transliteration are
 * language-neutral and live only in the English file; the gloss and definition are translated per
 * locale, and a key a locale file does not have falls back to English by the bundle's own parent
 * chain — no code, no per-language logic.
 *
 * <p>Lookups deliberately skip the JVM's default-locale fallback (a server running in a Finnish
 * locale must not turn English requests Finnish).
 */
@Service
public class LexiconService {

    /** One entry. {@code machineTranslated} is true when the gloss or definition shown differs from
     *  the English — i.e. it came from a locale file, which is machine-translated and unreviewed. */
    public record Entry(String strongs, String lemma, String xlit, String gloss, String definition,
                        boolean machineTranslated) {}

    private static final Pattern ID = Pattern.compile("H\\d{1,5}");
    private static final ResourceBundle.Control NO_FALLBACK =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private final String basename;

    @Autowired
    public LexiconService() { this("i18n.lexicon.hebrew"); }

    /** A different bundle base name — the tests' small bundles. */
    public LexiconService(final String aBasename) { this.basename = aBasename; }

    /** The Hebrew entry for a Strong's id such as {@code H430}, in the given locale (English when the
     *  locale is null or has no file), or empty when the id is malformed or unknown. */
    public Optional<Entry> hebrew(final String aStrongs, final Locale aLocale) {
        if (aStrongs == null || !ID.matcher(aStrongs).matches()) return Optional.empty();
        final ResourceBundle english = bundle(Locale.ROOT);
        if (english == null || !english.containsKey(aStrongs + ".gloss")) return Optional.empty();
        final ResourceBundle b = aLocale == null ? english : bundle(aLocale);
        final ResourceBundle chosen = b == null ? english : b;
        final String gloss = chosen.getString(aStrongs + ".gloss");
        final String def = chosen.containsKey(aStrongs + ".def") ? chosen.getString(aStrongs + ".def") : "";
        final boolean translated = chosen != english
            && (!gloss.equals(english.getString(aStrongs + ".gloss"))
                || !def.equals(english.containsKey(aStrongs + ".def") ? english.getString(aStrongs + ".def") : ""));
        return Optional.of(new Entry(aStrongs,
            english.getString(aStrongs + ".lemma"), english.getString(aStrongs + ".xlit"),
            gloss, def, translated));
    }

    private ResourceBundle bundle(final Locale aLocale) {
        try {
            return ResourceBundle.getBundle(basename, aLocale, NO_FALLBACK);
        } catch (final MissingResourceException e) {
            return null;
        }
    }
}
