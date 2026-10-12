// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.i18n;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/** Every provided locale carries its own text for the ⓝ toggle's tooltip (not the English fallback). */
class StrongsStringsTest {

    @Test
    void everyLocaleDefinesTheToggleTooltipItself() {
        for (final Locale loc : LocaleUtil.LOCALES) {
            final ResourceBundle b = ResourceBundle.getBundle("i18n.translations", loc,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            for (final String key : new String[] {"reader.strongs.toggle", "reader.strongs.machine",
                    "reader.strongs.occurrences", "reader.strongs.more"}) {
                assertThat(b.keySet()).as(loc + " " + key).contains(key);
                assertThat(b.getString(key)).as(loc + " " + key).isNotBlank();
            }
        }
    }

    @Test
    void theTooltipDoesNotMentionOpeningTheRungs() {
        // deliberate (the maintainer, 2026-10-12): readers will find that out themselves
        assertThat(ResourceBundle.getBundle("i18n.translations", Locale.ENGLISH)
            .getString("reader.strongs.toggle")).isEqualTo("Show Strong's numbers");
    }
}
