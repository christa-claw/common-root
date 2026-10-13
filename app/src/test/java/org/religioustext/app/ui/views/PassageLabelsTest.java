// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PassageLabelsTest {

    @Test
    void aSingleVerseIsNamedInEnglishFromItsUsfmCode() {
        assertThat(PassageLabels.of("ROM", 5, 8)).isEqualTo("Romans 5:8");
        assertThat(PassageLabels.of("1CO", 13, 4)).isEqualTo("1 Corinthians 13:4");
    }

    @Test
    void theCodeIsCaseInsensitive() {
        assertThat(PassageLabels.of("rom", 5, 8)).isEqualTo("Romans 5:8");
    }

    @Test
    void songOfSolomonUsesTheCorpusSpellingSoItMatchesTheReaderViewPassage() {
        assertThat(PassageLabels.bookName("SNG")).isEqualTo("Song of Solomon");
    }

    @Test
    void aCodeTheBundleDoesNotListIsUsedAsItIs() {
        assertThat(PassageLabels.of("TOB", 1, 1)).isEqualTo("TOB 1:1");
        assertThat(PassageLabels.bookName(null)).isEmpty();
    }

    @Test
    void aRangeInsideOneChapterNamesOnlyTheLastVerse() {
        assertThat(PassageLabels.range("PRO", 8, 22, 8, 30)).isEqualTo("Proverbs 8:22-30");
    }

    @Test
    void aRangeAcrossChaptersNamesBothEnds() {
        assertThat(PassageLabels.range("PSA", 89, 11, 90, 2)).isEqualTo("Psalms 89:11-90:2");
    }

    @Test
    void aLabelFromThePageIsKeptWhenItLooksLikeAPassage() {
        assertThat(PassageLabels.clean("Genesis 1:1")).isEqualTo("Genesis 1:1");
        assertThat(PassageLabels.clean("  1 Samuel 3:4 ")).isEqualTo("1 Samuel 3:4");
        assertThat(PassageLabels.clean("Psalms 89:11-90:2")).isEqualTo("Psalms 89:11-90:2");
    }

    @Test
    void aLabelFromThePageIsDroppedWhenItIsAnythingElse() {
        assertThat(PassageLabels.clean(null)).isNull();
        assertThat(PassageLabels.clean("   ")).isNull();
        assertThat(PassageLabels.clean("<script>alert(1)</script>")).isNull();
        assertThat(PassageLabels.clean("Genesis 1:1'}); evil({")).isNull();
        assertThat(PassageLabels.clean("x".repeat(61))).isNull();
    }

    @Test
    void aMissingOrEmptyEndIsTheSingleVerse() {
        assertThat(PassageLabels.range("ROM", 5, 8, null, null)).isEqualTo("Romans 5:8");
        assertThat(PassageLabels.range("ROM", 5, 8, 5, 8)).isEqualTo("Romans 5:8");
    }
}
