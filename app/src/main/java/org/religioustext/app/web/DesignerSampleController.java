// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Christa Claw
package org.religioustext.app.web;

import org.religioustext.app.model.VerseRef;
import org.religioustext.app.service.CrossRefQueryService;
import org.religioustext.app.service.TextQueryService;
import org.religioustext.app.ui.views.reader.BookTitles;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The text the print designer's preview pages are set from: a handful of real
 * passages of the edition being ordered, so the preview shows the book as it
 * would print instead of a stand-in. Only the editions in {@link PrintEditions}
 * are served.
 *
 * <p>The passages are the ones the preview's pages need: the opening of the
 * Bible, a book boundary in each order, and the closing page of each order. The
 * page declares which verses of them go on which page; this just hands over the
 * chapters, with the book names in the edition's own language and, for English,
 * the traditional long titles.
 */
@RestController
public class DesignerSampleController {

    private record Ref(String code, int chapter) { }

    private static final List<Ref> REFS = List.of(
        new Ref("GEN", 1), new Ref("GEN", 2), new Ref("GEN", 50), new Ref("EXO", 1),
        new Ref("DEU", 34), new Ref("PSA", 90), new Ref("JOS", 1),
        new Ref("AMO", 1), new Ref("AMO", 2), new Ref("NUM", 36), new Ref("MAL", 1),
        new Ref("REV", 22), new Ref("2PE", 3));

    /** How many references per verse the printed margin carries. */
    private static final int XREFS_PER_VERSE = 3;

    private final TextQueryService text;
    private final CrossRefQueryService xrefs;
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public DesignerSampleController(final TextQueryService aText, final CrossRefQueryService anXrefs) {
        this.text = aText;
        this.xrefs = anXrefs;
    }

    @GetMapping(value = "/designer/sample", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> sample(@RequestParam("source") final String aSource) {
        final PrintEditions.Edition ed = PrintEditions.byAbbr(aSource).orElse(null);
        if (ed == null) return ResponseEntity.notFound().build();
        final Map<String, Object> body = cache.computeIfAbsent(ed.abbr(), k -> build(ed));
        return ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
            .body(body);
    }

    private Map<String, Object> build(final PrintEditions.Edition anEdition) {
        final Properties base = read("/i18n/booknames.properties");
        final Properties edLang = read("/i18n/booknames_" + anEdition.lang() + ".properties");
        final Properties own = edLang.isEmpty() ? base : edLang;

        final Map<String, String> names = new LinkedHashMap<>();
        final Map<String, String> longTitles = new LinkedHashMap<>();
        final Map<String, List<Object[]>> chapters = new LinkedHashMap<>();
        final Map<String, String> titles = new LinkedHashMap<>();
        for (final Ref r : REFS) {
            names.computeIfAbsent(r.code(), c -> own.getProperty(c, base.getProperty(c, c)));
            if ("en".equals(anEdition.lang())) {
                final String t = BookTitles.longTitle(base.getProperty(r.code()));
                if (t != null) longTitles.putIfAbsent(r.code(), t);
            }
            final List<VerseRef> verses =
                text.versesByCodeChapter(anEdition.id(), r.code(), r.chapter());
            final List<Object[]> rows = new ArrayList<>();
            for (final VerseRef v : verses) {
                if (v.getContent() != null && !v.getContent().isBlank())
                    rows.add(new Object[] {v.getVerseNumber(), v.getContent().strip()});
            }
            final String key = r.code() + "." + r.chapter();
            chapters.put(key, rows);
            if (!verses.isEmpty() && verses.get(0).getChapterTitle() != null
                    && !verses.get(0).getChapterTitle().isBlank())
                titles.put(key, verses.get(0).getChapterTitle().strip());
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("abbr", anEdition.abbr());
        out.put("lang", anEdition.lang());
        out.put("names", names);
        out.put("long", longTitles);
        out.put("titles", titles);
        out.put("chapters", chapters);
        final Map<String, Object> front = frontMatter(anEdition);
        if (front != null) out.put("front", front);
        final Map<String, Object> margin = crossReferences(anEdition);
        if (margin != null) out.put("xrefs", margin);
        return out;
    }

    /**
     * The cross references the reference edition prints in its margin, for the chapters the
     * preview shows: {@code "GEN.1": {"1": ["John 1:1–3", "Heb 11:3", "Isa 45:18"], ...}}. The
     * three most-voted for each verse, in the reader's own order. Only English editions, because
     * the references are anchored to the KJV numbering and named in English; null when there
     * are none, or when they cannot be read, so the preview simply shows no margin.
     */
    private Map<String, Object> crossReferences(final PrintEditions.Edition anEdition) {
        if (!"en".equals(anEdition.lang())) return null;
        try {
            final Map<String, Object> out = new LinkedHashMap<>();
            for (final Ref r : REFS) {
                final Map<String, List<String>> byVerse = new LinkedHashMap<>();
                xrefs.forChapter(r.code(), r.chapter()).forEach((verse, refs) ->
                    byVerse.put(String.valueOf(verse),
                        refs.stream().limit(XREFS_PER_VERSE).map(XrefLabels::label).toList()));
                if (!byVerse.isEmpty()) out.put(r.code() + "." + r.chapter(), byVerse);
            }
            return out.isEmpty() ? null : out;
        } catch (final RuntimeException e) {
            return null;
        }
    }

    /**
     * The title and about pages in the edition's own language, from the same
     * {@code print/frontmatter_<lang>.json} the PDF generator reads, so the preview
     * and the printed book say the same thing. Null for a language with no file
     * (its front pages are English, as in the book).
     *
     * <p>The rights text is returned with its pieces, and the page assembles it,
     * because what it says depends on the reading order: a licensed edition's
     * notice changes when the books are rearranged.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> frontMatter(final PrintEditions.Edition anEdition) {
        try (InputStream in = DesignerSampleController.class
                .getResourceAsStream("/print/frontmatter_" + anEdition.lang() + ".json")) {
            if (in == null) return null;
            final Map<String, Object> f = MAPPER.readValue(in, Map.class);
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("title", f.get("title"));
            out.put("order", f.get("order"));
            out.put("headings", f.get("headings"));
            out.put("about", f.get("about"));
            out.put("rights", f.get("rights"));
            final Map<String, Object> notices = (Map<String, Object>) f.getOrDefault("notices", Map.of());
            final Object mine = notices.get(anEdition.id());
            final Map<String, Object> notice = new LinkedHashMap<>();
            if (mine instanceof Map<?, ?> m) {
                notice.put("canonical", m.get("canonical"));
                notice.put("rearranged", m.get("rearranged"));
            } else {
                final Object one = mine != null ? mine : notices.get("public_domain");
                notice.put("canonical", one);
                notice.put("rearranged", one);
            }
            out.put("notice", notice);
            final Map<String, Object> names = (Map<String, Object>) f.getOrDefault("edition_names", Map.of());
            out.put("editionName", names.get(anEdition.id()));
            return out;
        } catch (final IOException e) {
            return null;
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private static Properties read(final String aPath) {
        final Properties p = new Properties();
        try (InputStream in = DesignerSampleController.class.getResourceAsStream(aPath)) {
            if (in != null) p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (final IOException ignored) {
            // A missing bundle falls back to English names.
        }
        return p;
    }
}
