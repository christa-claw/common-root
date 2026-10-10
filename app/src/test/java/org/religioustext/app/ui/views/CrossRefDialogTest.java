// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.CrossRefQueryService.XRef;

import static org.assertj.core.api.Assertions.assertThat;

class CrossRefDialogTest {

    @Test
    void labelsSingleVerseSameChapterRangeAndCrossChapterRange() {
        assertThat(CrossRefDialog.label(new XRef("x", "GEN", 1, 1, null, null, 5))).isEqualTo("GEN 1:1");
        assertThat(CrossRefDialog.label(new XRef("x", "PRO", 8, 22, 8, 30, 5))).isEqualTo("PRO 8:22–30");
        assertThat(CrossRefDialog.label(new XRef("x", "PSA", 89, 11, 90, 2, 5))).isEqualTo("PSA 89:11–90:2");
        assertThat(CrossRefDialog.label(new XRef("x", "JHN", 1, 1, 1, 1, 5))).isEqualTo("JHN 1:1");
    }

    @Test
    void clipCollapsesWhitespaceAndTruncatesLongText() {
        assertThat(CrossRefDialog.clip("  In the \n beginning ")).isEqualTo("In the beginning");
        assertThat(CrossRefDialog.clip(null)).isEmpty();
        final String clipped = CrossRefDialog.clip("word ".repeat(100));
        assertThat(clipped).hasSizeLessThanOrEqualTo(160).endsWith("…");
    }
}
