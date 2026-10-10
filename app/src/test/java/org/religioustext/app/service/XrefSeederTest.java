// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.assertj.core.api.Assertions.assertThat;

/** The pure parsing half of cross-reference seeding; the upsert is plain SQL. */
class XrefSeederTest {

    private static final String SAMPLE = String.join("\n",
        "From Verse\tTo Verse\tVotes\t#www.openbible.info CC-BY 2026-10-05",
        "Gen.1.1\tIsa.40.28\t67",
        "Gen.1.1\tJohn.1.1-John.1.3\t379",
        "Prov.8.22\tPs.89.11-Ps.90.2\t12",
        "1John.1.1\tPhlm.1.2\t0",
        "Gen.1.1\tRev.4.11\t-3",
        "Gen.1.1\tNotABook.1.1\t5",
        "Gen.1.1\tJohn.1.1-Rev.1.3\t5",
        "Gen.1.1\tJohn.1.1-Rev.x\t5",
        "Gen.1\tIsa.40.28\t5",
        "garbage");

    @Test
    void parsesSingleVersesAndRangesIntoUsfm() throws Exception {
        final var p = XrefSeeder.parse(new StringReader(SAMPLE));
        assertThat(p.rows()).containsExactly(
            new XrefSeeder.Xref("GEN", 1, 1, "ISA", 40, 28, null, null, 67),
            new XrefSeeder.Xref("GEN", 1, 1, "JHN", 1, 1, 1, 3, 379),
            new XrefSeeder.Xref("PRO", 8, 22, "PSA", 89, 11, 90, 2, 12),
            new XrefSeeder.Xref("1JN", 1, 1, "PHM", 1, 2, null, null, 0),
            // cross-book range: the start verse is kept, the foreign end dropped
            new XrefSeeder.Xref("GEN", 1, 1, "JHN", 1, 1, null, null, 5));
    }

    @Test
    void setsAsideNegativeVotesAndMalformedRows() throws Exception {
        final var p = XrefSeeder.parse(new StringReader(SAMPLE));
        assertThat(p.negative()).isEqualTo(1);
        // unknown book, unparseable range end, short ref, short line
        assertThat(p.malformed()).isEqualTo(4);
    }

    @Test
    void bookTableCoversTheProtestantCanonWithDistinctCodes() {
        assertThat(XrefSeeder.USFM_BY_OSIS).hasSize(66);
        assertThat(XrefSeeder.USFM_BY_OSIS.values()).doesNotHaveDuplicates();
    }
}
