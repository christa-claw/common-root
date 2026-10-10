// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrintPackageRequestTest {

    private static PrintPackageRequest web() {
        return new PrintPackageRequest("WEB", "chronological", "66", "standard", 8.8, 12, "split",
            "long", "black", "none", 3, "CHAPTERS_VERSES");
    }

    private static PrintPackageRequest with(final String edition, final String ordering, final String canon,
                                            final String trim, final Double body, final Integer outer,
                                            final String xrefs, final String mode) {
        return new PrintPackageRequest(edition, ordering, canon, trim, body, outer, "split", "long",
            "black", xrefs, 3, mode);
    }

    private static String message(final PrintPackageRequest aRequest) {
        return assertThrows(IllegalArgumentException.class, aRequest::validate).getMessage();
    }

    @Test
    void aPlainRequestBecomesTheBuildersArguments() {
        final List<String> a = web().validate().arguments("scripts/print/build_package.py", Path.of("/o/p.zip"));
        assertEquals("scripts/print/build_package.py", a.get(0));
        assertTrue(a.containsAll(List.of("--translation", "bible-web", "--ordering", "chronological",
            "--canon", "66", "--trim", "standard", "--fonts", "fonts/crimson", "--font-family", "CrimsonPro",
            "--running-head", "split", "--book-titles", "long", "--accent", "black",
            "--body-size", "8.8", "--outer", "12", "--out", "/o/p.zip")));
        assertFalse(a.contains("--xrefs"));
    }

    @Test
    void emptyChoicesTakeTheDefaults() {
        final PrintPackageRequest.Valid v = new PrintPackageRequest("KJV", null, null, null, null, null,
            null, null, null, null, null, null).validate();
        assertEquals("chronological", v.ordering());
        assertEquals("standard", v.trim());
        assertEquals("none", v.xrefs());
        assertNull(v.body());
        assertNull(v.outer());
        assertEquals(3, v.xrefTop());
    }

    @Test
    void anEditionNotOfferedForPrintingIsRefused() {
        assertTrue(message(with("NIV", "chronological", "66", "standard", null, null, "none", null))
            .contains("not offered"));
        assertTrue(message(with("bible-web; rm -rf /", "chronological", "66", "standard", null, null, "none", null))
            .contains("not offered"));
    }

    @Test
    void aValueOutsideItsListIsRefusedAndNeverReachesTheCommand() {
        assertTrue(message(with("WEB", "chronological; rm -rf /", "66", "standard", null, null, "none", null))
            .contains("reading order"));
        assertTrue(message(with("WEB", "chronological", "66", "huge", null, null, "none", null)).contains("format"));
        assertTrue(message(new PrintPackageRequest("WEB", "canonical", "66", "standard", null, null, "split",
            "long", "--out=/etc/passwd", "none", 3, null)).contains("colour"));
    }

    @Test
    void aTextModeThatIsNotBuiltIsRefused() {
        assertTrue(message(with("WEB", "chronological", "66", "standard", null, null, "none", "ORIGINAL"))
            .contains("verse-numbered"));
        assertEquals("standard", with("WEB", "chronological", "66", "standard", null, null, "none",
            "CHAPTERS_VERSES").validate().trim());
    }

    @Test
    void theApocryphaNeedTheTraditionalOrder() {
        assertTrue(message(with("WEB", "chronological", "full", "standard", null, null, "none", null))
            .contains("apocrypha"));
        assertTrue(message(with("WEB", "writing", "full", "standard", null, null, "none", null))
            .contains("apocrypha"));
        assertEquals("full", with("WEB", "canonical", "full", "standard", null, null, "none", null).validate().canon());
    }

    @Test
    void sizesAreRangeCheckedAndRounded() {
        assertTrue(message(with("WEB", "canonical", "66", "standard", 12.0, null, "none", null)).contains("7.2"));
        assertTrue(message(with("WEB", "canonical", "66", "standard", null, 3, "none", null)).contains("8 to 40"));
        assertEquals(8.8, with("WEB", "canonical", "66", "standard", 8.8000001, 12, "none", null).validate().body());
    }

    @Test
    void marginCrossReferencesTakeTheWideTrim() {
        final PrintPackageRequest.Valid v = with("WEB", "chronological", "66", "standard", 9.4, 12, "margin", null).validate();
        assertEquals("notes", v.trim());
        assertNull(v.outer());                      // the trim sets its own fore-edge
        final List<String> a = v.arguments("s.py", Path.of("o.zip"));
        assertTrue(a.containsAll(List.of("--trim", "notes", "--xrefs", "margin", "--xref-top", "3")));
        assertFalse(a.contains("--outer"));
        assertTrue(v.filename().contains("xrefs-margin"));
    }

    @Test
    void inlineCrossReferencesKeepTheChosenTrim() {
        final PrintPackageRequest.Valid v = with("KJV", "chronological", "66", "large", null, null, "inline", null).validate();
        assertEquals("large", v.trim());
        assertTrue(v.arguments("s.py", Path.of("o.zip")).containsAll(List.of("--xrefs", "inline")));
    }

    @Test
    void theWideTrimIsOnlyForTheMargin() {
        assertTrue(message(with("WEB", "chronological", "66", "notes", null, null, "none", null)).contains("margin"));
    }

    @Test
    void crossReferencesAreEnglishOnlySoFar() {
        assertTrue(message(with("ONAV", "canonical", "66", "standard", null, null, "inline", null))
            .contains("English"));
    }

    @Test
    void anArabicEditionIsSetInItsOwnTypeface() {
        final PrintPackageRequest.Valid v = with("ONAV", "canonical", "66", "standard", null, null, "none", null).validate();
        assertEquals("fonts/scheherazade", v.fontDir());
        assertEquals("ScheherazadeNew", v.fontFamily());
        assertTrue(v.arguments("s.py", Path.of("o.zip")).contains("bible-ar-onav"));
    }

    @Test
    void crossReferencesPerVerseAreOneToFive() {
        assertTrue(message(new PrintPackageRequest("WEB", "canonical", "66", "standard", null, null, "split",
            "long", "black", "inline", 9, null)).contains("1 to 5"));
    }
}
