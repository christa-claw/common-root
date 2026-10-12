// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.WordLayerService.Occurrence;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class StrongsDialogTest {

    @Test
    void labelsAnOccurrenceWithTheBookNameInTheInterfaceLanguage() {
        assertThat(StrongsDialog.label(new Occurrence("GEN", 1, 1, "x"), Locale.ENGLISH)).isEqualTo("Genesis 1:1");
        assertThat(StrongsDialog.label(new Occurrence("1SA", 17, 45, "x"), Locale.ENGLISH)).isEqualTo("1 Samuel 17:45");
        assertThat(StrongsDialog.label(new Occurrence("GEN", 1, 1, "x"), Locale.forLanguageTag("es"))).isEqualTo("Génesis 1:1");
    }

    @Test
    void rightToLeftLocalesAreArabicAndHebrew() {
        assertThat(StrongsDialog.isRtl(Locale.forLanguageTag("ar"))).isTrue();
        assertThat(StrongsDialog.isRtl(Locale.forLanguageTag("he"))).isTrue();
        assertThat(StrongsDialog.isRtl(Locale.forLanguageTag("fi"))).isFalse();
        assertThat(StrongsDialog.isRtl(null)).isFalse();
    }
}
