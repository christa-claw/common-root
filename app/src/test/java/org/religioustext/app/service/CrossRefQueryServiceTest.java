// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.CrossRefQueryService.Row;
import org.religioustext.app.service.CrossRefQueryService.XRef;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CrossRefQueryServiceTest {

    private static Row row(final int verse, final String book, final int ch, final int v, final int votes) {
        return new Row(verse, new XRef("xrf-" + book + ch + v, book, ch, v, null, null, votes));
    }

    @Test
    void groupsByVerseOrdersByVotesAndDropsNegatives() {
        final var shaped = CrossRefQueryService.shape(List.of(
            row(16, "ROM", 5, 8, 40),
            row(1, "ISA", 40, 28, 67),
            row(16, "1JN", 4, 9, 120),
            row(16, "REV", 4, 11, -3),
            row(1, "JHN", 1, 1, 0)));
        assertThat(shaped.keySet()).containsExactly(1, 16);
        assertThat(shaped.get(16)).extracting(XRef::toBook).containsExactly("1JN", "ROM");
        assertThat(shaped.get(1)).extracting(XRef::toBook).containsExactly("ISA", "JHN");
    }

    @Test
    void equalVotesKeepAStableOrder() {
        final var shaped = CrossRefQueryService.shape(List.of(
            row(1, "PSA", 2, 1, 5), row(1, "GEN", 3, 1, 5), row(1, "GEN", 1, 9, 5)));
        assertThat(shaped.get(1)).extracting(x -> x.toBook() + x.toChapter())
            .containsExactly("GEN1", "GEN3", "PSA2");
    }

    @Test
    void verseWithOnlyNegativeReferencesIsAbsent() {
        assertThat(CrossRefQueryService.shape(List.of(row(3, "GEN", 1, 1, -1)))).isEmpty();
    }

    @Test
    void linkRefFollowsTheLinkFormatForSingleVersesAndRanges() {
        assertThat(new XRef("x", "ROM", 5, 8, null, null, 1).linkRef()).isEqualTo("ROM.5.8");
        assertThat(new XRef("x", "PRO", 8, 22, 8, 30, 1).linkRef()).isEqualTo("PRO.8.22-30");
        assertThat(new XRef("x", "PSA", 89, 11, 90, 2, 1).linkRef()).isEqualTo("PSA.89.11-90.2");
    }
}
