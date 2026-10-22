// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * French abbreviations: complete, unambiguous within French, and each typed form
 * resolves to the same book as that book's English name.
 */
class BookAbbrevFrTest {

    private static Properties load(final String aName) throws IOException {
        final Properties p = new Properties();
        try (InputStream in = BookAbbrevFrTest.class.getResourceAsStream("/i18n/" + aName + ".properties")) {
            assertThat(in).as(aName).isNotNull();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static String norm(final String aForm) {
        return aForm.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }

    @Test
    void coversEveryBookCode() throws IOException {
        assertThat(load("bookabbrev_fr").stringPropertyNames())
            .containsExactlyInAnyOrderElementsOf(load("booknames").stringPropertyNames());
    }

    @Test
    void noFormMeansTwoDifferentBooks() throws IOException {
        final Map<String, String> seen = new HashMap<>();
        for (final String file : new String[] {"booknames_fr", "bookabbrev_fr"}) {
            final Properties p = load(file);
            for (final String code : p.stringPropertyNames()) {
                for (final String form : p.getProperty(code).split(",")) {
                    final String prev = seen.putIfAbsent(norm(form), code);
                    assertThat(prev == null || prev.equals(code))
                        .as("'%s' in %s means both %s and %s", form, file, prev, code).isTrue();
                }
            }
        }
    }

    @Test
    void everyAbbreviationParsesToTheEnglishBook() throws IOException {
        final Properties en = load("booknames");
        final Properties fr = load("bookabbrev_fr");
        for (final String code : fr.stringPropertyNames()) {
            final var expected = RefListParser.parse(en.getProperty(code) + " 1:1", Locale.ENGLISH);
            assertThat(expected).as("English %s", code).hasSize(1);
            for (final String form : fr.getProperty(code).split(",")) {
                assertThat(RefListParser.parse(form + " 1:1", Locale.FRENCH))
                    .as("%s (%s)", form, code).isEqualTo(expected);
            }
        }
    }
}
