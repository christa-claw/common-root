// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AboutCardsTest {

    /** A catalogue row: id, name, abbreviation, direction, licence, source, type, language, ..., year (11). */
    private static String[] bible(final String name, final String abbr, final String lang,
                                  final String licence, final String year) {
        return new String[] {"bible-x", name, abbr, "ltr", licence, "", "bible", lang, "", "", "", year};
    }

    @Test
    void aBibleRowBecomesACard() {
        assertArrayEquals(new String[] {"YLT", "Young's Literal Translation 1898", "english", "1898",
                "Public Domain", "✅"},
            AboutCards.derive(bible("Young's Literal Translation 1898", "YLT", "en", "Public Domain", "1898")));
    }

    @Test
    void anUnknownYearReadsAsADash() {
        assertEquals("—", AboutCards.derive(bible("Majority Text", "GRCMT", "grc", "Public Domain", ""))[3]);
    }

    @Test
    void aNonBibleOrAnonymousRowIsSkipped() {
        final String[] quran = {"quran-x", "Quran", "QAR", "rtl", "PD", "", "quran", "ar", "", "", "", ""};
        assertNull(AboutCards.derive(quran));
        assertNull(AboutCards.derive(bible("No Abbreviation", "", "en", "PD", "")));
        assertNull(AboutCards.derive(null));
    }

    @Test
    void aNativeScriptNameKeepsItsLatinGloss() {
        assertEquals("Freedom Bible", AboutCards.displayName("フリーダム・バイブル (Freedom Bible)", "JFB"));
        assertEquals("Synodal", AboutCards.displayName("Synodal", "SYN"));
        assertEquals("和合本", AboutCards.displayName("和合本", "CUV"));
        assertEquals("ZZZ", AboutCards.displayName("", "ZZZ"));
    }

    @Test
    void languageKeysCoverTheBundlesAndFallBackToTheCode() {
        assertEquals("greek", AboutCards.languageKey("grc"));
        assertEquals("chinese", AboutCards.languageKey("zh-Hant"));
        assertEquals("iso:ja", AboutCards.languageKey("ja"));
        assertEquals("iso:und", AboutCards.languageKey(""));
    }

    @Test
    void anUnbundledLanguageIsNamedByTheJdk() {
        assertEquals("Japanese", AboutCards.languageName("iso:ja", Locale.ENGLISH, (k, l) -> k));
        assertEquals("Arabic", AboutCards.languageName("arabic", Locale.ENGLISH, (k, l) -> "Arabic"));
    }
}
