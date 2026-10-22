// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The German, French and Italian book-name bundles must cover every USFM code the
 * English base does, and a full name typed in that language must parse to the same
 * book as its English name.
 */
class BookNamesBundleTest {

    private static Properties load(final String aSuffix) throws IOException {
        final Properties p = new Properties();
        try (InputStream in = BookNamesBundleTest.class
                .getResourceAsStream("/i18n/booknames" + aSuffix + ".properties")) {
            assertThat(in).as("booknames%s", aSuffix).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void everyBundleCoversEveryBookCode() throws IOException {
        final Properties en = load("");
        for (final String lang : new String[] {"de", "fr", "it"}) {
            final Properties p = load("_" + lang);
            assertThat(p.stringPropertyNames()).as("codes in booknames_%s", lang)
                .containsExactlyInAnyOrderElementsOf(en.stringPropertyNames());
            p.forEach((k, v) -> assertThat(v.toString()).as("%s_%s", lang, k).isNotBlank());
        }
    }

    @Test
    void aFullNameTypedInTheLanguageParsesToTheSameBookAsEnglish() throws IOException {
        final Properties en = load("");
        for (final String lang : new String[] {"de", "fr", "it"}) {
            final Properties p = load("_" + lang);
            final Locale loc = Locale.forLanguageTag(lang);
            for (final String code : en.stringPropertyNames()) {
                final var expected = RefListParser.parse(en.getProperty(code) + " 1:1", Locale.ENGLISH);
                assertThat(expected).as("English %s", code).hasSize(1);
                assertThat(RefListParser.parse(p.getProperty(code) + " 1:1", loc))
                    .as("%s %s (%s)", lang, code, p.getProperty(code))
                    .isEqualTo(expected);
            }
        }
    }
}
