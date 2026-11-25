// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.dom.Element;
import org.junit.jupiter.api.Test;
import org.religioustext.app.model.VerseRef;
import org.religioustext.app.service.CrossRefQueryService.XRef;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommentaryRendererTest {

    private static final String NS = " xmlns=\"http://religioustext.org/schema/1.0\"";

    private static VerseRef verse(final int aVerse, final String aXml) {
        return VerseRef.builder().sourceId("commentary-x").bookName("Genesis").bookCode("GEN")
            .chapterNumber(1).verseNumber(aVerse).content("plain").commentaryXml(aXml).build();
    }

    private static List<Element> all(final Element aRoot) {
        final List<Element> out = new ArrayList<>();
        if (aRoot.isTextNode()) return out;   // text nodes have no tag
        out.add(aRoot);
        aRoot.getChildren().forEach(c -> out.addAll(all(c)));
        return out;
    }

    @Test
    void onlyVersesWithCommentaryMarkupAreCommentary() {
        assertFalse(CommentaryRenderer.isCommentary(List.of(verse(1, null), verse(2, ""))));
        assertTrue(CommentaryRenderer.isCommentary(List.of(verse(1, null),
            verse(2, "<comment" + NS + "><p>x</p></comment>"))));
    }

    @Test
    void aBlockCarriesTheVerseAnchorTheSyncScrollsTo() {
        final Div block = CommentaryRenderer.verseBlock(
            verse(3, "<comment" + NS + " n=\"1\" toVerse=\"5\"><p>Light <em>first</em>.</p></comment>"),
            1, null, "Introduction", t -> { });
        final Element anchor = all(block.getElement()).stream()
            .filter(e -> "v-GEN-1-3".equals(e.getAttribute("id"))).findFirst().orElse(null);
        assertNotNull(anchor);
        assertEquals("3–5 ", anchor.getText());   // the range the comment covers
        assertTrue(all(block.getElement()).stream().anyMatch(e -> "em".equals(e.getTag())));
    }

    @Test
    void aRefIsALinkThatOpensItsTarget() {
        final List<String> opened = new ArrayList<>();
        final Div block = CommentaryRenderer.verseBlock(
            verse(1, "<comment" + NS + "><p>See <ref target=\"PRO.8.22-24\">Pr 8:22-24</ref>.</p></comment>"),
            1, null, "Introduction", opened::add);
        final Element a = all(block.getElement()).stream().filter(e -> "a".equals(e.getTag()))
            .findFirst().orElseThrow();
        assertEquals("Pr 8:22-24", a.getText());
        assertEquals("PRO.8.22-24", a.getAttribute("title"));
    }

    @Test
    void markupOutsideTheSchemaIsRenderedAsTextNotAsHtml() {
        final Div block = CommentaryRenderer.verseBlock(
            verse(1, "<comment" + NS + "><p>a<script>alert(1)</script>b</p></comment>"),
            1, null, "Introduction", t -> { });
        assertTrue(all(block.getElement()).stream().noneMatch(e -> "script".equals(e.getTag())));
    }

    @Test
    void brokenMarkupFallsBackToThePlainText() {
        assertNull(CommentaryRenderer.parse("<comment><p>unclosed"));
        final Div block = CommentaryRenderer.verseBlock(verse(1, "<comment><p>unclosed"), 1, null, "I", t -> { });
        assertTrue(block.getElement().getTextRecursively().contains("plain"));
    }

    @Test
    void doctypesAreRefused() {
        assertNull(CommentaryRenderer.parse("<!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><p>&x;</p>"));
    }

    @Test
    void targetsBecomeCrossReferences() {
        final XRef single = CommentaryRenderer.toXRef("ISA.7.14");
        assertEquals("ISA", single.toBook());
        assertEquals(7, single.toChapter());
        assertEquals(14, single.toVerse());
        assertNull(single.toEndVerse());
        assertEquals("ISA.7.14-16", CommentaryRenderer.toXRef("ISA.7.14-16").linkRef());
        assertEquals(1, CommentaryRenderer.toXRef("PSA.23").toVerse());
        assertEquals("1CO.13.4", CommentaryRenderer.toXRef("1CO.13.4").linkRef());
        assertNull(CommentaryRenderer.toXRef("Genesis 1:1"));
    }
}
