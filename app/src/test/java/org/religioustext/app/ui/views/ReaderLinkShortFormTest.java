// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;
import org.religioustext.app.model.DisplayOptions.DisplayMode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Built links carry only what differs from the defaults, and keep a verse range. */
class ReaderLinkShortFormTest {

    private static ReaderLink.ColSpec col(final String src, final ReaderLink.Ref ref) {
        final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
        c.src = src;
        c.ref = ref;
        c.mode = DisplayMode.CHAPTERS_VERSES;
        return c;
    }

    @Test
    void singleColumnIsJustSrcAndRef() {
        final ReaderLink.Ref r = new ReaderLink.Ref(false, "EPH", 5, 24, 5, 28);
        assertThat(ReaderLink.build(List.of(col("kr3338", r)), true))
            .isEqualTo("c1.src=kr3338&c1.ref=EPH.5.24-28");
    }

    @Test
    void nonDefaultModeAndUnsyncedMultiColumnAreKept() {
        final ReaderLink.ColSpec a = col("niv", new ReaderLink.Ref(false, "JHN", 1, 0));
        a.mode = DisplayMode.TITLES;
        final ReaderLink.ColSpec b = col("q-en", new ReaderLink.Ref(true, "Q", 19, 0));
        assertThat(ReaderLink.build(List.of(a, b), false))
            .isEqualTo("sync=0&c1.src=niv&c1.ref=JHN.1&c1.mode=titles&c2.src=q-en&c2.ref=Q.19");
        assertThat(ReaderLink.build(List.of(a, b), true))
            .doesNotContain("sync").doesNotContain("cols");
    }
}
