// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views.reader;

import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class BookNamesTest {

    @Test
    void englishIsTheBase() {
        assertThat(BookNames.of("GEN", Locale.ENGLISH)).isEqualTo("Genesis");
        assertThat(BookNames.of("PSA", null)).isEqualTo("Psalms");
    }

    @Test
    void otherLanguagesUseTheirOwnNames() {
        assertThat(BookNames.of("GEN", Locale.forLanguageTag("es"))).isEqualTo("Génesis");
        assertThat(BookNames.of("1SA", Locale.forLanguageTag("ar"))).isEqualTo("صموئيل الأول");
    }

    @Test
    void aLocaleWithoutAFileGetsEnglishAndAnUnknownCodeStaysAsItIs() {
        assertThat(BookNames.of("GEN", Locale.forLanguageTag("xx"))).isEqualTo("Genesis");
        assertThat(BookNames.of("ZZZ", Locale.ENGLISH)).isEqualTo("ZZZ");
        assertThat(BookNames.of(null, Locale.ENGLISH)).isEmpty();
    }

    @Test
    void theJvmDefaultLocaleDoesNotLeakIn() {
        final Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("fi"));
            assertThat(BookNames.of("GEN", Locale.ENGLISH)).isEqualTo("Genesis");
        } finally {
            Locale.setDefault(saved);
        }
    }
}
