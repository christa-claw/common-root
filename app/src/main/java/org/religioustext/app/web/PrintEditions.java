// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The editions the print pipeline can produce today: those whose print rights
 * are VERIFIED in {@code scripts/print/build_package.py}. An edition joins this
 * list, the designer's picker in {@code edition-designer.html} and the registry
 * together, when its rights are confirmed.
 *
 * <p>This is the server's whitelist: the info pages use it to decide where to
 * offer printing, and the designer's preview endpoint will only read text from
 * an edition named here, so a request can never name an arbitrary document.
 */
public final class PrintEditions {

    /** One printable edition: the abbreviation the site uses, its BaseX document
     *  id, and the language of its text (which picks the book names). */
    public record Edition(String abbr, String id, String lang) { }

    private static final List<Edition> ALL = List.of(
        new Edition("WEB", "bible-web", "en"),
        new Edition("KJV", "bible-kjv-1611", "en"),
        new Edition("ONAV", "bible-ar-onav", "ar"),
        new Edition("SVD-E", "bible-ar-vd-ebible", "ar"));

    private PrintEditions() { }

    public static Optional<Edition> byAbbr(final String anAbbr) {
        return ALL.stream().filter(e -> e.abbr().equalsIgnoreCase(anAbbr)).findFirst();
    }

    public static Set<String> abbreviations() {
        return ALL.stream().map(Edition::abbr).collect(Collectors.toUnmodifiableSet());
    }
}
