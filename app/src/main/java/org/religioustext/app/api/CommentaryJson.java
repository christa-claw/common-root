// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns commentary markup (schema v1.5: {@code <comment>} and {@code <intro>}
 * holding {@code <p>} paragraphs of mixed content) into the plain JSON the
 * {@code /commentaries} route serves, so a client never has to parse XML or
 * trust markup: a paragraph is a list of runs, each one piece of text with the
 * flags that apply to it.
 *
 * {@snippet lang="json" :
 * [ {"text": "The first verse "},
 *   {"text": "John 1:3", "ref": "JHN.1.3"},
 *   {"text": "in the beginning", "em": true},
 *   {"text": "a footnote", "note": true} ]
 * }
 *
 * <p>Nesting is flattened: {@code <em>} inside {@code <note>} is a run with
 * both {@code em} and {@code note}. A {@code <ref>} keeps only its text and
 * target. Any other element (the schema allows none) contributes its text, as
 * the site's {@code CommentaryRenderer} does.
 *
 * <p>Pattern: none — a pure function from one tree to another.
 *
 * @author Christa Claw
 * @version 1.1.0
 * @since 1.1.0
 */
final class CommentaryJson {

    private static final Logger log = LoggerFactory.getLogger(CommentaryJson.class);

    private static final DocumentBuilderFactory FACTORY = secureFactory();

    private CommentaryJson() { }

    /**
     * One commentary's findings for a verse, as {@code ApiCorpusService.commentariesOn}
     * returns them.
     *
     * @param id       the commentary's document id
     * @param author   the {@code @author}, possibly blank
     * @param intros   book and chapter introductions, as JSON maps
     * @param comments the comments covering the verse, as JSON maps
     */
    record Found(String id, String author, List<Map<String, Object>> intros, List<Map<String, Object>> comments) { }

    /**
     * Reads the query's {@code <r>} document.
     *
     * @param aXml     the document; {@code null} or blank reads as nothing found
     * @param aBookCode the book, to write each comment's {@code from}/{@code to} as link-format references
     * @return one entry per commentary, in document order
     */
    static List<Found> read(final String aXml, final String aBookCode) {
        final List<Found> out = new ArrayList<>();
        final Element root = parse(aXml);
        if (root == null) return out;
        for (final Element t : children(root, "t")) {
            final List<Map<String, Object>> intros = new ArrayList<>();
            final List<Map<String, Object>> comments = new ArrayList<>();
            for (final Element w : children(t, null)) {
                final Element inner = first(w);
                if (inner == null) continue;
                if ("i".equals(w.getLocalName())) intros.add(intro(inner));
                else if ("c".equals(w.getLocalName()))
                    comments.add(comment(inner, aBookCode, intAttr(w, "ch"), intAttr(w, "v")));
            }
            out.add(new Found(t.getAttribute("id"), t.getAttribute("author"), intros, comments));
        }
        return out;
    }

    /**
     * One comment: where it starts and, for a range, ends, an optional heading
     * and source, and its paragraphs.
     *
     * @param aComment  the {@code <comment>}
     * @param aBookCode the book code
     * @param aChapter  the chapter it is stored under
     * @param aVerse    the verse it is stored under
     * @return {@code from}, optional {@code to}, {@code heading}, {@code source}, {@code paragraphs}
     */
    static Map<String, Object> comment(final Element aComment, final String aBookCode,
                                       final int aChapter, final int aVerse) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("from", aBookCode + "." + aChapter + "." + aVerse);
        final int toChapter = intAttr(aComment, "toChapter");
        final int toVerse = intAttr(aComment, "toVerse");
        if (toChapter > aChapter) {
            m.put("to", aBookCode + "." + toChapter + (toVerse > 0 ? "." + toVerse : ""));
        } else if (toVerse > aVerse) {
            m.put("to", aBookCode + "." + aChapter + "." + toVerse);
        }
        putIfPresent(m, "heading", aComment.getAttribute("heading"));
        putIfPresent(m, "source", aComment.getAttribute("source"));
        m.put("paragraphs", paragraphs(aComment));
        return m;
    }

    /**
     * A book or chapter introduction.
     *
     * @param anIntro the {@code <intro>}
     * @return optional {@code heading}, and {@code paragraphs}
     */
    static Map<String, Object> intro(final Element anIntro) {
        final Map<String, Object> m = new LinkedHashMap<>();
        putIfPresent(m, "heading", anIntro.getAttribute("heading"));
        m.put("paragraphs", paragraphs(anIntro));
        return m;
    }

    /** The element's {@code <p>} children as lists of runs; empty paragraphs are left out. */
    private static List<List<Map<String, Object>>> paragraphs(final Element aParent) {
        final List<List<Map<String, Object>>> out = new ArrayList<>();
        for (final Element p : children(aParent, "p")) {
            final List<Map<String, Object>> runs = new ArrayList<>();
            runs(p, false, false, false, runs);
            if (runs.stream().anyMatch(r -> !((String) r.get("text")).isBlank())) out.add(runs);
        }
        return out;
    }

    private static void runs(final Node aNode, final boolean anEm, final boolean aStrong, final boolean aNote,
                             final List<Map<String, Object>> theRuns) {
        for (Node n = aNode.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n.getNodeType() == Node.TEXT_NODE || n.getNodeType() == Node.CDATA_SECTION_NODE) {
                add(theRuns, n.getNodeValue(), anEm, aStrong, aNote, null);
                continue;
            }
            if (!(n instanceof Element el)) continue;
            switch (el.getLocalName()) {
                case "em" -> runs(el, true, aStrong, aNote, theRuns);
                case "strong" -> runs(el, anEm, true, aNote, theRuns);
                case "note" -> runs(el, anEm, aStrong, true, theRuns);
                case "ref" -> {
                    final String target = el.getAttribute("target").trim();
                    add(theRuns, el.getTextContent(), anEm, aStrong, aNote, target.isEmpty() ? null : target);
                }
                default -> add(theRuns, el.getTextContent(), anEm, aStrong, aNote, null);
            }
        }
    }

    /** Appends a run, joining it to the previous one when the flags are the same and neither is a link. */
    private static void add(final List<Map<String, Object>> theRuns, final String aText, final boolean anEm,
                            final boolean aStrong, final boolean aNote, final String aRef) {
        if (aText == null || aText.isEmpty()) return;
        if (aRef == null && !theRuns.isEmpty()) {
            final Map<String, Object> last = theRuns.get(theRuns.size() - 1);
            if (!last.containsKey("ref") && last.containsKey("em") == anEm
                && last.containsKey("strong") == aStrong && last.containsKey("note") == aNote) {
                last.put("text", last.get("text") + aText);
                return;
            }
        }
        final Map<String, Object> run = new LinkedHashMap<>();
        run.put("text", aText);
        if (anEm) run.put("em", true);
        if (aStrong) run.put("strong", true);
        if (aNote) run.put("note", true);
        if (aRef != null) run.put("ref", aRef);
        theRuns.add(run);
    }

    // ── XML plumbing ───────────────────────────────────────────────────

    private static void putIfPresent(final Map<String, Object> aMap, final String aKey, final String aValue) {
        if (aValue != null && !aValue.isBlank()) aMap.put(aKey, aValue.trim());
    }

    /** Child elements, all of them or only those with the local name given. */
    private static List<Element> children(final Element aParent, final String aLocalName) {
        final List<Element> out = new ArrayList<>();
        for (Node n = aParent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && (aLocalName == null || aLocalName.equals(el.getLocalName()))) out.add(el);
        }
        return out;
    }

    private static Element first(final Element aParent) {
        final List<Element> all = children(aParent, null);
        return all.isEmpty() ? null : all.get(0);
    }

    private static int intAttr(final Element anElement, final String aName) {
        try {
            final String v = anElement.getAttribute(aName);
            return v.isBlank() ? 0 : Integer.parseInt(v.trim());
        } catch (final NumberFormatException e) {
            return 0;
        }
    }

    private static Element parse(final String aXml) {
        if (aXml == null || aXml.isBlank()) return null;
        try {
            final javax.xml.parsers.DocumentBuilder b = FACTORY.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new InputSource(new StringReader(aXml))).getDocumentElement();
        } catch (final Exception e) {
            log.warn("Unparseable commentary result ({} chars): {}", aXml.length(), e.getMessage());
            return null;
        }
    }

    /** No DTDs, no external entities: the input comes from source files. */
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
