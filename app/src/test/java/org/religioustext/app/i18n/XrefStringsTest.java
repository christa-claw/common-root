// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.i18n;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

/** Every provided locale carries its own cross-reference strings (not the English fallback). */
class XrefStringsTest {

    private static final List<String> KEYS = List.of("reader.xrefs.title", "reader.xrefs.toggleMarkers",
        "reader.xrefs.showAll", "reader.xrefs.openAll", "reader.xrefs.credit", "reader.xrefs.changes", "prefs.showXrefMarkers");

    @Test
    void everyLocaleDefinesEveryKeyItself() {
        for (final Locale loc : LocaleUtil.LOCALES) {
            final ResourceBundle b = ResourceBundle.getBundle("i18n.translations", loc,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
            for (final String k : KEYS) {
                assertThat(b.keySet()).as(loc + " " + k).contains(k);
                assertThat(b.getString(k)).as(loc + " " + k).isNotBlank();
            }
        }
    }
}
