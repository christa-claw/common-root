// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Every credited contributor must be described in every UI locale. */
class ContributorsTest {

    private static final String[] LOCALES =
        {"", "_ar", "_de", "_es", "_fi", "_fr", "_he", "_hi", "_it", "_ja", "_ru", "_sv", "_tr", "_zh"};

    private static Properties load(final String aSuffix) throws IOException {
        final Properties p = new Properties();
        try (InputStream in = ContributorsTest.class
                .getResourceAsStream("/i18n/translations" + aSuffix + ".properties")) {
            assertThat(in).as("bundle translations%s", aSuffix).isNotNull();
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void sectionAndEveryContributorAreTranslatedInEveryLocale() throws IOException {
        for (final String suffix : LOCALES) {
            final Properties p = load(suffix);
            Stream.concat(Stream.of("about.contributors.title", "about.contributors.intro"),
                    Contributors.ALL.stream().map(Contributors.Contributor::descriptionKey))
                .forEach(k -> assertThat(p.getProperty(k))
                    .as("%s in translations%s", k, suffix).isNotBlank());
        }
    }

    @Test
    void profileUrlIsBuiltFromTheHandle() {
        assertThat(new Contributors.Contributor("someone").url())
            .isEqualTo("https://github.com/someone");
    }
}
