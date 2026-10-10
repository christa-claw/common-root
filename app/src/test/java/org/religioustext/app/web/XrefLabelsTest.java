// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.CrossRefQueryService.XRef;
import org.religioustext.app.service.XrefSeeder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class XrefLabelsTest {

    private static XRef ref(final String book, final int ch, final int v, final Integer endCh, final Integer endV) {
        return new XRef("xrf-1", book, ch, v, endCh, endV, 10);
    }

    @Test
    void aSingleVerse() {
        assertEquals("Heb 11:3", XrefLabels.label(ref("HEB", 11, 3, null, null)));
    }

    @Test
    void aRangeWithinAChapter() {
        assertEquals("John 1:1–3", XrefLabels.label(ref("JHN", 1, 1, 1, 3)));
    }

    @Test
    void aRangeOverAChapterBoundary() {
        assertEquals("Ps 89:11–90:2", XrefLabels.label(ref("PSA", 89, 11, 90, 2)));
    }

    @Test
    void aRangeThatEndsWhereItStartedIsASingleVerse() {
        assertEquals("1 Cor 15:20", XrefLabels.label(ref("1CO", 15, 20, 15, 20)));
    }

    @Test
    void everyBookTheCrossReferencesUseHasAName() {
        final List<String> codes = List.of("GEN", "SNG", "JOL", "EZK", "NAM", "PHP", "PHM", "JHN", "1JN", "JUD", "REV");
        for (final String code : codes) {
            assertNotNull(XrefLabels.name(code), code);
            assertEquals(true, XrefSeeder.isBookCode(code), code);
        }
    }
}
