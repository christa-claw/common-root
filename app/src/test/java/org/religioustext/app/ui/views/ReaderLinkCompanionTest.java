// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The companion token as a DEPTH: legacy 1/true still means "shown"; N opens
 *  N lineage generations; garbage degrades to off (lenient like the rest of
 *  the link grammar). */
class ReaderLinkCompanionTest {

    @Test
    void companionDepthRoundTrips() {
        final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
        c.src = "kjv";
        c.companion = 3;
        assertThat(ReaderLink.build(List.of(c), true)).contains("c1.companion=3");
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv", "c1.companion", "3"))
            .cols.get(0).companion).isEqualTo(3);
    }

    @Test
    void legacyCompanionFlagStillMeansOne() {
        assertThat(ReaderLink.parse(Map.of("c1.src", "q-ar", "c1.companion", "1"))
            .cols.get(0).companion).isEqualTo(1);
        assertThat(ReaderLink.parse(Map.of("c1.src", "q-ar", "c1.companion", "true"))
            .cols.get(0).companion).isEqualTo(1);
        assertThat(ReaderLink.parse(Map.of("c1.src", "q-ar", "c1.companion", "garbage"))
            .cols.get(0).companion).isEqualTo(0);
    }
}
