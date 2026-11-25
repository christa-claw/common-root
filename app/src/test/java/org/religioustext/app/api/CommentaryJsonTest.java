// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Commentary markup as the {@code /commentaries} route serves it: plain runs,
 * ranges as link-format references, and only open licences.
 */
class CommentaryJsonTest {

    private static final String NS = "xmlns='http://religioustext.org/schema/1.0'";

    @Test
    @SuppressWarnings("unchecked")
    void aCommentBecomesParagraphsOfRuns() {
        final String xml = "<r><t id='commentary-mhc-en' author='Matthew Henry'>"
            + "<c ch='3' v='14'><comment " + NS + " n='1' toVerse='17'>"
            + "<p><strong>Verses 14-17</strong></p>"
            + "<p>As <ref target='NUM.21.9'>Numbers 21:9</ref> shows, <em>so must</em> the Son"
            + "<note>see <em>also</em> John 12</note>.</p>"
            + "<p>  </p>"
            + "</comment></c></t></r>";
        final List<CommentaryJson.Found> found = CommentaryJson.read(xml, "JHN");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).author()).isEqualTo("Matthew Henry");
        final Map<String, Object> c = found.get(0).comments().get(0);
        assertThat(c).containsEntry("from", "JHN.3.14").containsEntry("to", "JHN.3.17");
        final List<List<Map<String, Object>>> paras = (List<List<Map<String, Object>>>) c.get("paragraphs");
        assertThat(paras).hasSize(2);                                       // the blank one is dropped
        assertThat(paras.get(0)).containsExactly(Map.of("text", "Verses 14-17", "strong", true));
        assertThat(paras.get(1)).containsExactly(
            Map.of("text", "As "),
            Map.of("text", "Numbers 21:9", "ref", "NUM.21.9"),
            Map.of("text", " shows, "),
            Map.of("text", "so must", "em", true),
            Map.of("text", " the Son"),
            Map.of("text", "see ", "note", true),
            Map.of("text", "also", "em", true, "note", true),
            Map.of("text", " John 12", "note", true),
            Map.of("text", "."));
    }

    @Test
    void aRangeIntoTheNextChapterNamesIt() {
        final String xml = "<r><t id='x' author=''><c ch='4' v='31'><comment " + NS
            + " toChapter='5' toVerse='2'><p>x</p></comment></c>"
            + "<c ch='5' v='1'><comment " + NS + " toChapter='5'><p>y</p></comment></c></t></r>";
        final List<Map<String, Object>> cs = CommentaryJson.read(xml, "GEN").get(0).comments();
        assertThat(cs.get(0)).containsEntry("to", "GEN.5.2");
        assertThat(cs.get(1)).doesNotContainKey("to");                     // its own chapter: no range
    }

    @Test
    void introsComeWithTheirHeading() {
        final String xml = "<r><t id='x' author=''><i><intro " + NS + " heading='Genesis'>"
            + "<p>The book of beginnings.</p></intro></i></t></r>";
        final CommentaryJson.Found f = CommentaryJson.read(xml, "GEN").get(0);
        assertThat(f.comments()).isEmpty();
        assertThat(f.intros().get(0)).containsEntry("heading", "Genesis");
    }

    @Test
    void nothingAndGarbageReadAsNothing() {
        assertThat(CommentaryJson.read("<r/>", "GEN")).isEmpty();
        assertThat(CommentaryJson.read("<r><t", "GEN")).isEmpty();
        assertThat(CommentaryJson.read(null, "GEN")).isEmpty();
    }

    @Test
    void onlyOpenLicencesAreServed() {
        assertThat(ApiCommentaryController.open("Public Domain")).isTrue();
        assertThat(ApiCommentaryController.open("CC0")).isTrue();
        assertThat(ApiCommentaryController.open("CC0; Public Domain")).isTrue();
        assertThat(ApiCommentaryController.open("CC0; CC-BY-SA 3.0")).isFalse();
        assertThat(ApiCommentaryController.open("CC-BY 4.0")).isFalse();
        assertThat(ApiCommentaryController.open("")).isFalse();
    }
}
