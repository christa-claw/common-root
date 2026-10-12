// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** strongs=1 is opt-in per column: absent means off, and only "on" is written. */
class ReaderLinkStrongsTest {

    @Test
    void strongsFlagRoundTrips() {
        final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
        c.src = "kjv";
        c.strongs = true;
        assertThat(ReaderLink.build(List.of(c), true)).contains("c1.strongs=1");
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv", "c1.strongs", "1")).cols.get(0).strongs).isTrue();
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv", "c1.strongs", "true")).cols.get(0).strongs).isTrue();
    }

    @Test
    void offByDefaultAndNotWrittenWhenOff() {
        final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
        c.src = "kjv";
        assertThat(ReaderLink.build(List.of(c), true)).doesNotContain("strongs");
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv")).cols.get(0).strongs).isFalse();
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv", "c1.strongs", "0")).cols.get(0).strongs).isFalse();
    }

    @Test
    void independentOfTheOtherMarkerFlags() {
        final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
        c.src = "kjv";
        c.comments = true;
        c.xrefs = true;
        c.strongs = true;
        final String link = ReaderLink.build(List.of(c), true);
        assertThat(link).contains("comments=1").contains("xrefs=1").contains("strongs=1");
        final ReaderLink.ColSpec back = ReaderLink.parse(Map.of(
            "c1.src", "kjv", "c1.comments", "1", "c1.xrefs", "1", "c1.strongs", "1")).cols.get(0);
        assertThat(back.comments && back.xrefs && back.strongs).isTrue();
    }
}
