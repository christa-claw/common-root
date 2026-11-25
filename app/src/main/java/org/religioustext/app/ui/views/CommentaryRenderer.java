// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.ui.views;

import com.vaadin.flow.component.details.Details;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.dom.Element;
import org.religioustext.app.model.VerseRef;
import org.religioustext.app.service.CrossRefQueryService.XRef;
import org.religioustext.app.i18n.Numerals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Renders the verses of a commentary column (schema v1.5, {@code type="commentary"}).
 *
 * <p>A Bible column is one inline flow of verse text. A commentary is the opposite:
 * long prose per verse, often several separate comments on one verse, sometimes a
 * comment that covers a range, and chapter- or book-level introductions. So every
 * commented verse becomes its own block: a bold reference ({@code 3} or
 * {@code 3–5}) that doubles as the verse anchor the column sync scrolls to, then
 * each comment as its own paragraphs. Introductions sit, collapsed, above the first
 * commented verse of their chapter or book — a Matthew Henry chapter outline is
 * useful on demand and in the way otherwise.
 *
 * <p>The markup comes from {@link VerseRef#getCommentaryXml()} and is restricted
 * by the schema to {@code p}, {@code em}, {@code strong}, {@code note} and
 * {@code ref}; anything else is rendered as its text. It is turned into DOM
 * elements one by one, never inserted as HTML, so a source file cannot inject
 * markup into the page. A {@code ref} becomes a link that opens the passage in a
 * new column, like a cross-reference.
 *
 * @author Christa Claw
 * @version 1.0.5
 * @since 1.1.0
 */
final class CommentaryRenderer {

    private static final Logger log = LoggerFactory.getLogger(CommentaryRenderer.class);

    private static final DocumentBuilderFactory FACTORY = secureFactory();

    private CommentaryRenderer() { }

    /**
     * Whether a batch of verses comes from a commentary.
     *
     * @param theVerses one chapter group as the window loader hands it over
     * @return {@code true} if any verse carries commentary markup
     */
    static boolean isCommentary(final List<VerseRef> theVerses) {
        return theVerses != null && theVerses.stream().anyMatch(VerseRef::isCommentary);
    }

    /**
     * One commented verse as a block: any intros, the verse reference, the comments.
     *
     * @param aVerse       the verse; its {@link VerseRef#getCommentaryXml()} is rendered
     * @param aChapter     the chapter number (the anchor id carries it)
     * @param aNumerals    numeral locale for the reference, or {@code null} for Western digits
     * @param anIntroLabel the summary shown on an intro that has no heading of its own
     * @param anOpener     called with a {@code ref} target ({@code GEN.1.1}) when one is clicked
     * @return the block
     */
    static Div verseBlock(final VerseRef aVerse, final int aChapter, final Locale aNumerals,
                          final String anIntroLabel, final Consumer<String> anOpener) {
        final Div block = new Div();
        block.addClassName("cm-verse");
        block.getStyle().set("margin", "0 0 18px 0").set("line-height", "1.65").set("text-align", "start");

        final org.w3c.dom.Element root = parse(aVerse.getCommentaryXml());
        if (root == null) {                       // unparseable: show the plain text rather than nothing
            block.add(reference(aVerse, aChapter, aNumerals, null));
            block.add(new Span(aVerse.getDisplayContent(false)));
            return block;
        }
        boolean first = true;
        for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof org.w3c.dom.Element el)) continue;
            if ("intro".equals(el.getLocalName())) {
                block.add(intro(el, anIntroLabel, anOpener));
                continue;
            }
            if (!"comment".equals(el.getLocalName())) continue;
            final Div comment = new Div();
            comment.addClassName("cm-comment");
            comment.getStyle().set("margin", first ? "0" : "8px 0 0 0");
            if (!el.getAttribute("source").isBlank())
                comment.getElement().setAttribute("title", el.getAttribute("source"));
            if (!el.getAttribute("heading").isBlank())
                comment.add(heading(el.getAttribute("heading")));
            boolean firstPara = true;
            for (Node p = el.getFirstChild(); p != null; p = p.getNextSibling()) {
                if (!(p instanceof org.w3c.dom.Element pe) || !"p".equals(pe.getLocalName())) continue;
                final Div para = new Div();
                para.getStyle().set("margin", "0 0 6px 0");
                if (first && firstPara) {
                    para.add(reference(aVerse, aChapter, aNumerals, el));
                } else if (firstPara) {
                    final String range = rangeLabel(aVerse, el, aNumerals);
                    if (range != null) para.add(rangeTag(range));
                }
                inline(pe, para.getElement(), anOpener);
                comment.add(para);
                firstPara = false;
            }
            block.add(comment);
            first = false;
        }
        if (first) block.addComponentAsFirst(reference(aVerse, aChapter, aNumerals, null));
        return block;
    }

    // ── pieces ─────────────────────────────────────────────────────────

    /** The bold verse reference that starts a block. Carries the {@code v-CODE-ch-v}
     *  id every column uses, so scroll sync and "open at" land on it. */
    private static Span reference(final VerseRef aVerse, final int aChapter, final Locale aNumerals,
                                  final org.w3c.dom.Element aComment) {
        final String range = aComment == null ? null : rangeLabel(aVerse, aComment, aNumerals);
        final Span num = new Span((range != null ? range : Numerals.format(aVerse.getVerseNumber(), aNumerals)) + " ");
        num.addClassName("vn");
        num.addClassName("cm-ref");
        num.getStyle().set("font-weight", "700").set("color", "var(--lumo-primary-text-color)")
            .set("margin-inline-end", "4px");
        final String code = aVerse.getBookCode();
        if (code != null && !code.isBlank())
            num.setId("v-" + code + "-" + aChapter + "-" + aVerse.getVerseNumber());
        return num;
    }

    /** {@code 3–5} or {@code 3–4:2} when a comment covers more than its own verse, else null. */
    private static String rangeLabel(final VerseRef aVerse, final org.w3c.dom.Element aComment,
                                     final Locale aNumerals) {
        final int toVerse = intAttr(aComment, "toVerse");
        final int toChapter = intAttr(aComment, "toChapter");
        final String from = Numerals.format(aVerse.getVerseNumber(), aNumerals);
        if (toChapter > 0 && toChapter != aVerse.getChapterNumber())
            return from + "–" + Numerals.format(toChapter, aNumerals)
                + (toVerse > 0 ? ":" + Numerals.format(toVerse, aNumerals) : "");
        if (toVerse > aVerse.getVerseNumber())
            return from + "–" + Numerals.format(toVerse, aNumerals);
        return null;
    }

    private static Span rangeTag(final String aRange) {
        final Span s = new Span(aRange + " ");
        s.getStyle().set("font-weight", "600").set("color", "var(--lumo-secondary-text-color)")
            .set("margin-inline-end", "4px");
        return s;
    }

    private static Div heading(final String aText) {
        final Div h = new Div();
        h.setText(aText);
        h.getStyle().set("font-weight", "700").set("margin", "4px 0");
        return h;
    }

    /** A book or chapter introduction, collapsed. */
    private static Details intro(final org.w3c.dom.Element anIntro, final String aLabel,
                                 final Consumer<String> anOpener) {
        final Div body = new Div();
        body.getStyle().set("font-size", "0.95em");
        for (Node p = anIntro.getFirstChild(); p != null; p = p.getNextSibling()) {
            if (!(p instanceof org.w3c.dom.Element pe) || !"p".equals(pe.getLocalName())) continue;
            final Div para = new Div();
            para.getStyle().set("margin", "0 0 6px 0");
            inline(pe, para.getElement(), anOpener);
            body.add(para);
        }
        final String h = anIntro.getAttribute("heading");
        final Details d = new Details(h.isBlank() ? aLabel : h, body);
        d.addClassName("cm-intro");
        d.setOpened(false);
        d.getStyle().set("margin", "0 0 10px 0").set("padding", "2px 10px")
            .set("background", "var(--lumo-contrast-5pct)").set("border-radius", "6px");
        return d;
    }

    /** Copies one paragraph's mixed content into {@code aTarget}, element by element. */
    private static void inline(final org.w3c.dom.Element aSource, final Element aTarget,
                               final Consumer<String> anOpener) {
        for (Node n = aSource.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                aTarget.appendChild(Element.createText(n.getNodeValue()));
                continue;
            }
            if (!(n instanceof org.w3c.dom.Element el)) continue;
            switch (el.getLocalName()) {
                case "em" -> {
                    final Element e = new Element("em");
                    inline(el, e, anOpener);
                    aTarget.appendChild(e);
                }
                case "strong" -> {
                    final Element e = new Element("strong");
                    inline(el, e, anOpener);
                    aTarget.appendChild(e);
                }
                case "note" -> {
                    final Element e = new Element("span");
                    e.getClassList().add("cm-note");
                    e.getStyle().set("font-size", "0.85em").set("color", "var(--lumo-secondary-text-color)");
                    e.appendChild(Element.createText(" ["));
                    inline(el, e, anOpener);
                    e.appendChild(Element.createText("] "));
                    aTarget.appendChild(e);
                }
                case "ref" -> aTarget.appendChild(link(el, anOpener));
                default -> aTarget.appendChild(Element.createText(el.getTextContent()));
            }
        }
    }

    private static Element link(final org.w3c.dom.Element aRef, final Consumer<String> anOpener) {
        final String target = aRef.getAttribute("target");
        final Element a = new Element("a");
        a.setText(aRef.getTextContent());
        if (target.isBlank() || anOpener == null) return a;
        a.setAttribute("title", target);
        a.setAttribute("role", "link");
        a.setAttribute("tabindex", "0");
        a.getStyle().set("cursor", "pointer").set("color", "var(--lumo-primary-text-color)");
        a.addEventListener("click", e -> anOpener.accept(target));
        return a;
    }

    /**
     * A {@code ref} target as the cross-reference model the reader already knows
     * how to open: {@code GEN.1.1}, {@code ISA.7.14-16}, or a whole chapter
     * {@code PSA.23} (opened at its first verse).
     *
     * @param aTarget the target attribute
     * @return the reference, or {@code null} when it does not parse
     */
    static XRef toXRef(final String aTarget) {
        if (aTarget == null) return null;
        final java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("^([0-9A-Z]{3})\\.(\\d+)(?:\\.(\\d+)(?:-(\\d+))?)?$").matcher(aTarget.trim());
        if (!m.matches()) return null;
        final int chapter = Integer.parseInt(m.group(2));
        final int verse = m.group(3) == null ? 1 : Integer.parseInt(m.group(3));
        final Integer end = m.group(4) == null ? null : Integer.valueOf(m.group(4));
        return new XRef("commentary", m.group(1), chapter, verse,
                        end == null ? null : chapter, end, 0);
    }

    // ── parsing ────────────────────────────────────────────────────────

    private static int intAttr(final org.w3c.dom.Element anElement, final String aName) {
        try {
            final String v = anElement.getAttribute(aName);
            return v.isBlank() ? 0 : Integer.parseInt(v.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    /** The verse's fragments under one synthetic root, or {@code null} if they do not parse. */
    static org.w3c.dom.Element parse(final String aXml) {
        if (aXml == null || aXml.isBlank()) return null;
        try {
            final DocumentBuilder b = FACTORY.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader("<r>" + aXml + "</r>"))).getDocumentElement();
        } catch (final Exception e) {
            log.warn("Unparseable commentary markup ({} chars): {}", aXml.length(), e.getMessage());
            return null;
        }
    }

    /** No DTDs, no external entities: the input is data from a source file. */
    private static DocumentBuilderFactory secureFactory() {
        final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setExpandEntityReferences(false);
        f.setXIncludeAware(false);
        try {
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (final ParserConfigurationException e) {
            throw new IllegalStateException("XML parser cannot be secured", e);
        }
        return f;
    }
}
