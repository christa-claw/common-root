// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.service;

import org.junit.jupiter.api.Test;
import org.religioustext.app.service.WordLayerService.Word;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WordLayerServiceTest {

    private static final String MAQQEF = "־";

    @Test
    void parsesRowsIntoWordsByVerseKeepingNullsAndMarks() {
        final Map<Integer, List<Word>> m = WordLayerService.parse(List.of(
            "1|01xeN|H7225||" + "|בראשית",
            "1|01Nvk|H1254|a||ברא",
            "1|0129t||| ׃|לְ",         // prefix-only word, a mark with a leading space
            "2|0abcd|H430|||אלהים"));
        assertThat(m.keySet()).containsExactly(1, 2);
        final List<Word> v1 = m.get(1);
        assertThat(v1).hasSize(3);
        assertThat(v1.get(0)).isEqualTo(new Word("01xeN", "בראשית", "H7225", null, ""));
        assertThat(v1.get(1).label()).isEqualTo("H1254a");
        assertThat(v1.get(2).strongs()).isNull();
        assertThat(v1.get(2).label()).isNull();
        assertThat(v1.get(2).after()).isEqualTo(" ׃");
    }

    @Test
    void parsesOccurrenceRowsAndSkipsBadOnes() {
        final var occ = WordLayerService.parseOccurrences(List.of(
            "GEN|1|1|אֱלֹהִים", "GEN|x|1|bad", "junk", "EXO|3|14|a|b"));
        assertThat(occ).containsExactly(
            new WordLayerService.Occurrence("GEN", 1, 1, "אֱלֹהִים"),
            new WordLayerService.Occurrence("EXO", 3, 14, "a|b"));      // text is last: a bar in it stays whole
    }

    @Test
    void aRowThatDoesNotParseIsSkipped() {
        assertThat(WordLayerService.parse(List.of("junk", "x|1|H1|||t", "3|a|H1|||t"))).containsOnlyKeys(3);
    }

    @Test
    void textContainingABarSurvives() {
        // the text is the LAST field, so a stray bar in it stays whole
        assertThat(WordLayerService.parse(List.of("1|i|H1|||a|b")).get(1).get(0).text()).isEqualTo("a|b");
    }

    @Test
    void reconstructJoinsWithSpacesButNotAfterAMaqqefOrBeforeTheEnd() {
        final List<Word> ws = List.of(
            new Word("1", "עַל", "H5921", "a", MAQQEF),
            new Word("2", "פְּנֵי", "H6440", null, ""),
            new Word("3", "הַמָּיִם", "H4325", null, "׃"));
        assertThat(WordLayerService.reconstruct(ws)).isEqualTo("עַל" + MAQQEF + "פְּנֵי הַמָּיִם׃");
    }

    @Test
    void gapAfterFollowsTheSameRule() {
        final List<Word> ws = List.of(
            new Word("1", "a", null, null, MAQQEF), new Word("2", "b", null, null, ""),
            new Word("3", "c", null, null, ""));
        assertThat(WordLayerService.gapAfter(ws, 0)).isEmpty();   // after a maqqef
        assertThat(WordLayerService.gapAfter(ws, 1)).isEqualTo(" ");
        assertThat(WordLayerService.gapAfter(ws, 2)).isEmpty();   // last word
    }

    @Test
    void layoutGapsAndReconstructAgree() {
        final List<Word> ws = List.of(
            new Word("1", "x", null, null, MAQQEF), new Word("2", "y", null, null, " ׀"),
            new Word("3", "z", null, null, "׃"));
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ws.size(); i++)
            sb.append(ws.get(i).text()).append(ws.get(i).after()).append(WordLayerService.gapAfter(ws, i));
        assertThat(sb.toString().strip()).isEqualTo(WordLayerService.reconstruct(ws));
    }
}
