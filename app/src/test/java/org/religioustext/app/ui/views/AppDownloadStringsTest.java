// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code /app} page in every language the site speaks, and the release
 * description it reads.
 *
 * @author Christa Claw
 * @version 0.12.9-SNAPSHOT
 * @since 0.12.9
 */
class AppDownloadStringsTest {

    private static final List<String> LANGS = List.of(
        "", "ar", "de", "es", "fi", "fr", "he", "hi", "it", "ja", "ru", "sv", "tr", "zh");

    private static Properties bundle(final String aLang) throws Exception {
        final String name = "/i18n/translations" + (aLang.isEmpty() ? "" : "_" + aLang) + ".properties";
        try (InputStream in = AppDownloadStringsTest.class.getResourceAsStream(name)) {
            final Properties p = new Properties();
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return p;
        }
    }

    private static Set<String> appKeys(final Properties aBundle) {
        final Set<String> keys = new TreeSet<>();
        for (final String k : aBundle.stringPropertyNames()) if (k.startsWith("app.")) keys.add(k);
        return keys;
    }

    @Test
    void everyLanguageHasEveryStringOfThePage() throws Exception {
        final Set<String> english = appKeys(bundle(""));
        assertThat(english).contains("app.title", "app.download", "app.install.5", "app.privacy.body");
        for (final String lang : LANGS) {
            assertThat(appKeys(bundle(lang))).as("app.* keys in %s", lang).isEqualTo(english);
        }
    }

    @Test
    void theFormattedStringsKeepTheirApostrophesAndArguments() throws Exception {
        for (final String lang : LANGS) {
            final Properties p = bundle(lang);
            final String meta = new MessageFormat(p.getProperty("app.download.meta"), Locale.ROOT)
                .format(new Object[] {"1.0.0", "38", "7.0"});
            assertThat(meta).as(lang).contains("1.0.0", "38", "7.0");
            final String elsewhere = new MessageFormat(p.getProperty("app.notAndroid"), Locale.ROOT)
                .format(new Object[] {AppDownloadView.SHORT_URL});
            assertThat(elsewhere).as(lang).contains(AppDownloadView.SHORT_URL).doesNotContain("''");
        }
    }

    @Test
    void theReleaseOnTheClasspathIsWholeOrAbsent() {
        AndroidRelease.current().ifPresent(r -> {
            assertThat(r.version()).isNotBlank();
            assertThat(r.file()).endsWith(".apk").contains(r.version());
            assertThat(r.bytes()).isPositive();
            assertThat(r.sha256()).matches("[0-9a-f]{64}");
            assertThat(r.href()).isEqualTo("/downloads/android/" + r.file());
        });
    }
}
