// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** {@code cN.hl} — extra passages flashed on landing, in any book.
 *
 *  It exists because a reordered edition's point is the passage that MOVED,
 *  which is in a different book from the one the link opens at. {@code ref} is
 *  one book by construction, so highlights had to be a separate list rather
 *  than a widening of ref into a cross-book range: a range would be forced to
 *  paint the anchor too, and the anchor is the part that did not move.
 */
class ReaderLinkHighlightTest {

    @Test
    void highlightsRoundTrip() {
        final ReaderLink.ColSpec c = new ReaderLink.ColSpec();
        c.src = "kjv";
        c.ref = ReaderLink.Ref.parse("DEU.34.1");
        c.highlights.add(ReaderLink.Ref.parse("PSA.90"));
        c.highlights.add(ReaderLink.Ref.parse("PSA.91"));

        final String link = ReaderLink.build(List.of(c), false);
        assertThat(link).contains("c1.hl=PSA.90,PSA.91");

        final List<ReaderLink.Ref> back =
            ReaderLink.parse(Map.of("c1.src", "kjv", "c1.ref", "DEU.34.1",
                                    "c1.hl", "PSA.90,PSA.91"))
                      .cols.get(0).highlights;
        assertThat(back).hasSize(2);
        assertThat(back.get(0).unit()).isEqualTo("PSA");
        assertThat(back.get(0).a()).isEqualTo(90);
        assertThat(back.get(1).a()).isEqualTo(91);
    }

    @Test
    void absentHighlightsAreEmptyNotNull() {
        assertThat(ReaderLink.parse(Map.of("c1.src", "kjv")).cols.get(0).highlights)
            .isEmpty();
        final ReaderLink.ColSpec bare = new ReaderLink.ColSpec();
        bare.src = "kjv";
        assertThat(ReaderLink.build(List.of(bare), false)).doesNotContain("hl=");
    }

    @Test
    void rangesAndVersesSurvive() {
        final List<ReaderLink.Ref> hl =
            ReaderLink.parseHighlights("ISA.52.13-53.12,PSA.90.1-17,JHN.3.16");
        assertThat(hl).hasSize(3);
        assertThat(hl.get(0).endA()).isEqualTo(53);
        assertThat(hl.get(0).endB()).isEqualTo(12);
        assertThat(hl.get(1).b()).isEqualTo(1);
        assertThat(hl.get(1).endB()).isEqualTo(17);
        assertThat(hl.get(2).hasEnd()).isFalse();
    }

    /** Lenient like the rest of the codec: a hand-edited link degrades to
     *  fewer highlights rather than to an error page. */
    @Test
    void rubbishEntriesAreDroppedNotFatal() {
        final List<ReaderLink.Ref> hl =
            ReaderLink.parseHighlights("PSA.90,,nonsense,PSA,.,PSA.91");
        assertThat(hl).hasSize(2);
        assertThat(hl.get(0).a()).isEqualTo(90);
        assertThat(hl.get(1).a()).isEqualTo(91);
        assertThat(ReaderLink.parseHighlights(null)).isEmpty();
        assertThat(ReaderLink.parseHighlights("   ")).isEmpty();
    }

    /** A crafted link must not make every tick of the flash interval scan for
     *  an unbounded number of spans. */
    @Test
    void highlightCountIsCapped() {
        final StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            if (i > 1) many.append(',');
            many.append("PSA.").append(i);
        }
        assertThat(ReaderLink.parseHighlights(many.toString()))
            .hasSize(ReaderLink.MAX_HIGHLIGHTS);
    }
}
