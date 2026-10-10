// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** xrefs=1 is opt-in per column: absent means off, and only "on" is written. */
class ReaderLinkXrefsTest {

    @Test
    void xrefsFlagRoundTrips() {
        final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
        c.src = "kjv";
        c.xrefs = true;
        assertThat(ReaderLink.build(List.of(c), true)).contains("c1.xrefs=1");
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv", "c1.xrefs", "1")).cols.get(0).xrefs).isTrue();
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv", "c1.xrefs", "true")).cols.get(0).xrefs).isTrue();
    }

    @Test
    void offByDefaultAndNotWrittenWhenOff() {
        final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
        c.src = "kjv";
        assertThat(ReaderLink.build(List.of(c), true)).doesNotContain("xrefs");
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv")).cols.get(0).xrefs).isFalse();
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv", "c1.xrefs", "0")).cols.get(0).xrefs).isFalse();
    }
}
