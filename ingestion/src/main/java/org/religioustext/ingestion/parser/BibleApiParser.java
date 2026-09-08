package org.religioustext.ingestion.parser;

import org.religioustext.ingestion.config.BibleSourcesConfig.TranslationDef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses API.Bible chapter JSON into our canonical verse/chapter model.
 *
 * API.Bible content is a tree of para/char/verse tags with text nodes.
 * Each text node carries a verseId attribute (e.g. "GEN.1.1").
 * We collect all text fragments per verseId to build clean verse text.
 *
 * Section titles appear as para items with style "s", "s1", "s2", "ms".
 */
@Component
public class BibleApiParser {

    private static final Logger log = LoggerFactory.getLogger(BibleApiParser.class);

    /**
     * Parses a single API.Bible chapter response into an ordered map of
     * verseNumber -> verse text, plus an optional chapter title.
     *
     * @param chapterData  the "data" object from the API.Bible chapter response
     * @return             ParsedChapter with verses and optional title
     */
    @SuppressWarnings("unchecked")
    public ParsedChapter parseChapter(final Map<String, Object> aChapterData) {
        final String chapterId  = (String) aChapterData.get("id");
        final String reference  = (String) aChapterData.get("reference");
        final List<Object> content = (List<Object>) aChapterData.get("content");

        final Map<String, StringBuilder> verseTextMap = new LinkedHashMap<>();
        final List<String>               sectionTitles = new ArrayList<>();
        final String[]                   firstTitle    = {null};

        if (content != null) {
            for (final Object item : content) {
                if (item instanceof Map) {
                    processNode((Map<String, Object>) item, verseTextMap
                        , sectionTitles, firstTitle);
                }
            }
        }

        // Build verse list in order
        final List<ParsedVerse> verses = new ArrayList<>();
        for (final Map.Entry<String, StringBuilder> entry : verseTextMap.entrySet()) {
            final String verseId = entry.getKey();           // e.g. "GEN.1.1"
            final String text    = entry.getValue().toString().trim();
            if (!text.isEmpty()) {
                final int verseNum = parseVerseNumber(verseId);
                verses.add(new ParsedVerse(verseNum, verseId, text));
            }
        }

        log.debug("Parsed chapter {} — {} verses, title: {}"
            , chapterId, verses.size(), firstTitle[0]);

        return new ParsedChapter(chapterId, reference, firstTitle[0], verses);
    }

    /**
     * Recursively processes a content node, collecting verse text and titles.
     */
    @SuppressWarnings("unchecked")
    private void processNode(
             final Map<String, Object>         aNode
            , final Map<String, StringBuilder>  aVerseTextMap
            , final List<String>                theSectionTitles
            , final String[]                    aFirstTitle) {

        final String type = (String) aNode.get("type");
        final String name = (String) aNode.get("name");

        // Text node — append to current verse
        if ("text".equals(type)) {
            final Map<String, Object> attrs =
                (Map<String, Object>) aNode.get("attrs");
            final String verseId = attrs != null ? (String) attrs.get("verseId") : null;
            final String text    = (String) aNode.get("text");

            if (verseId != null && text != null && !text.trim().isEmpty()) {
                aVerseTextMap
                    .computeIfAbsent(verseId, k -> new StringBuilder())
                    .append(text);
            }
            return;
        }

        // Tag node — check if it's a section title or verse marker
        if ("tag".equals(type)) {
            final Map<String, Object> attrs =
                (Map<String, Object>) aNode.get("attrs");
            final String style = attrs != null ? (String) attrs.get("style") : null;

            // Section title styles: s, s1, s2, ms, mr
            if (style != null && (style.startsWith("s") || style.equals("ms")
                    || style.equals("mr")) && !"sp".equals(style)) {
                final String titleText = extractText(aNode);
                if (titleText != null && !titleText.isBlank()) {
                    theSectionTitles.add(titleText.trim());
                    if (aFirstTitle[0] == null) {
                        aFirstTitle[0] = titleText.trim();
                    }
                }
                return; // Don't recurse into title nodes for verse text
            }

            // Skip verse number tags (style "v") — we use verseId instead
            if ("verse".equals(name) && "v".equals(style)) {
                return;
            }
        }

        // Recurse into children
        final List<Object> items = (List<Object>) aNode.get("items");
        if (items != null) {
            for (final Object child : items) {
                if (child instanceof Map) {
                    processNode((Map<String, Object>) child
                        , aVerseTextMap, theSectionTitles, aFirstTitle);
                }
            }
        }
    }

    /**
     * Extracts all text from a node and its children as a flat string.
     */
    @SuppressWarnings("unchecked")
    private String extractText(final Map<String, Object> aNode) {
        if ("text".equals(aNode.get("type"))) {
            return (String) aNode.get("text");
        }
        final List<Object> items = (List<Object>) aNode.get("items");
        if (items == null) return null;
        final StringBuilder sb = new StringBuilder();
        for (final Object child : items) {
            if (child instanceof Map) {
                final String t = extractText((Map<String, Object>) child);
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }

    /**
     * Extracts verse number from verseId e.g. "GEN.1.1" -> 1
     */
    private int parseVerseNumber(final String aVerseId) {
        try {
            final String[] parts = aVerseId.split("\\.");
            return Integer.parseInt(parts[parts.length - 1]);
        } catch (final Exception e) {
            return 0;
        }
    }

    /**
     * Serialises a book to an XML string fragment for BaseX insertion.
     */
    public String serialiseBook(
             final TranslationDef           aTranslationDef
            , final String                   aBookName
            , final String                   aBookAltName
            , final int                      aCanonicalOrder
            , final String                   aTestament
            , final List<ParsedChapter>      theChapters) {
        return serialiseBook(aTranslationDef, aBookName, aBookAltName
            , aCanonicalOrder, aTestament, theChapters, null);
    }

    public String serialiseBook(
             final TranslationDef           aTranslationDef
            , final String                   aBookName
            , final String                   aBookAltName
            , final int                      aCanonicalOrder
            , final String                   aTestament
            , final List<ParsedChapter>      theChapters
            , final String                   aBookCode) {

        final StringBuilder sb = new StringBuilder();
        sb.append("<book xmlns=\"http://religioustext.org/schema/1.0\"");
        sb.append(" name=\"").append(escapeXml(aBookName)).append("\"");
        sb.append(" canonicalOrder=\"").append(aCanonicalOrder).append("\"");
        sb.append(" testament=\"").append(aTestament).append("\"");
        if (aBookCode != null) {
            sb.append(" code=\"").append(escapeXml(aBookCode)).append("\"");
        }
        if (aBookAltName != null) {
            sb.append(" altName=\"").append(escapeXml(aBookAltName)).append("\"");
        }
        sb.append(">");

        for (final ParsedChapter chapter : theChapters) {
            if (chapter.verses().isEmpty()) continue;

            // Extract chapter number from id e.g. "GEN.1" -> 1
            final int chapterNum = parseChapterNumber(chapter.id());

            sb.append("<chapter number=\"").append(chapterNum).append("\"");
            if (chapter.title() != null) {
                sb.append(" title=\"").append(escapeXml(chapter.title())).append("\"");
            }
            sb.append(">");

            for (final ParsedVerse verse : chapter.verses()) {
                sb.append("<verse");
                sb.append(" number=\"").append(verse.number()).append("\"");
                sb.append(" bookName=\"").append(escapeXml(aBookName)).append("\"");
                sb.append(" chapterNumber=\"").append(chapterNum).append("\"");
                if (chapter.title() != null) {
                    sb.append(" chapterTitle=\"")
                      .append(escapeXml(chapter.title())).append("\"");
                }
                sb.append(">");
                sb.append(escapeXml(verse.text()));
                sb.append("</verse>");
            }

            sb.append("</chapter>");
        }

        sb.append("</book>");
        return sb.toString();
    }

    private int parseChapterNumber(final String aChapterId) {
        try {
            return Integer.parseInt(aChapterId.split("\\.")[1]);
        } catch (final Exception e) {
            return 0;
        }
    }

    private String escapeXml(final String aValue) {
        if (aValue == null) return "";
        return aValue
            .replace("&",  "&amp;")
            .replace("\"", "&quot;")
            .replace("<",  "&lt;")
            .replace(">",  "&gt;")
            .replaceAll("[\\r\\n\\t]+", " ")
            .trim();
    }

    // ── Result records ────────────────────────────────────────────────

    public record ParsedChapter(
         String            id
        , String            reference
        , String            title
        , List<ParsedVerse> verses) {}

    public record ParsedVerse(
         int    number
        , String verseId
        , String text) {}
}
