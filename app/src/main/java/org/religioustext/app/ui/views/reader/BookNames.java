// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views.reader;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * A book's display name in the interface language, by USFM code, from the
 * {@code i18n/booknames[_lang].properties} bundles (English is the base, so a book a language lacks
 * shows in English). Display only: the corpus's own book names stay English.
 */
public final class BookNames {

    private static final ResourceBundle.Control NO_FALLBACK =
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private BookNames() { }

    /** The book's name in {@code aLocale}; the code itself when no bundle knows it. */
    public static String of(final String aUsfmCode, final Locale aLocale) {
        if (aUsfmCode == null) return "";
        try {
            final ResourceBundle b = ResourceBundle.getBundle("i18n.booknames", aLocale == null ? Locale.ROOT : aLocale, NO_FALLBACK);
            if (b.containsKey(aUsfmCode)) return b.getString(aUsfmCode);
        } catch (final MissingResourceException e) {
            // fall through to the code
        }
        return aUsfmCode;
    }
}
