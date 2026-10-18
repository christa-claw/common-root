// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.VerseNumbering.Ref;
import org.religioustext.app.service.VerseNumbering.Scheme;

import static org.assertj.core.api.Assertions.assertThat;

/** Against the shipped verse map and scheme table: the real data, not a fixture. */
class VerseNumberingTest {

    private final VerseNumbering n = new VerseNumbering();

    @Test
    void aKjvNumberedEditionGetsTheKjvVerseForAHebrewOne() {
        // Gen 32:1 in the Hebrew text is Gen 31:55 in the KJV (the map's own first entry)
        assertThat(n.fromHebrew("bible-kjv-1611", "GEN", 32, 1)).contains(new Ref("GEN", 31, 55));
        assertThat(n.fromHebrew("bible-kjv-1611", "GEN", 32, 2)).contains(new Ref("GEN", 32, 1));
    }

    @Test
    void aVerseTheMapDoesNotListIsTheSameInEnglishNumbering() {
        assertThat(n.fromHebrew("bible-kjv-1611", "GEN", 1, 1)).contains(new Ref("GEN", 1, 1));
    }

    @Test
    void theNivIsConfirmedEnglishNumbering() {
        assertThat(n.schemeOf("bible-niv-2011")).isEqualTo(Scheme.KJV);
        assertThat(n.fromHebrew("bible-niv-2011", "JOL", 3, 1)).contains(new Ref("JOL", 2, 28));
    }

    @Test
    void joelAndMalachiAndThePsalmTitlesAreMapped() {
        assertThat(n.fromHebrew("bible-kjv-1611", "JOL", 4, 1)).contains(new Ref("JOL", 3, 1));
        assertThat(n.fromHebrew("bible-kjv-1611", "MAL", 3, 19)).contains(new Ref("MAL", 4, 1));
        assertThat(n.fromHebrew("bible-kjv-1611", "PSA", 51, 3)).contains(new Ref("PSA", 51, 1));   // title is verse 1/2 in Hebrew
    }

    @Test
    void aHebrewNumberedEditionUsesTheHebrewReferenceUnchanged() {
        assertThat(n.schemeOf("bible-he-wlc")).isEqualTo(Scheme.HEBREW);
        assertThat(n.fromHebrew("bible-he-wlc", "GEN", 32, 1)).contains(new Ref("GEN", 32, 1));
        assertThat(n.schemeOf("bible-lut1912-1912")).isEqualTo(Scheme.HEBREW);
    }

    @Test
    void anEditionWithUnknownNumberingGetsNoAnswerRatherThanAGuess() {
        assertThat(n.schemeOf("bible-ru-synodal")).isEqualTo(Scheme.UNKNOWN);       // 417 differing chapters, unexplained
        assertThat(n.schemeOf("bible-fr-segond-1910")).isEqualTo(Scheme.UNKNOWN);   // Psalm titles only: mixed
        assertThat(n.schemeOf("bible-la-vulgate")).isEqualTo(Scheme.UNKNOWN);       // Greek/Latin Psalter
        assertThat(n.schemeOf("no-such-edition")).isEqualTo(Scheme.UNKNOWN);
        assertThat(n.fromHebrew("bible-ru-synodal", "GEN", 1, 1)).isEmpty();
    }

    @Test
    void aChapterWhoseVerseCountDiffersFromTheKjvGetsNoTranslationLine() {
        // RVR09 splits/merges verses in NUM 12 (listed in chapter-differences.properties); GEN 1 is aligned
        assertThat(n.schemeOf("bible-rvr09-1909")).isEqualTo(Scheme.KJV);
        assertThat(n.fromHebrew("bible-rvr09-1909", "GEN", 1, 1)).contains(new Ref("GEN", 1, 1));
        assertThat(n.fromHebrew("bible-rvr09-1909", "NUM", 12, 1)).isEmpty();
        assertThat(n.fromHebrew("bible-niv-2011", "NUM", 1, 1)).isEmpty();      // the NIV merges verses there
    }

    @Test
    void theCheckUsesTheChapterTheKjvHasTheVerseIn() {
        // Hebrew Gen 32:1 is KJV Gen 31:55: it is GEN 31 that must be aligned, not GEN 32. Both are aligned
        // for the KJV itself (the reference has no differences).
        assertThat(n.fromHebrew("bible-kjv-1611", "GEN", 32, 1)).contains(new Ref("GEN", 31, 55));
    }

    @Test
    void osisReferencesParseToUsfm() {
        assertThat(VerseNumbering.parse("Gen.32.1")).isEqualTo(new Ref("GEN", 32, 1));
        assertThat(VerseNumbering.parse("1Kgs.18.34!a")).isEqualTo(new Ref("1KI", 18, 34));
        assertThat(VerseNumbering.parse("Ps.51.3")).isEqualTo(new Ref("PSA", 51, 3));
        assertThat(VerseNumbering.parse("Nonsense.1.1")).isNull();
        assertThat(VerseNumbering.parse("Gen.x.1")).isNull();
        assertThat(VerseNumbering.parse("")).isNull();
    }

    @Test
    void aMissingMapGivesNoAnswerForKjvEditionsButStillServesHebrewOnes() {
        final VerseNumbering broken = new VerseNumbering("/no/such/map.xml", "/versification/schemes.properties");
        assertThat(broken.fromHebrew("bible-kjv-1611", "GEN", 32, 1)).isEmpty();           // never the unmapped (wrong) verse
        assertThat(broken.fromHebrew("bible-he-wlc", "GEN", 32, 1)).contains(new Ref("GEN", 32, 1));
    }

    @Test
    void theGreekNewTestamentKeepsItsNumbersInKjvAndHebrewNumberedEditions() {
        assertThat(n.fromGreek("bible-kjv-1611", "JHN", 3, 16)).contains(new Ref("JHN", 3, 16));
        assertThat(n.fromGreek("bible-he-wlc", "JHN", 3, 16)).contains(new Ref("JHN", 3, 16));
        assertThat(n.fromGreek("bible-ru-synodal", "JHN", 3, 16)).isEmpty();      // numbering unknown: no guess
    }
}
